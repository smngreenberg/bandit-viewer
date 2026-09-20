#!/usr/bin/env python3
"""
bandit-test.py - probe a TomTom Bandit action camera from a laptop.

Protocol details come from TomTom's BanditCameraKit-Android source
(sdk/.../api/CameraApi.java, api/v2/RetrofitCameraApiV2.java,
viewfinder/ImageStreamParser.java):

  * REST base URL ........ http://192.168.1.101/api   (API version 2)
  * GET  /api/version
  * GET  /api/2/status
  * POST /api/2/viewfinder  {"viewfinder_active": true, "viewfinder_streaming_port": 4001}
  * Camera then sends 768x432 JPEG frames over UDP to the requesting
    client on that port, split into packets:
        header (7 bytes, big-endian): sync 0x55AA | msg u8 | packet# u16 | payload_len u16
        msg 0 = frame start; payload = image_length u32, pts float32
        msg 1 = frame data;  payload = next chunk of JPEG bytes

Requirements: Python 3.8+, standard library only.
Optional: `pip install opencv-python` and use --show for a live window.

Usage:
    1. Turn on Wi-Fi on the Bandit, join its Wi-Fi network from this computer.
       (Disconnect Ethernet / other Wi-Fi, so 192.168.1.101 is reached via the camera.)
    2. python bandit_test.py
       -> saves frames/latest.jpg continuously, plus every Nth frame.
    3. python bandit_test.py --show     (live window, press q to quit)
"""

import argparse
import json
import os
import socket
import struct
import sys
import time
import urllib.error
import urllib.request

SYNC = 0x55AA
HEADER_LEN = 7
MSG_START = 0
MSG_DATA = 1


# --------------------------------------------------------------------------
# REST helpers
# --------------------------------------------------------------------------
def http(method, url, body=None, timeout=5.0):
    data = None
    headers = {"Accept": "application/json"}
    if body is not None:
        data = json.dumps(body).encode("utf-8")
        headers["Content-Type"] = "application/json"
    req = urllib.request.Request(url, data=data, method=method, headers=headers)
    with urllib.request.urlopen(req, timeout=timeout) as resp:
        raw = resp.read()
        status = resp.status
    parsed = None
    if raw:
        try:
            parsed = json.loads(raw.decode("utf-8"))
        except Exception:
            parsed = raw[:200]
    return status, parsed


def step(label):
    print(f"\n=== {label}")


def set_viewfinder(base, active, port):
    body = {"viewfinder_active": bool(active),
            "viewfinder_streaming_port": port if active else -1}
    return http("POST", f"{base}/2/viewfinder", body)


# --------------------------------------------------------------------------
# Frame reassembly (mirrors ImageStreamParser.java)
# --------------------------------------------------------------------------
class FrameAssembler:
    def __init__(self):
        self.waiting = True
        self.expected_len = 0
        self.buf = bytearray()
        self.next_packet = 0
        self.pts = 0.0
        self.dropped_frames = 0
        self.bad_packets = 0

    def feed(self, pkt):
        """Feed one UDP datagram. Returns (pts, jpeg_bytes) when a frame completes."""
        if len(pkt) < HEADER_LEN:
            self.bad_packets += 1
            return None
        sync, msg, pnum, plen = struct.unpack(">HBHH", pkt[:HEADER_LEN])
        if sync != SYNC or len(pkt) != plen + HEADER_LEN or msg not in (MSG_START, MSG_DATA):
            self.bad_packets += 1
            return None
        payload = pkt[HEADER_LEN:]

        if msg == MSG_START:
            if len(payload) < 8:
                self.bad_packets += 1
                return None
            self.expected_len, self.pts = struct.unpack(">If", payload[:8])
            self.buf = bytearray()
            self.next_packet = (pnum + 1) & 0xFFFF
            self.waiting = False
            return None

        # MSG_DATA
        if self.waiting:
            return None
        if pnum != self.next_packet:
            # lost or reordered packet: abandon this frame, wait for the next start
            self.dropped_frames += 1
            self.waiting = True
            return None
        self.buf += payload
        self.next_packet = (self.next_packet + 1) & 0xFFFF
        if len(self.buf) >= self.expected_len:
            self.waiting = True
            frame = bytes(self.buf[: self.expected_len])
            return self.pts, frame
        return None


def looks_like_jpeg(b):
    return len(b) > 4 and b[:2] == b"\xff\xd8" and b[-2:] == b"\xff\xd9"


# --------------------------------------------------------------------------
# Main
# --------------------------------------------------------------------------
def main():
    ap = argparse.ArgumentParser(description="Test a TomTom Bandit camera.")
    ap.add_argument("--ip", default="192.168.1.101", help="camera IP (default 192.168.1.101)")
    ap.add_argument("--http-port", type=int, default=80)
    ap.add_argument("--udp-port", type=int, default=4001, help="viewfinder UDP port (default 4001)")
    ap.add_argument("--seconds", type=float, default=15.0, help="how long to stream (0 = until Ctrl-C)")
    ap.add_argument("--outdir", default="frames")
    ap.add_argument("--save-every", type=int, default=30, help="also keep every Nth frame as its own file (0 = never)")
    ap.add_argument("--show", action="store_true", help="live window via OpenCV (if installed)")
    ap.add_argument("--status-only", action="store_true", help="only test REST (no streaming)")
    args = ap.parse_args()

    port_part = "" if args.http_port == 80 else f":{args.http_port}"
    base = f"http://{args.ip}{port_part}/api"

    # ---- 1. version ------------------------------------------------------
    step(f"1. GET {base}/version")
    try:
        status, ver = http("GET", f"{base}/version")
        print(f"HTTP {status}: {ver}")
        if isinstance(ver, dict) and str(ver.get("version")) != "2":
            print("WARNING: camera reports API version other than 2; this script speaks v2 only.")
            print("         (kit requires firmware 1.57.500 or newer)")
    except Exception as e:
        print(f"FAILED: {e}")
        print("\nCannot reach the camera. Check that:")
        print("  - the Bandit's Wi-Fi is switched on (push up on the four-way pad)")
        print("  - this computer is joined to the camera's Wi-Fi network")
        print("  - no other network (Ethernet, second Wi-Fi) is competing for 192.168.1.x")
        sys.exit(1)

    # ---- 2. status -------------------------------------------------------
    step(f"2. GET {base}/2/status")
    try:
        status, st = http("GET", f"{base}/2/status")
        print(f"HTTP {status}:")
        print(json.dumps(st, indent=2) if isinstance(st, dict) else st)
    except Exception as e:
        print(f"FAILED: {e}")
        sys.exit(1)

    if args.status_only:
        print("\nREST OK (status-only mode).")
        return

    # ---- 3. open UDP socket BEFORE asking the camera to stream -----------
    step(f"3. Listening on UDP port {args.udp_port}")
    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    sock.setsockopt(socket.SOL_SOCKET, socket.SO_RCVBUF, 4 * 1024 * 1024)
    try:
        sock.bind(("0.0.0.0", args.udp_port))
    except OSError as e:
        print(f"FAILED to bind UDP {args.udp_port}: {e}  (is another program using it?)")
        sys.exit(1)
    sock.settimeout(1.0)
    print("Bound. (If your OS firewall prompts, allow Python on private networks.)")

    # ---- 4. start viewfinder --------------------------------------------
    step(f"4. POST {base}/2/viewfinder (start)")
    already_active = isinstance(st, dict) and st.get("viewfinder_active")
    if already_active:
        # kit README: only one viewfinder stream may be active; stop first.
        try:
            print("Viewfinder already active - stopping it first...")
            set_viewfinder(base, False, args.udp_port)
            time.sleep(0.5)
        except Exception as e:
            print(f"(stop before start failed, continuing: {e})")
    try:
        status, resp = set_viewfinder(base, True, args.udp_port)
        print(f"HTTP {status}: {resp if resp is not None else '(empty body - normal)'}")
    except urllib.error.HTTPError as e:
        print(f"FAILED: HTTP {e.code} {e.reason} - body: {e.read()[:200]!r}")
        sock.close()
        sys.exit(1)
    except Exception as e:
        print(f"FAILED: {e}")
        sock.close()
        sys.exit(1)

    # ---- 5. receive frames ----------------------------------------------
    step("5. Receiving frames" + ("  (press q in the window / Ctrl-C to stop)" if args.show else "  (Ctrl-C to stop)"))
    os.makedirs(args.outdir, exist_ok=True)
    cv2 = np = None
    if args.show:
        try:
            import cv2  # type: ignore
            import numpy as np  # type: ignore
        except ImportError:
            print("--show needs OpenCV: pip install opencv-python   (continuing without window)")
            args.show = False

    asm = FrameAssembler()
    t0 = time.time()
    last_report = t0
    frames = 0
    frames_since_report = 0
    packets = 0
    bad_jpeg = 0
    first_frame_at = None
    last_sender = None
    try:
        while True:
            now = time.time()
            if args.seconds > 0 and now - t0 >= args.seconds:
                break
            try:
                pkt, addr = sock.recvfrom(2048)
            except socket.timeout:
                if first_frame_at is None and now - t0 > 5:
                    print(f"  ...{now - t0:.0f}s and no packets yet. See troubleshooting notes below.")
                continue
            packets += 1
            last_sender = addr
            result = asm.feed(pkt)
            if result is not None:
                pts, jpeg = result
                if not looks_like_jpeg(jpeg):
                    bad_jpeg += 1
                    continue
                frames += 1
                frames_since_report += 1
                if first_frame_at is None:
                    first_frame_at = time.time() - t0
                    print(f"  first frame after {first_frame_at:.2f}s, {len(jpeg)} bytes, from {addr[0]}:{addr[1]}")
                with open(os.path.join(args.outdir, "latest.jpg"), "wb") as f:
                    f.write(jpeg)
                if args.save_every and frames % args.save_every == 1:
                    with open(os.path.join(args.outdir, f"frame_{frames:05d}.jpg"), "wb") as f:
                        f.write(jpeg)
                if args.show:
                    img = cv2.imdecode(np.frombuffer(jpeg, np.uint8), cv2.IMREAD_COLOR)
                    if img is not None:
                        cv2.imshow("Bandit viewfinder", img)
                        if cv2.waitKey(1) & 0xFF == ord("q"):
                            break
            if now - last_report >= 1.0:
                fps = frames_since_report / (now - last_report)
                print(f"  {fps:5.1f} fps | frames {frames} | packets {packets} | "
                      f"dropped {asm.dropped_frames} | bad pkts {asm.bad_packets} | bad jpeg {bad_jpeg}")
                last_report = now
                frames_since_report = 0
    except KeyboardInterrupt:
        print("\n(interrupted)")
    finally:
        # ---- 6. stop viewfinder -----------------------------------------
        step("6. POST viewfinder (stop)")
        try:
            status, _ = set_viewfinder(base, False, args.udp_port)
            print(f"HTTP {status}")
        except Exception as e:
            print(f"stop failed (harmless, camera resets stream on disconnect): {e}")
        sock.close()
        if args.show:
            cv2.destroyAllWindows()

    # ---- summary ---------------------------------------------------------
    step("Summary")
    elapsed = time.time() - t0
    print(f"packets received : {packets}")
    print(f"frames assembled : {frames}  (~{frames / max(elapsed, 0.001):.1f} fps average)")
    print(f"dropped frames   : {asm.dropped_frames}   bad packets: {asm.bad_packets}   bad JPEGs: {bad_jpeg}")
    if frames:
        print(f"OK - open {os.path.join(args.outdir, 'latest.jpg')} to see the camera's view.")
        print("The protocol works from this machine; the Android app is worth building.")
    elif packets:
        print("Packets arrived but no complete JPEG frames were assembled.")
        print("Save the output above and share it - the packet format may differ on your firmware.")
    else:
        print("No UDP packets arrived. Likely causes:")
        print("  - OS firewall blocking inbound UDP (allow Python on private networks, or open UDP 4001)")
        print("  - laptop sending the REST call over a different interface than the Wi-Fi to the camera")
        print("  - another program already bound to UDP 4001 (bind would have failed above)")
        print("  - camera firmware older than 1.57.500")


if __name__ == "__main__":
    main()
