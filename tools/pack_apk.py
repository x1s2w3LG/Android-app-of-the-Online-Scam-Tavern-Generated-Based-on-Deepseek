"""把 assets 与 classes.dex 打包进 aapt2 link 产出的 APK。

为什么不用 aapt2 的 -A、也不用 JDK 的 jar --update：
  * aapt2 (build-tools 34/36, Windows) 的 -A 会把 asset 条目名写成 Windows 反斜杠
    （assets/web\\index.html）。APK 里看着"有文件"，但 Android 的 AssetManager
    按正斜杠查找，list("web") 返回空、open("web/index.html") 抛 FileNotFoundException，
    结果 HTTP 服务器对一切资源都返回 404。
  * 这里用 zipfile 显式写入正斜杠路径，保证是标准的 assets/web/index.html。

保留每条原有条目的压缩方式（resources.arsc 必须保持不压缩，否则 Android 11+ 起不来）。

用法: python pack_apk.py <base.apk> <assets_dir> <classes.dex> <out.apk>
"""
import os
import sys
import zipfile


def main():
    if len(sys.argv) < 5:
        print("用法: pack_apk.py <base.apk> <assets_dir> <classes.dex> <out.apk>")
        return 2
    base, assets_dir, dex, out = sys.argv[1:5]

    if os.path.exists(out):
        os.remove(out)

    written = set()
    asset_names = []
    with zipfile.ZipFile(base) as zin:
        with zipfile.ZipFile(out, "w", zipfile.ZIP_DEFLATED) as zout:
            # 1) 原样搬过来（保留 compress_type，尤其 resources.arsc 必须 STORED）
            for item in zin.infolist():
                data = zin.read(item.filename)
                zout.writestr(item, data)
                written.add(item.filename)

            # 2) assets/**，一律使用正斜杠
            if assets_dir and os.path.isdir(assets_dir):
                for root, dirs, files in os.walk(assets_dir):
                    dirs.sort()
                    for f in sorted(files):
                        full = os.path.join(root, f)
                        rel = os.path.relpath(full, assets_dir).replace(os.sep, "/")
                        name = "assets/" + rel
                        if name in written:
                            continue
                        zi = zipfile.ZipInfo(name, date_time=(1980, 1, 1, 0, 0, 0))
                        zi.compress_type = zipfile.ZIP_DEFLATED
                        zi.external_attr = 0o644 << 16
                        with open(full, "rb") as fh:
                            zout.writestr(zi, fh.read())
                        written.add(name)
                        asset_names.append(name)

            # 3) classes.dex
            if dex and os.path.exists(dex) and "classes.dex" not in written:
                with open(dex, "rb") as fh:
                    zout.writestr("classes.dex", fh.read())
                written.add("classes.dex")

    print("[pack] 写入 %d 个 asset, 共 %d 个条目" % (len(asset_names), len(written)))
    for n in asset_names:
        print("       %s" % n)
    if not asset_names:
        print("[pack] 警告: 没有任何 asset 被打包!")
        return 1
    bad = [n for n in asset_names if "\\" in n]
    if bad:
        print("[pack] 错误: 仍有反斜杠路径 %s" % bad)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
