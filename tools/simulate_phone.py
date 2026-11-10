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
    ap.add_argument("--nfc", action="store_true", help="模拟手机本地 NFC 验签后的解锁请求")
    ap.add_argument(
        "--expect-state",
        choices=("accepted", "submitted", "succeeded", "failed"),
        help="断言最终解锁状态，用于 CP 管道回归",
    )
    ap.add_argument(
        "--status-timeout",
        type=float,
        default=3.0,
        help="等待 unlock_status 最终状态的秒数",
    )
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

        await ws.send(json.dumps({
            "type": "status",
            "payload": {"deviceId": device_id},
        }))
        status = json.loads(await ws.recv())
        print("status_result:", json.dumps(status, ensure_ascii=False))
        assert status["type"] == "status_result"
        assert status["payload"]["protocolVersion"] >= 2
        assert status["payload"]["paired"] is True
        assert status["payload"]["online"] is True

        nonce = uuid.uuid4().hex
        timestamp = int(time.time())
        data = f"unlock:{device_id}:{nonce}:{timestamp}".encode()
        signature = sign_p1363(key, data)

        payload = {
            "deviceId": device_id,
            "nonce": nonce,
            "timestamp": timestamp,
            "signature": signature,
            "method": "nfc" if args.nfc else "phone",
        }
        if args.nfc:
            payload["keyHint"] = "BK-NFC \u00b7\u00b7\u00b7 8F2A"

        await ws.send(json.dumps({
            "type": "unlock",
            "payload": payload,
        }))
        result = json.loads(await ws.recv())
        print("unlock_result:", json.dumps(result, ensure_ascii=False))
        accepted = result.get("type") == "unlock_result" and result.get("payload", {}).get("accepted")
        final_state = None
        if accepted:
            request_id = result["payload"]["requestId"]
            assert request_id > 0

            deadline = time.time() + args.status_timeout
            while True:
                await ws.send(json.dumps({
                    "type": "unlock_status",
                    "payload": {"requestId": request_id},
                }))
                unlock_status = json.loads(await ws.recv())
                state = unlock_status.get("payload", {}).get("state")
                final_state = state
                if state in ("succeeded", "failed") or time.time() >= deadline:
                    break
                time.sleep(0.1)
            print("unlock_status:", json.dumps(unlock_status, ensure_ascii=False))
            assert unlock_status["type"] == "unlock_status_result"
            assert unlock_status["payload"]["state"] in ("accepted", "submitted", "succeeded", "failed")

        if args.expect_state and final_state != args.expect_state:
            raise SystemExit(f"unexpected unlock state: {final_state}, expected {args.expect_state}")

        if args.replay and accepted:
            await ws.send(json.dumps({
                "type": "unlock",
                "payload": payload,
            }))
            replayed = json.loads(await ws.recv())
            print("replay_result:", json.dumps(replayed, ensure_ascii=False))


if __name__ == "__main__":
    asyncio.run(main())
