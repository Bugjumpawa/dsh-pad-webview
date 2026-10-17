#!/usr/bin/env python3
"""把 d8 产出的 classes.dex 装进 aapt2 link 出的 APK 骨架。

不依赖 zip 命令；classes.dex 以 STORED（不压缩）方式写入，
这样 zipalign -p 能把它对齐到 4 字节，运行时可直接 mmap。
"""
import sys
import zipfile

def main() -> int:
    if len(sys.argv) != 4:
        print("usage: pack-apk.py <base.apk> <classes.dex> <out.apk>", file=sys.stderr)
        return 2
    base_apk, classes_dex, out_apk = sys.argv[1:4]
    with zipfile.ZipFile(base_apk) as src, zipfile.ZipFile(out_apk, "w") as dst:
        for item in src.infolist():
            dst.writestr(item, src.read(item.filename))
        dst.writestr("classes.dex", open(classes_dex, "rb").read(),
                     compress_type=zipfile.ZIP_STORED)
    print("packed", out_apk)
    return 0

if __name__ == "__main__":
    sys.exit(main())
