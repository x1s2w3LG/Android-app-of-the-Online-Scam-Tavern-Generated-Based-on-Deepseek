"""用微软 Edge「大声朗读」的神经语音合成一小段音频（无需 API key）。

为什么自己写：这台机器上 Google 的 TTS 端点连不通，本机 SAPI 只有中英文语音，
而 Edge 的朗读服务（speech.platform.bing.com:443）是通的，且有 ja-JP 神经语音。
它走 WebSocket，所以这里用标准库手写了一个最小客户端。

用法: python edge_tts.py <输出文件> <文本> [语音名]
"""
import base64
import hashlib
import json
import os
import random
import ssl
import struct
import sys
import time
import urllib.parse
import socket

HOST = "speech.platform.bing.com"
TRUSTED_CLIENT_TOKEN = "6A5AA1D4EAFF4E9FB37E23D68491D6F4"
PATH = ("/consumer/speech/synthesize/readaloud/edge/v1"
        "?TrustedClientToken=" + TRUSTED_CLIENT_TOKEN)
UA = ("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
      "(KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36 Edg/130.0.0.0")
ORIGIN = "chrome-extension://jdiccldimpdaibmpdkjnbmckianbfold"

# 微软 2024 年起要求带 Sec-MS-GEC 签名，否则握手直接 403
WIN_EPOCH_OFFSET = 11644473600


def sec_ms_gec():
    ticks = time.time() + WIN_EPOCH_OFFSET
    ticks -= ticks % 300                       # 向下取整到 5 分钟
    ticks *= 1e9 / 100                         # 转成 100ns 单位
    raw = ("%.0f%s" % (ticks, TRUSTED_CLIENT_TOKEN)).encode("ascii")
    return hashlib.sha256(raw).hexdigest().upper()


# ------------------------------------------------------------------ WebSocket
class WS:
    def __init__(self, sock):
        self.sock = sock
        self.buf = b""

    def send_text(self, text):
        payload = text.encode("utf-8")
        header = bytearray()
        header.append(0x81)                       # FIN + text
        mask_bit = 0x80
        n = len(payload)
        if n < 126:
            header.append(mask_bit | n)
        elif n < 65536:
            header.append(mask_bit | 126)
            header += struct.pack(">H", n)
        else:
            header.append(mask_bit | 127)
            header += struct.pack(">Q", n)
        mask = bytes(random.getrandbits(8) for _ in range(4))
        header += mask
        masked = bytes(b ^ mask[i % 4] for i, b in enumerate(payload))
        self.sock.sendall(bytes(header) + masked)

    def _read_n(self, n):
        while len(self.buf) < n:
            chunk = self.sock.recv(65536)
            if not chunk:
                raise IOError("连接被关闭")
            self.buf += chunk
        out, self.buf = self.buf[:n], self.buf[n:]
        return out

    def recv(self):
        """返回 (opcode, payload)。"""
        b = self._read_n(2)
        opcode = b[0] & 0x0F
        masked = (b[1] & 0x80) != 0
        length = b[1] & 0x7F
        if length == 126:
            length = struct.unpack(">H", self._read_n(2))[0]
        elif length == 127:
            length = struct.unpack(">Q", self._read_n(8))[0]
        mask = self._read_n(4) if masked else None
        payload = self._read_n(length) if length else b""
        if mask:
            payload = bytes(x ^ mask[i % 4] for i, x in enumerate(payload))
        if opcode == 0x9:                          # ping -> pong
            self._send_control(0xA, payload)
            return self.recv()
        return opcode, payload

    def _send_control(self, opcode, payload):
        header = bytearray([0x80 | opcode])
        n = len(payload)
        header.append(0x80 | n)
        mask = bytes(random.getrandbits(8) for _ in range(4))
        header += mask
        masked = bytes(b ^ mask[i % 4] for i, b in enumerate(payload))
        self.sock.sendall(bytes(header) + masked)


def connect():
    ctx = ssl.create_default_context()
    raw = socket.create_connection((HOST, 443), timeout=20)
    sock = ctx.wrap_socket(raw, server_hostname=HOST)
    key = base64.b64encode(bytes(random.getrandbits(8) for _ in range(16))).decode()
    req = (
        "GET %s HTTP/1.1\r\n"
        "Host: %s\r\n"
        "Upgrade: websocket\r\n"
        "Connection: Upgrade\r\n"
        "Sec-WebSocket-Key: %s\r\n"
        "Sec-WebSocket-Version: 13\r\n"
        "Sec-MS-GEC: %s\r\n"
        "Sec-MS-GEC-Version: 1-130.0.2849.68\r\n"
        "Pragma: no-cache\r\n"
        "Cache-Control: no-cache\r\n"
        "Origin: %s\r\n"
        "User-Agent: %s\r\n"
        "Accept-Encoding: gzip, deflate, br\r\n"
        "Accept-Language: zh-CN,zh;q=0.9,en;q=0.8\r\n"
        "\r\n"
    ) % (PATH, HOST, key, sec_ms_gec(), ORIGIN, UA)
    sock.sendall(req.encode())

    head = b""
    while b"\r\n\r\n" not in head:
        chunk = sock.recv(4096)
        if not chunk:
            raise IOError("握手时连接关闭")
        head += chunk
    status = head.split(b"\r\n", 1)[0].decode("latin-1")
    if "101" not in status:
        raise IOError("握手失败: %s" % status)
    ws = WS(sock)
    ws.buf = head.split(b"\r\n\r\n", 1)[1]
    return ws


def ts():
    return time.strftime("%a %b %d %Y %H:%M:%S GMT+0000 (Coordinated Universal Time)",
                         time.gmtime())


def synth(text, voice, out_path, rate="+18%", pitch="+2Hz"):
    ws = connect()
    req_id = "".join(random.choice("0123456789abcdef") for _ in range(32))

    config = {
        "context": {
            "synthesis": {
                "audio": {
                    "metadataoptions": {
                        "sentenceBoundaryEnabled": "false",
                        "wordBoundaryEnabled": "false",
                    },
                    "outputFormat": "audio-24khz-48kbitrate-mono-mp3",
                }
            }
        }
    }
    ws.send_text(
        "X-Timestamp:%s\r\nContent-Type:application/json; charset=utf-8\r\n"
        "Path:speech.config\r\n\r\n%s" % (ts(), json.dumps(config))
    )

    ssml = (
        "<speak version='1.0' xmlns='http://www.w3.org/2001/10/synthesis' xml:lang='ja-JP'>"
        "<voice name='%s'><prosody pitch='%s' rate='%s' volume='+0%%'>%s</prosody></voice>"
        "</speak>" % (voice, pitch, rate, text)
    )
    ws.send_text(
        "X-RequestId:%s\r\nContent-Type:application/ssml+xml\r\n"
        "X-Timestamp:%sZ\r\nPath:ssml\r\n\r\n%s" % (req_id, ts(), ssml)
    )

    audio = bytearray()
    while True:
        opcode, payload = ws.recv()
        if opcode == 0x8:
            break
        if opcode == 0x2:                      # 二进制：2 字节头长度 + 头 + 音频
            if len(payload) < 2:
                continue
            head_len = struct.unpack(">H", payload[:2])[0]
            headers = payload[2:2 + head_len].decode("utf-8", "ignore")
            data = payload[2 + head_len:]
            if "Path:audio" in headers and data:
                audio += data
        elif opcode == 0x1:                    # 文本：turn.end 表示结束
            if "Path:turn.end" in payload.decode("utf-8", "ignore"):
                break

    if not audio:
        raise IOError("没有收到音频数据")
    with open(out_path, "wb") as f:
        f.write(bytes(audio))
    print("[ok] %s -> %s (%d bytes)" % (voice, os.path.basename(out_path), len(audio)))
    return True


def main():
    out = sys.argv[1]
    text = sys.argv[2] if len(sys.argv) > 2 else "異議あり！"
    voice = sys.argv[3] if len(sys.argv) > 3 else "ja-JP-KeitaNeural"
    return 0 if synth(text, voice, out) else 1


if __name__ == "__main__":
    sys.exit(main())
