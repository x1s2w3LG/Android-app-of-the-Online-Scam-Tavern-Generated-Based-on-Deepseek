"""判断音频文件某一段是不是静音：直接解析 MP4/M4A 里每个 AAC 帧的字节大小。

原理：AAC 是压缩格式，静音帧只需要很少的字节，正常人声帧会大得多。
所以对比"目标区间"和"已知有声区间"的平均帧大小即可，不需要解码器。

用法: python aac_frame_probe.py <文件> [每段秒数]
"""
import struct
import sys


def iter_boxes(data, start, end):
    i = start
    while i + 8 <= end:
        size = struct.unpack(">I", data[i:i + 4])[0]
        typ = data[i + 4:i + 8].decode("latin-1")
        header = 8
        if size == 1:
            size = struct.unpack(">Q", data[i + 8:i + 16])[0]
            header = 16
        elif size == 0:
            size = end - i
        if size < header:
            return
        yield typ, i + header, i + size
        i += size


def find(data, start, end, path):
    """按 box 路径查找，返回 (内容起点, 内容终点)。"""
    cur = [(start, end)]
    for want in path:
        nxt = []
        for (s, e) in cur:
            for typ, cs, ce in iter_boxes(data, s, e):
                if typ == want:
                    nxt.append((cs, ce))
        if not nxt:
            return None
        cur = nxt
    # 只取第一个匹配
    return cur[0]


def sample_sizes(data):
    box = find(data, 0, len(data), ["moov", "trak", "mdia", "minf", "stbl", "stsz"])
    if box is None:
        return None, 0
    s, e = box
    sample_size = struct.unpack(">I", data[s + 4:s + 8])[0]
    count = struct.unpack(">I", data[s + 8:s + 12])[0]
    if sample_size:
        return [sample_size] * count, count
    sizes = list(struct.unpack(">%dI" % count, data[s + 12:s + 12 + 4 * count]))
    return sizes, count


def main():
    path = sys.argv[1]
    secs = float(sys.argv[2]) if len(sys.argv) > 2 else 3.0
    with open(path, "rb") as f:
        data = f.read()
    sizes, count = sample_sizes(data)
    if not sizes:
        print("  没找到 stsz 表（不是 MP4/M4A？）")
        return 1

    # AAC 每帧 1024 采样；文件是 44100Hz
    per_sec = 44100.0 / 1024.0
    n = int(per_sec * secs)
    print("  总帧数 %d（约 %.1f 秒），每帧约 %.1f ms" % (count, count / per_sec, 1000 / per_sec))

    def stat(label, a, b):
        seg = sizes[a:min(b, len(sizes))]
        if not seg:
            print("  %-22s 无数据" % label)
            return
        avg = sum(seg) / len(seg)
        print("  %-22s 帧数 %5d  平均 %6.1f 字节  最小 %4d  最大 %5d"
              % (label, len(seg), avg, min(seg), max(seg)))
        return avg

    print("  各区间平均帧大小：")
    first = stat("前 %.0f 秒" % secs, 0, n)
    stat("第 30 秒起 %.0f 秒" % secs, int(per_sec * 30), int(per_sec * 33))
    mid = stat("第 1800 秒起 %.0f 秒" % secs, int(per_sec * 1800), int(per_sec * 1803))
    quiet = stat("最安静的 100 帧", sizes.index(min(sizes)), sizes.index(min(sizes)) + 100)

    print()
    if first and mid:
        ratio = first / mid
        print("  结论：前 %.0f 秒 / 中段 = %.2f 倍" % (secs, ratio))
        if ratio > 0.75:
            print("  => 前 %.0f 秒和其他时段音量相当，**有声音**" % secs)
        elif ratio > 0.35:
            print("  => 前 %.0f 秒明显偏轻，开头可能有一段静音或淡入" % secs)
        else:
            print("  => 前 %.0f 秒基本是静音，建议把起点往后挪" % secs)
    return 0


if __name__ == "__main__":
    sys.exit(main())
