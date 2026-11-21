#!/usr/bin/env python3
"""One raw, bidirectional TCP audio connection over two existing POSIX FIFOs.

--input is modem output; --output is modem input. Bytes are copied unchanged.
No framing, resampling, retry, or dropped-sample concealment is performed.
"""
import argparse
import json
import os
from pathlib import Path
import select
import signal
import socket
import stat
import time


def relay(source, sink, peer, status, stopping):
    limit = 32768  # at most one second of float32/8 kHz audio per user-space queue
    tx, rx = bytearray(), bytearray()
    counters = {"state": "connected", "to_network_bytes": 0, "from_network_bytes": 0,
                "to_modem_bytes": 0, "from_modem_bytes": 0,
                "tx_queue_high_water_bytes": 0, "rx_queue_high_water_bytes": 0}
    last_report = 0

    def report():
        temporary = status.with_suffix(".tmp")
        temporary.write_text(json.dumps(counters, indent=2) + "\n")
        temporary.replace(status)

    peer.setblocking(False)
    report()
    try:
        while not stopping():
            readers = ([peer] if len(rx) < limit else []) + ([source] if len(tx) < limit else [])
            writers = ([peer] if tx else []) + ([sink] if rx else [])
            readable, writable, _ = select.select(readers, writers, [], 0.2)
            if peer in readable:
                data = peer.recv(min(8192, limit - len(rx)))
                if not data:
                    raise ConnectionError("Remote audio socket closed; restart both bridge endpoints before retrying")
                rx.extend(data)
                counters["from_network_bytes"] += len(data)
            if source in readable:
                data = os.read(source, min(8192, limit - len(tx)))
                if not data:
                    raise ConnectionError("Modem output FIFO closed")
                tx.extend(data)
                counters["from_modem_bytes"] += len(data)
            if peer in writable:
                size = peer.send(tx)
                del tx[:size]
                counters["to_network_bytes"] += size
            if sink in writable:
                size = os.write(sink, rx)
                del rx[:size]
                counters["to_modem_bytes"] += size
            counters["tx_queue_high_water_bytes"] = max(counters["tx_queue_high_water_bytes"], len(tx))
            counters["rx_queue_high_water_bytes"] = max(counters["rx_queue_high_water_bytes"], len(rx))
            if time.monotonic() - last_report >= 1:
                report()
                last_report = time.monotonic()
        counters["state"] = "stopped"
    except Exception as error:
        counters["state"] = "error"
        counters["error"] = str(error)
        raise
    finally:
        counters["pending_to_network_bytes"] = len(tx)
        counters["pending_to_modem_bytes"] = len(rx)
        report()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--input", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    endpoint = parser.add_mutually_exclusive_group(required=True)
    endpoint.add_argument("--peer", help="Connect to IPv4-or-hostname:port")
    endpoint.add_argument("--listen", help="Accept one connection on local IPv4:port")
    parser.add_argument("--status", type=Path, required=True)
    args = parser.parse_args()
    host, port = (args.peer or args.listen).rsplit(":", 1)
    for path in (args.input, args.output):
        if not stat.S_ISFIFO(path.stat().st_mode):
            parser.error(f"Not an existing FIFO: {path}")
    if args.input.resolve() == args.output.resolve():
        parser.error("Use distinct input and output FIFOs")
    stopping = False

    def stop(_signal, _frame):
        nonlocal stopping
        stopping = True

    signal.signal(signal.SIGTERM, stop)
    signal.signal(signal.SIGINT, stop)
    # Holding each FIFO's second end prevents startup open-order deadlocks.
    # The supervisor separately watches the modem; these are never auto-retried.
    source = os.open(args.input, os.O_RDWR | os.O_NONBLOCK)
    try:
        sink = os.open(args.output, os.O_RDWR | os.O_NONBLOCK)
        try:
            peer = None
            if args.listen:
                with socket.socket() as server:
                    server.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
                    server.bind((host, int(port)))
                    server.listen(1)
                    server.settimeout(0.2)
                    args.status.write_text(json.dumps({"state": "waiting", "listen": args.listen}) + "\n")
                    print(f"Audio TCP listening on {host}:{port}", flush=True)
                    while not stopping:
                        try:
                            peer, _ = server.accept()
                            break
                        except TimeoutError:
                            pass
            else:
                peer = socket.create_connection((host, int(port)), timeout=15)
            if peer is None:
                return
            with peer:
                peer.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
                peer.setsockopt(socket.SOL_SOCKET, socket.SO_SNDBUF, 16384)
                peer.setsockopt(socket.SOL_SOCKET, socket.SO_RCVBUF, 16384)
                print(f"Audio TCP connected ({host}:{port})", flush=True)
                relay(source, sink, peer, args.status, lambda: stopping)
        finally:
            os.close(sink)
    finally:
        os.close(source)


if __name__ == "__main__":
    main()
