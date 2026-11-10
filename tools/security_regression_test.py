#!/usr/bin/env python3
"""BandKey 安全加固回归测试。

覆盖：配对 PIN 锁定、时间戳容错、设备库损坏恢复、备份文件生成。
依赖：pip install websockets cryptography
"""
import asyncio
import base64
import json
import os
import socket
import subprocess
import tempfile
import time
import urllib.request
import uuid
from pathlib import Path

from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import ec
from cryptography.hazmat.primitives.asymmetric.utils import decode_dss_signature

import websockets


ROOT = Path(__file__).resolve().parent.parent
DOTNET = Path(os.environ["USERPROFILE"]) / ".dotnet" / "dotnet.exe"
SERVICE_DLL = ROOT / "windows" / "BandKeyService" / "bin" / "Release" / "net8.0" / "BandKeyService.dll"
PIN = "123456"


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


def free_port() -> int:
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as sock:
        sock.bind(("127.0.0.1", 0))
        return sock.getsockname()[1]


def wait_health(port: int, process: subprocess.Popen) -> None:
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
    deadline = time.time() + 15
    while time.time() < deadline:
        if process.poll() is not None:
            raise RuntimeError("BandKeyService 提前退出")
        try:
            with opener.open(f"http://127.0.0.1:{port}/health", timeout=1) as response:
                if response.read().decode() == "ok":
                    return
        except Exception:
            time.sleep(0.2)
    raise RuntimeError("BandKeyService 启动超时")


def get_json(port: int, path: str):
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
    with opener.open(f"http://127.0.0.1:{port}{path}", timeout=3) as response:
        return json.loads(response.read().decode())


def start_service(port: int, config: Path, pipe_suffix: str) -> subprocess.Popen:
    return subprocess.Popen(
        [
            str(DOTNET),
            str(SERVICE_DLL),
            "--urls",
            f"http://127.0.0.1:{port}",
            "--config",
            str(config),
            "--pair-pin",
            PIN,
            "--dev",
            "--pipe-suffix",
            pipe_suffix,
        ],
        cwd=str(ROOT),
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
        text=True,
        encoding="utf-8",
        errors="replace",
    )


def stop_service(process: subprocess.Popen | None) -> str:
    if process is None:
        return ""
    if process.poll() is None:
        process.terminate()
    try:
        output, _ = process.communicate(timeout=8)
    except subprocess.TimeoutExpired:
        process.kill()
        output, _ = process.communicate(timeout=5)
    return output


async def send(ws, message: dict) -> dict:
    await ws.send(json.dumps(message))
    return json.loads(await ws.recv())


async def pair(ws, key, pin: str) -> dict:
    challenge = await send(
        ws,
        {
            "type": "pair_request",
            "payload": {
                "deviceName": "安全回归设备",
                "devicePublicKey": public_spki_b64(key),
            },
        },
    )
    assert challenge["type"] == "pair_challenge", challenge
    return await send(
        ws,
        {
            "type": "pair_confirm",
            "payload": {"pairingId": challenge["payload"]["pairingId"], "pin": pin},
        },
    )


async def test_pairing_lock(url: str) -> None:
    key = ec.generate_private_key(ec.SECP256R1())
    async with websockets.connect(url) as ws:
        challenge = await send(
            ws,
            {
                "type": "pair_request",
                "payload": {
                    "deviceName": "锁定测试",
                    "devicePublicKey": public_spki_b64(key),
                },
            },
        )
        pairing_id = challenge["payload"]["pairingId"]
        for attempt in range(1, 6):
            result = await send(
                ws,
                {
                    "type": "pair_confirm",
                    "payload": {"pairingId": pairing_id, "pin": "000000"},
                },
            )
            expected = "pairing_locked" if attempt == 5 else "wrong_pin"
            assert result["payload"]["code"] == expected, (attempt, result)

        result = await send(
            ws,
            {
                "type": "pair_confirm",
                "payload": {"pairingId": pairing_id, "pin": PIN},
            },
        )
        assert result["payload"]["code"] == "unknown_pairing", result


async def test_timestamps(url: str) -> None:
    key = ec.generate_private_key(ec.SECP256R1())
    async with websockets.connect(url) as ws:
        paired = await pair(ws, key, PIN)
        assert paired["type"] == "pair_success", paired
        device_id = paired["payload"]["deviceId"]

        for timestamp in (int(time.time()) - 121, -9223372036854775808):
            nonce = uuid.uuid4().hex
            signature = sign_p1363(
                key, f"unlock:{device_id}:{nonce}:{timestamp}".encode()
            )
            result = await send(
                ws,
                {
                    "type": "unlock",
                    "payload": {
                        "deviceId": device_id,
                        "nonce": nonce,
                        "timestamp": timestamp,
                        "signature": signature,
                        "method": "phone",
                    },
                },
            )
            assert result["payload"]["code"] == "timestamp_out_of_range", result

        timestamp = int(time.time()) - 90
        nonce = uuid.uuid4().hex
        signature = sign_p1363(
            key, f"unlock:{device_id}:{nonce}:{timestamp}".encode()
        )
        result = await send(
            ws,
            {
                "type": "unlock",
                "payload": {
                    "deviceId": device_id,
                    "nonce": nonce,
                    "timestamp": timestamp,
                    "signature": signature,
                    "method": "phone",
                },
            },
        )
        assert result["type"] == "unlock_result" and result["payload"]["ok"], result


async def add_second_device(url: str) -> None:
    key = ec.generate_private_key(ec.SECP256R1())
    async with websockets.connect(url) as ws:
        paired = await pair(ws, key, PIN)
        assert paired["type"] == "pair_success", paired


def main() -> None:
    for key in ("HTTP_PROXY", "HTTPS_PROXY", "ALL_PROXY", "http_proxy", "https_proxy", "all_proxy"):
        os.environ.pop(key, None)
    os.environ["NO_PROXY"] = "127.0.0.1,localhost"

    if not DOTNET.exists() or not SERVICE_DLL.exists():
        raise SystemExit("未找到 dotnet 或 BandKeyService.dll，请先以 Release 构建")

    with tempfile.TemporaryDirectory(prefix="bandkey_security_") as temp_dir:
        temp = Path(temp_dir)
        config = temp / "devices.json"
        port = free_port()
        pipe_suffix = f"_security_{os.getpid()}"
        url = f"ws://127.0.0.1:{port}/bandkey"
        process = None
        try:
            process = start_service(port, config, pipe_suffix)
            wait_health(port, process)

            asyncio.run(test_pairing_lock(url))
            asyncio.run(test_timestamps(url))
            asyncio.run(add_second_device(url))

            devices = get_json(port, "/devices")
            assert len(devices) == 2, devices
            assert (temp / "devices.json.bak").exists(), "第二次保存后未生成备份"

            stop_service(process)
            process = None
            config.write_text("{ broken json", encoding="utf-8")

            process = start_service(port, config, pipe_suffix)
            wait_health(port, process)
            recovered = get_json(port, "/devices")
            assert len(recovered) == 1, recovered
            assert json.loads(config.read_text(encoding="utf-8"))["Devices"], "主文件未修复"
            assert not (temp / "devices.json.tmp").exists(), "残留临时文件"

            print("security_regression: PASS")
        finally:
            output = stop_service(process)
            if output and os.environ.get("BANDKEY_TEST_VERBOSE") == "1":
                print(output)


if __name__ == "__main__":
    main()
