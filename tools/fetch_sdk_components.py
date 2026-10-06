"""直接下载 Android SDK 组件（绕过 sdkmanager 与 Gradle）。

sdkmanager 在这台机器上取不到包列表，Gradle 发行包又反复 TLS 超时，
所以这里用 dl.google.com 的直链（已逐个 HEAD 验证过 200）把构建 APK 所需的三件套拿下来：

    platform-tools            8 MB   adb
    build-tools;34.0.0       58 MB   aapt2 / d8 / zipalign / apksigner
    platforms;android-34     63 MB   android.jar

用法: python fetch_sdk_components.py <sdk_root>
"""
import os
import shutil
import sys
import urllib.request
import zipfile

BASE = "https://dl.google.com/android/repository/"
UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64)"

# (解压到 sdk 下的相对目录, 远端 zip 文件名)
DIRECT = [
    ("platform-tools", "platform-tools-latest-windows.zip"),
    (os.path.join("build-tools", "34.0.0"), "build-tools_r34-windows.zip"),
    (os.path.join("platforms", "android-34"), "platform-34-ext7_r03.zip"),
]


def fetch(url, dest):
    if os.path.exists(dest) and os.path.getsize(dest) > 0:
        print("[skip] %s" % dest, flush=True)
        return True
    tmp = dest + ".part"
    for attempt in range(1, 6):
        try:
            print("[get ] %s (attempt %d)" % (url, attempt), flush=True)
            req = urllib.request.Request(url, headers={"User-Agent": UA})
            total = 0
            with urllib.request.urlopen(req, timeout=300) as r, open(tmp, "wb") as f:
                while True:
                    chunk = r.read(262144)
                    if not chunk:
                        break
                    f.write(chunk)
                    total += len(chunk)
                    if total % (20 * 1024 * 1024) < 262144:
                        print("       ... %d MB" % (total // (1024 * 1024)), flush=True)
            os.replace(tmp, dest)
            print("[done] %s (%d MB)" % (dest, total // (1024 * 1024)), flush=True)
            return True
        except Exception as e:
            print("[warn] 第 %d 次失败: %s: %s" % (attempt, type(e).__name__, e), flush=True)
            try:
                if os.path.exists(tmp):
                    os.remove(tmp)
            except OSError:
                pass
    return False


def extract_into(zip_path, target_dir):
    """zip 里通常只有一层目录（名字可能是 android-14 之类的代号），统一改名成目标目录。"""
    tmp = target_dir + "__tmp"
    if os.path.exists(tmp):
        shutil.rmtree(tmp)
    os.makedirs(tmp, exist_ok=True)
    with zipfile.ZipFile(zip_path) as z:
        z.extractall(tmp)
    entries = os.listdir(tmp)
    src = os.path.join(tmp, entries[0]) if len(entries) == 1 else tmp
    if os.path.exists(target_dir):
        shutil.rmtree(target_dir)
    parent = os.path.dirname(target_dir)
    if parent:
        os.makedirs(parent, exist_ok=True)
    shutil.move(src, target_dir)
    shutil.rmtree(tmp, ignore_errors=True)
    print("[unzp] -> %s" % target_dir, flush=True)


def main():
    sdk = sys.argv[1]
    os.makedirs(sdk, exist_ok=True)
    ok = True
    for rel, filename in DIRECT:
        target = os.path.join(sdk, rel)
        marker = os.path.join(target, "source.properties")
        if os.path.isdir(target) and os.listdir(target):
            print("[skip] 已存在 %s" % target, flush=True)
            continue
        zip_path = os.path.join(sdk, filename)
        if not fetch(BASE + filename, zip_path):
            print("[FAIL] %s 下载失败" % filename, flush=True)
            ok = False
            continue
        try:
            extract_into(zip_path, target)
        except Exception as e:
            print("[FAIL] %s 解压失败: %s" % (zip_path, e), flush=True)
            ok = False
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
