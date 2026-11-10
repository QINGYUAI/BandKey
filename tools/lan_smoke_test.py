#!/usr/bin/env python3
"""局域网通道冒烟测试：UDP 发现 + TCP 配对/解锁。"""
import argparse
import json
import os
import socket
import sys
import time
import uuid

sys.path.insert(0, os.path.dirname(__file__))
from simulate_phone import load_or_create_key, public_spki_b64, sign_p1363  # noqa: E402


def send_line(stream, obj):
    stream.write((json.dumps(obj, ensure_ascii=False) + "\n").encode("utf-8"))
    stream.flush()


def read_line(stream):
    line = stream.readline()
    if not line:
        raise RuntimeError("connection closed")
    return json.loads(line.decode("utf-8"))


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--pin", default="123456")
    parser.add_argument("--keyfile", default=os.path.join(os.environ.get("TEMP", "."), "bandkey_lan_key.pem"))
    args = parser.parse_args()

    udp = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    udp.settimeout(3)
    udp.sendto(b"BANDKEY_DISCOVER", (args.host, 47322))
    discovery, _ = udp.recvfrom(4096)
    print("discover:", discovery.decode("utf-8"))
    udp.close()

    key = load_or_create_key(args.keyfile)
    sock = socket.create_connection((args.host, 47321), 5)
    sock.settimeout(10)
    stream = sock.makefile("rwb")
    try:
        send_line(stream, {
            "type": "pair_request",
            "payload": {
                "deviceName": "LAN-Test",
                "devicePublicKey": public_spki_b64(key),
            },
        })
        challenge = read_line(stream)
        print("pair_challenge:", challenge)
        pairing_id = challenge["payload"]["pairingId"]

        send_line(stream, {
            "type": "pair_confirm",
            "payload": {"pairingId": pairing_id, "pin": args.pin},
        })
        success = read_line(stream)
        print("pair_success:", success)
        device_id = success["payload"]["deviceId"]

        nonce = uuid.uuid4().hex
        timestamp = int(time.time())
        data = f"unlock:{device_id}:{nonce}:{timestamp}".encode("utf-8")
        send_line(stream, {
            "type": "unlock",
            "payload": {
                "deviceId": device_id,
                "nonce": nonce,
                "timestamp": timestamp,
                "signature": sign_p1363(key, data),
            },
        })
        result = read_line(stream)
        print("unlock_result:", result)
        if not result.get("payload", {}).get("ok"):
            raise SystemExit(1)
    finally:
        stream.close()
        sock.close()


if __name__ == "__main__":
    main()
