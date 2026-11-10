#!/usr/bin/env python3
"""模拟 Android 手机端的配对与解锁流程，用于端到端验证 BandKeyService。

依赖: pip install websockets cryptography
用法: python simulate_phone.py --url ws://127.0.0.1:23234/bandkey [--replay]
"""
import argparse
import asyncio
import base64
import json
import os
import time
import uuid

from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import ec
from cryptography.hazmat.primitives.asymmetric.utils import decode_dss_signature

import websockets


def load_or_create_key(path: str):
    if path and os.path.exists(path):
        with open(path, "rb") as f:
            return serialization.load_pem_private_key(f.read(), password=None)

    key = ec.generate_private_key(ec.SECP256R1())
    if path:
        pem = key.private_bytes(
            serialization.Encoding.PEM,
            serialization.PrivateFormat.PKCS8,
            serialization.NoEncryption(),
        )
        with open(path, "wb") as f:
            f.write(pem)
    return key


def public_spki_b64(key) -> str:
    pub = key.public_key().public_bytes(
        serialization.Encoding.DER,
        serialization.PublicFormat.SubjectPublicKeyInfo,
    )
    return base64.b64encode(pub).decode()


def sign_p1363(key, data: bytes) -> str:
    der = key.sign(data, ec.ECDSA(hashes.SHA256()))
    r, s = decode_dss_signature(der)
    return base64.b64encode(r.to_bytes(32, "big") + s.to_bytes(32, "big")).decode()


async def main() -> None:
    # 本机可能配置了系统代理，本地回环连接应禁用代理
    for _var in ("HTTP_PROXY", "HTTPS_PROXY", "ALL_PROXY", "http_proxy", "https_proxy", "all_proxy"):
        os.environ.pop(_var, None)
    os.environ["NO_PROXY"] = "127.0.0.1,localhost"

    ap = argparse.ArgumentParser()
    ap.add_argument("--url", default="ws://127.0.0.1:23234/bandkey")
    ap.add_argument("--keyfile", default="phone_key.pem")
    ap.add_argument("--pin", default=None, help="配对 PIN（显示在 PC 端，由用户输入）")
    ap.add_argument("--replay", action="store_true", help="重复发送同一 nonce，验证防重放")
    args = ap.parse_args()

    key = load_or_create_key(args.keyfile)

    async with websockets.connect(args.url) as ws:
        await ws.send(json.dumps({
            "type": "pair_request",
            "payload": {"deviceName": "模拟手机", "devicePublicKey": public_spki_b64(key)},
        }))
        challenge = json.loads(await ws.recv())
        print("pair_challenge:", json.dumps(challenge, ensure_ascii=False))
        assert challenge["type"] == "pair_challenge"
        pairing_id = challenge["payload"]["pairingId"]
        pin = args.pin
        if not pin:
            raise SystemExit("pair_challenge 不再回传 PIN；请通过 --pin 指定（PIN 显示在 PC 端）")

        await ws.send(json.dumps({
            "type": "pair_confirm",
            "payload": {"pairingId": pairing_id, "pin": pin},
        }))
        success = json.loads(await ws.recv())
        print("pair_success:", json.dumps(success, ensure_ascii=False))
        assert success["type"] == "pair_success"
        device_id = success["payload"]["deviceId"]

        nonce = uuid.uuid4().hex
        timestamp = int(time.time())
        data = f"unlock:{device_id}:{nonce}:{timestamp}".encode()
        signature = sign_p1363(key, data)

        await ws.send(json.dumps({
            "type": "unlock",
            "payload": {
                "deviceId": device_id,
                "nonce": nonce,
                "timestamp": timestamp,
                "signature": signature,
            },
        }))
        result = json.loads(await ws.recv())
        print("unlock_result:", json.dumps(result, ensure_ascii=False))

        if args.replay:
            await ws.send(json.dumps({
                "type": "unlock",
                "payload": {
                    "deviceId": device_id,
                    "nonce": nonce,
                    "timestamp": timestamp,
                    "signature": signature,
                },
            }))
            replayed = json.loads(await ws.recv())
            print("replay_result:", json.dumps(replayed, ensure_ascii=False))


if __name__ == "__main__":
    asyncio.run(main())
