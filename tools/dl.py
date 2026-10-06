"""极简多文件下载器：用 Python 的 OpenSSL 栈下载（本机 schannel 有凭证问题，curl/.NET 不可用）。
用法: python dl.py <url> <dest> [<url> <dest> ...]
"""
import os
import sys
import time
import urllib.request

UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64)"


def dl(url, dest):
    if os.path.exists(dest) and os.path.getsize(dest) > 0:
        print("[skip] %s (%d bytes)" % (dest, os.path.getsize(dest)), flush=True)
        return True
    tmp = dest + ".part"
    for attempt in range(1, 6):
        try:
            print("[get ] %s (attempt %d)" % (url, attempt), flush=True)
            req = urllib.request.Request(url, headers={"User-Agent": UA})
            total = 0
            with urllib.request.urlopen(req, timeout=120) as r, open(tmp, "wb") as f:
                while True:
                    chunk = r.read(262144)
                    if not chunk:
                        break
                    f.write(chunk)
                    total += len(chunk)
                    if total % (20 * 1024 * 1024) < 262144:
                        print("       ... %d MB" % (total // (1024 * 1024)), flush=True)
            os.replace(tmp, dest)
            print("[done] %s (%d bytes)" % (dest, total), flush=True)
            return True
        except Exception as e:
            print("[warn] 第 %d 次失败: %s: %s" % (attempt, type(e).__name__, e), flush=True)
            try:
                if os.path.exists(tmp):
                    os.remove(tmp)
            except OSError:
                pass
            time.sleep(3 * attempt)
    print("[FAIL] %s 下载失败" % url, flush=True)
    return False


def main():
    args = sys.argv[1:]
    if len(args) % 2 != 0:
        print("参数必须成对: url dest")
        return 2
    ok = True
    for i in range(0, len(args), 2):
        if not dl(args[i], args[i + 1]):
            ok = False
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
