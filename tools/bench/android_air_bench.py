#!/usr/bin/env python3
"""Persistent two-real-modem bench for an Android Ragtime LAN client.

Reuses Data2G's pipe:IN,OUT backend and the setup in real_modem_smoke.py.
Host A binds only the chosen address. Normally host B and desktop Ragtime
use loopback. With --remote-audio, one modem and desktop Ragtime instead
use host A, whose audio is relayed to the supplied TCP peer. No sound
devices, rig, fake modem, hosted service or RF is used.
"""
import argparse
import hashlib
import ipaddress
import json
import os
from pathlib import Path
import signal
import socket
import subprocess
import sys
import tempfile
import time

ROOT = Path(__file__).resolve().parents[2]


def write_json(path, value):
    temporary = path.with_suffix(".tmp")
    temporary.write_text(json.dumps(value, indent=2) + "\n")
    temporary.replace(path)


def current(output):
    pointer = output / "latest.json"
    if not pointer.exists():
        return None, None
    work = Path(json.loads(pointer.read_text())["work"])
    path = work / "status.json"
    return work, json.loads(path.read_text()) if path.exists() else None


def owned_process(work, status):
    if not status or status.get("state") not in ("starting", "ready"):
        return False
    try:
        command = subprocess.check_output(
            ["ps", "-p", str(status["pid"]), "-o", "args="], text=True).strip()
        return str(Path(__file__).resolve()) in command and str(work) in command and "serve" in command
    except subprocess.CalledProcessError:
        return False


def ready(address, port):
    try:
        with socket.create_connection((address, port), timeout=0.2):
            return True
    except OSError:
        return False


def serve(args):
    work = args.work.resolve()
    remote = bool(args.remote_audio or args.listen_audio)
    state = {"state": "starting", "pid": os.getpid(), "work": str(work),
             "phone": {"host": args.bind, "call": args.call, "locator": args.locator,
                       "kiss": args.kiss_port, "command": args.command_port, "data": args.command_port + 1},
             "desktop": {"host": "127.0.0.1", "call": "W1BBB", "locator": "FN31",
                         "kiss": 18110, "command": 18310, "data": 18311},
             "host_executable": str(args.host),
             "host_sha256": hashlib.sha256(args.host.read_bytes()).hexdigest(),
             "desktop_profile": str(work / "desktop.conf"), "children": []}
    if remote:
        state["remote_audio"] = args.remote_audio or args.listen_audio
        state["audio_connection"] = "client" if args.remote_audio else "listener"
        state["desktop"] = dict(state["phone"])
    status_path = work / "status.json"
    write_json(status_path, state)
    stopping = False

    def stop(_signal, _frame):
        nonlocal stopping
        stopping = True

    signal.signal(signal.SIGTERM, stop)
    signal.signal(signal.SIGINT, stop)
    processes, streams, fifos = [], [], [work / "a-to-b", work / "b-to-a"]
    try:
        hosts = [state["phone"]] if remote else [state["phone"], state["desktop"]]
        held = []
        try:
            for side in hosts:
                for kind in ("kiss", "command", "data"):
                    sock = socket.socket()
                    held.append(sock)
                    sock.bind((side["host"], side[kind]))
        finally:
            for sock in held:
                sock.close()
        for fifo in fifos:
            os.mkfifo(fifo)
        for index, side in enumerate(hosts):
            config = work / f"data2g-{index}.conf"
            config.write_text("")
            incoming, outgoing = (fifos[1], fifos[0]) if index == 0 else (fifos[0], fifos[1])
            command = [str(args.host), "--config", str(config), "--mycall", side["call"],
                       "--listen", side["host"], "--kiss-port", str(side["kiss"]),
                       "--command-port", str(side["command"]), "--audio-io", f"pipe:{incoming},{outgoing}",
                       "--no-rig", "--no-realtime", "--no-live-constellation", "--threads", "2",
                       "--record-dir", str(work / f"recordings-{index}"), "--stats-interval", "0"]
            stream = (work / f"data2g-{index}.log").open("wb")
            streams.append(stream)
            process = subprocess.Popen(command, cwd=work, stdout=stream, stderr=subprocess.STDOUT)
            processes.append(process)
            state["children"].append({"pid": process.pid, "command": command})
        if remote:
            command = [sys.executable, str(ROOT / "tools/bench/tcp_audio_bridge.py"),
                       "--input", str(fifos[0]), "--output", str(fifos[1]),
                       "--peer" if args.remote_audio else "--listen", args.remote_audio or args.listen_audio,
                       "--status", str(work / "audio-bridge.json")]
            stream = (work / "audio-bridge.log").open("wb")
            streams.append(stream)
            process = subprocess.Popen(command, cwd=work, stdout=stream, stderr=subprocess.STDOUT)
            processes.append(process)
            state["children"].append({"pid": process.pid, "command": command})
        deadline = time.monotonic() + 40
        for side in hosts:
            for kind in ("kiss", "command", "data"):
                while not ready(side["host"], side[kind]):
                    if stopping:
                        return
                    if any(p.poll() is not None for p in processes):
                        raise RuntimeError("Modem/bridge exited during startup; inspect data2g-*.log and audio-bridge.log")
                    if time.monotonic() > deadline:
                        raise RuntimeError(f"Timed out waiting for {side['host']}:{side[kind]}")
                    time.sleep(0.1)
        if remote:
            while not (work / "audio-bridge.json").exists():
                if stopping:
                    return
                if any(p.poll() is not None for p in processes) or time.monotonic() > deadline:
                    raise RuntimeError("Remote audio did not connect; inspect audio-bridge.log")
                time.sleep(0.1)
        side = state["desktop"]
        profile = (f"[Station]\nCallsign={side['call']}\nLocator={side['locator']}\n"
                   f"[Connection]\nHost={side['host']}\nKissPort={side['kiss']}\nCommandPort={side['command']}\nAcceptSessions=1\n"
                   "[Radio]\nSource=none\nUsSegmentsOnly=0\n"
                   "[Hamlib]\nKeyRig=0\nEarFilters=0\n"
                   "[Broadcast]\nSealChat=0\nAnswerRollCalls=0\n"
                   f"[Files]\nReceivedFolder={work / 'received'}\n")
        if args.auto_files:
            # Unattended file acceptance: session files from these calls and
            # every broadcast file are saved without a click on the desktop.
            profile += f"AutoAccept={args.auto_files}\nAutoReceiveBroadcast=1\n"
        (work / "desktop.conf").write_text(profile)
        command = [str(args.desktop), "--config", str(work / "desktop.conf")]
        stream = (work / "ragtime-desktop.log").open("wb")
        streams.append(stream)
        desktop = subprocess.Popen(command, cwd=work, stdout=stream, stderr=subprocess.STDOUT)
        processes.append(desktop)
        state["children"].append({"pid": desktop.pid, "command": command})
        state["state"] = "ready"
        write_json(status_path, state)
        print(json.dumps(state), flush=True)
        while not stopping:
            for index, process in enumerate(processes):
                result = process.poll()
                if result is not None:
                    if index == 2 and result == 0:
                        stopping = True  # closing the bench's desktop window stops this bench
                        break
                    raise RuntimeError(f"Bench child {process.pid} exited with status {result}")
            time.sleep(0.2)
    except Exception as error:
        state["state"] = "error"
        state["error"] = str(error)
        raise
    finally:
        # Desktop exits first, then both modems. Never touch another profile/process.
        for process in reversed(processes):
            if process.poll() is None:
                process.terminate()
            try:
                process.wait(timeout=8)
            except subprocess.TimeoutExpired:
                process.kill()
                process.wait()
        for stream in streams:
            stream.close()
        for fifo in fifos:
            if fifo.exists():
                fifo.unlink()
        if state["state"] != "error":
            state["state"] = "stopped"
        write_json(status_path, state)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("command", choices=("start", "stop", "status", "serve"))
    parser.add_argument("--output", type=Path, default=ROOT / "build-android-air-bench")
    parser.add_argument("--host", type=Path, help="Pinned release data2g-host executable")
    parser.add_argument("--desktop", type=Path, default=ROOT / "dist/macos/Ragtime.app/Contents/MacOS/Ragtime")
    parser.add_argument("--bind", help="This Mac's LAN IPv4 address; host B stays on loopback")
    audio = parser.add_mutually_exclusive_group()
    audio.add_argument("--remote-audio", help="Replace local host B with a raw TCP audio peer, HOST:PORT")
    audio.add_argument("--listen-audio", help="Replace local host B with an inbound raw audio connection, IPv4:PORT")
    parser.add_argument("--call", default="W1AAA", help="Host A callsign")
    parser.add_argument("--locator", default="CN87", help="Host A locator, or an empty string")
    parser.add_argument("--kiss-port", type=int, default=18100, help="Host A KISS port")
    parser.add_argument("--command-port", type=int, default=18300, help="Host A command port; data uses + 1")
    parser.add_argument("--auto-files", default="", metavar="CALLS",
                        help="Desktop saves session files from these comma-separated calls, and all broadcast files, unasked")
    parser.add_argument("--work", type=Path, help=argparse.SUPPRESS)
    args = parser.parse_args()
    args.output = args.output.resolve()
    if args.command in ("status", "stop"):
        work, state = current(args.output)
        if not owned_process(work, state):
            print(json.dumps(state or {"state": "not running"}, indent=2))
            return 0
        if args.command == "stop":
            os.kill(state["pid"], signal.SIGTERM)
            for _ in range(100):
                time.sleep(0.2)
                _, state = current(args.output)
                if not owned_process(work, state):
                    break
        print(json.dumps(state, indent=2))
        return 0
    if not args.host or not args.bind:
        parser.error("start/serve require --host and --bind")
    args.host, args.desktop = args.host.resolve(), args.desktop.resolve()
    for executable in (args.host, args.desktop):
        if not executable.is_file() or not os.access(executable, os.X_OK):
            parser.error(f"Not an executable: {executable}")
    address = ipaddress.ip_address(args.bind)
    if address.version != 4 or address.is_unspecified or address.is_multicast:
        parser.error("--bind must be a concrete IPv4 address")
    args.call = args.call.strip().upper()
    if not 1 <= len(args.call) <= 10 or any(c not in "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789/-" for c in args.call):
        parser.error("--call must contain 1..10 callsign characters")
    ports = [args.kiss_port, args.command_port, args.command_port + 1]
    if not (args.remote_audio or args.listen_audio):
        ports += [18110, 18310, 18311]
    if len(set(ports)) != len(ports) or any(p < 1024 or p > 65535 for p in ports):
        parser.error("Choose distinct unprivileged KISS, command and data ports")
    if args.remote_audio or args.listen_audio:
        try:
            peer_host, peer_port = (args.remote_audio or args.listen_audio).rsplit(":", 1)
            if not peer_host or not 1 <= int(peer_port) <= 65535:
                raise ValueError()
        except ValueError:
            parser.error("--remote-audio must be HOST:PORT with a port in 1..65535")
    if args.command == "serve":
        serve(args)
        return 0
    work, state = current(args.output)
    if owned_process(work, state):
        parser.error("Bench already running; use status/stop")
    args.output.mkdir(parents=True, exist_ok=True)
    work = Path(tempfile.mkdtemp(prefix="run-", dir=args.output))
    if "," in str(work):
        parser.error("Pipe audio cannot represent paths containing commas")
    write_json(args.output / "latest.json", {"work": str(work)})
    with (work / "supervisor.log").open("wb") as log:
        command = [sys.executable, str(Path(__file__).resolve()), "serve", "--work", str(work),
                   "--host", str(args.host), "--desktop", str(args.desktop), "--bind", args.bind,
                   "--call", args.call, "--locator", args.locator,
                   "--kiss-port", str(args.kiss_port), "--command-port", str(args.command_port)]
        if args.auto_files:
            command.extend(["--auto-files", args.auto_files])
        if args.remote_audio:
            command.extend(["--remote-audio", args.remote_audio])
        if args.listen_audio:
            command.extend(["--listen-audio", args.listen_audio])
        process = subprocess.Popen(command,
                                   stdin=subprocess.DEVNULL, stdout=log, stderr=subprocess.STDOUT, start_new_session=True)
    deadline = time.monotonic() + 50
    while time.monotonic() < deadline:
        path = work / "status.json"
        state = json.loads(path.read_text()) if path.exists() else None
        if state and state["state"] in ("ready", "error", "stopped"):
            print(json.dumps(state, indent=2))
            return 0 if state["state"] == "ready" else 1
        if process.poll() is not None:
            print(f"Bench exited; inspect {work / 'supervisor.log'}", file=sys.stderr)
            return 1
        time.sleep(0.1)
    print(f"Startup pending; inspect {work / 'supervisor.log'}", file=sys.stderr)
    return 1


if __name__ == "__main__":
    raise SystemExit(main())
