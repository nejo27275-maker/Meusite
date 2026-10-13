#!/usr/bin/env python3
"""Bluetooth Classic RFCOMM gateway: phone -> local LinkBlocks HTTP bridge.

Linux/BlueZ only. Keep this service paired with trusted devices and use a long token.
"""
import argparse
import json
import socket
import urllib.error
import urllib.request


def forward_message(raw: bytes, host: str, port: int) -> str:
    try:
        envelope = json.loads(raw.decode("utf-8").strip())
        if not isinstance(envelope, dict) or not isinstance(envelope.get("token"), str):
            raise ValueError("Mensagem inválida.")
        request = urllib.request.Request(
            f"http://{host}:{port}/api/ingest",
            data=json.dumps(envelope).encode("utf-8"),
            headers={"Content-Type": "application/json"},
            method="POST",
        )
        with urllib.request.urlopen(request, timeout=5) as response:
            return response.read(8192).decode("utf-8")
    except (ValueError, UnicodeDecodeError, urllib.error.URLError, TimeoutError) as exc:
        return json.dumps({"ok": False, "error": str(exc)})


def handle_client(client, address, host, port):
    try:
        client.settimeout(10)
        chunks = bytearray()
        while len(chunks) <= 65536:
            piece = client.recv(4096)
            if not piece:
                break
            chunks.extend(piece)
            if b"\n" in chunks:
                break
        message = bytes(chunks).split(b"\n", 1)[0]
        reply = forward_message(message, host, port)
        client.sendall((reply.replace("\n", " ") + "\n").encode("utf-8"))
        print(f"Bluetooth {address}: mensagem encaminhada")
    except (OSError, ValueError) as exc:
        print(f"Falha no cliente {address}: {exc}")
    finally:
        try:
            client.close()
        except OSError:
            pass


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--host", default="127.0.0.1", help="host HTTP da ponte")
    parser.add_argument("--port", type=int, default=8765, help="porta HTTP da ponte")
    parser.add_argument("--channel", type=int, default=1, help="canal RFCOMM")
    args = parser.parse_args()

    if not hasattr(socket, "AF_BLUETOOTH"):
        raise SystemExit("Este gateway exige Linux com suporte Bluetooth/BlueZ.")
    server = socket.socket(socket.AF_BLUETOOTH, socket.SOCK_STREAM, socket.BTPROTO_RFCOMM)
    server.bind((getattr(socket, "BDADDR_ANY", "00:00:00:00:00:00"), args.channel))
    server.listen(5)
    print(f"Gateway RFCOMM ouvindo no canal {args.channel}")
    print(f"Encaminhando para http://{args.host}:{args.port}/api/ingest")
    print("Pareie o telefone com este computador e mantenha o token protegido.")
    try:
        while True:
            client, address = server.accept()
            handle_client(client, address, args.host, args.port)
    except KeyboardInterrupt:
        print("\nGateway encerrado.")
    finally:
        server.close()


if __name__ == "__main__":
    main()
