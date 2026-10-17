#!/usr/bin/env python3
"""检查 APK 里的原生 .so 是否满足 Android 16 的 16KB 页对齐要求。

Android 15+ 开始有 16KB 页内核；未对齐的 .so 在加载时会失败（表现为运行期崩溃 / 回退路径反复重试），
`file -i` 看不出来，得看 ELF 程序头的 p_align。这里用 zipfile + struct 直接解析，不依赖 readelf。
"""
import struct
import sys
import zipfile

apk = sys.argv[1] if len(sys.argv) > 1 else "release/ha-companion-minimal-v0.1.3.apk"


def align_of(raw: bytes):
    """返回 ELF 里 PT_LOAD 段的最大 p_align；32 位 ELF 返回 "32bit"（16KB 页只跑在 arm64/x86_64 上）。"""
    if raw[:4] != b"\x7fELF":
        return None
    if raw[4] != 2:
        return "32bit"
    e_phoff = struct.unpack_from("<Q", raw, 0x20)[0]
    e_phentsize = struct.unpack_from("<H", raw, 0x36)[0]
    e_phnum = struct.unpack_from("<H", raw, 0x38)[0]
    best = 0
    for i in range(e_phnum):
        base = e_phoff + i * e_phentsize
        p_type = struct.unpack_from("<I", raw, base)[0]
        if p_type != 1:  # PT_LOAD
            continue
        best = max(best, struct.unpack_from("<Q", raw, base + 0x30)[0])
    return best


bad, checked, ok = [], 0, []
with zipfile.ZipFile(apk) as z:
    for name in z.namelist():
        if not name.endswith(".so"):
            continue
        checked += 1
        try:
            a = align_of(z.read(name))
        except Exception as e:  # noqa: BLE001
            print(f"  跳过 {name}: {e}")
            continue
        if a == "32bit":
            continue  # 32 位 ABI 不在 16KB 页内核上运行，跳过而不是算作违规
        if a is None or a < 16384:
            bad.append((name, a))
        else:
            ok.append((name, a))

print(f"{apk}\n  检查了 {checked} 个 .so（64 位 {len(ok) + len(bad)} 个）")
for name, a in sorted(ok):
    print(f"    OK  {name}  p_align={hex(a)}")
if bad:
    print(f"  未 16KB 对齐：{len(bad)} 个")
    for name, a in bad[:20]:
        print(f"    {name}  p_align={a}")
else:
    print("  所有 64 位 .so 都满足 16KB 页对齐")
