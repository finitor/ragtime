#!/usr/bin/env python3
"""Local correctness check: fragmented binary streams, backpressure and EOF."""
import json
import os
from pathlib import Path
import select
import socket
import subprocess
import sys
import tempfile
import time
import unittest


class AudioBridgeTest(unittest.TestCase):
    def test_exact_bidirectional_bytes_and_remote_disconnect(self):
        for listening in (False, True):
            with self.subTest(listening=listening):
                self.transfer(listening)

    def transfer(self, listening):
        with tempfile.TemporaryDirectory() as directory:
            work = Path(directory)
            incoming, outgoing = work / "modem-out", work / "modem-in"
            os.mkfifo(incoming)
            os.mkfifo(outgoing)
            modem_out = os.open(incoming, os.O_RDWR | os.O_NONBLOCK)
            modem_in = os.open(outgoing, os.O_RDWR | os.O_NONBLOCK)
            process = None
            remote = None
            try:
                with socket.socket() as server, (work / "bridge.log").open("wb") as log:
                    server.bind(("127.0.0.1", 0))
                    server.listen(1)
                    server.settimeout(5)
                    port = server.getsockname()[1]
                    if listening:
                        server.close()
                    process = subprocess.Popen([
                        sys.executable, str(Path(__file__).with_name("tcp_audio_bridge.py")),
                        "--input", str(incoming), "--output", str(outgoing),
                        "--listen" if listening else "--peer", f"127.0.0.1:{port}",
                        "--status", str(work / "status.json")], stdout=log, stderr=log)
                    if listening:
                        deadline = time.monotonic() + 5
                        while remote is None:
                            try:
                                remote = socket.create_connection(("127.0.0.1", port), timeout=0.2)
                            except ConnectionRefusedError:
                                self.assertLess(time.monotonic(), deadline)
                                time.sleep(0.02)
                    else:
                        remote, _ = server.accept()
                    remote.setblocking(False)
                    tx = bytes(range(256)) * 513
                    rx = bytes(reversed(range(256))) * 389
                    sent_tx = sent_rx = 0
                    got_tx, got_rx = bytearray(), bytearray()
                    deadline = time.monotonic() + 10
                    # Odd fragment sizes cross float/sample and network boundaries.
                    while len(got_tx) < len(tx) or len(got_rx) < len(rx):
                        self.assertIsNone(process.poll(), (work / "bridge.log").read_text())
                        self.assertLess(time.monotonic(), deadline, "Bridge transfer stalled")
                        readers, writers, _ = select.select(
                            [remote, modem_in],
                            ([modem_out] if sent_tx < len(tx) else []) +
                            ([remote] if sent_rx < len(rx) else []), [], 0.05)
                        if modem_out in writers:
                            sent_tx += os.write(modem_out, tx[sent_tx:sent_tx + 1021])
                        if remote in writers:
                            sent_rx += remote.send(rx[sent_rx:sent_rx + 769])
                        if remote in readers:
                            data = remote.recv(253)
                            self.assertTrue(data)
                            got_tx.extend(data)
                        if modem_in in readers:
                            got_rx.extend(os.read(modem_in, 509))
                    self.assertEqual(tx, bytes(got_tx))
                    self.assertEqual(rx, bytes(got_rx))
                    remote.close()
                    remote = None
                    self.assertNotEqual(0, process.wait(timeout=5))
                    status = json.loads((work / "status.json").read_text())
                    self.assertEqual("error", status["state"])
                    self.assertIn("Remote audio socket closed", status["error"])
                    self.assertEqual(len(tx), status["to_network_bytes"])
                    self.assertEqual(len(rx), status["to_modem_bytes"])
                    self.assertLessEqual(status["tx_queue_high_water_bytes"], 32768)
                    self.assertLessEqual(status["rx_queue_high_water_bytes"], 32768)
            finally:
                if remote:
                    remote.close()
                if process and process.poll() is None:
                    process.terminate()
                    process.wait(timeout=5)
                os.close(modem_out)
                os.close(modem_in)


if __name__ == "__main__":
    unittest.main()
