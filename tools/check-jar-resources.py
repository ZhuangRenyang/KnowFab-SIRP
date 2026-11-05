#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""校验 jar 内的资源文件与源码目录一致（DH-83）。

# 为什么要这门禁

2026-11-05 发现：`db/migrations/1.9.0.sql`（DH-72 生产工单排程的迁移脚本）
**只活在本地 `target/classes/`，从未提交过 git**。

它造成的后果很有欺骗性：

| 构建方式 | jar 里有 1.9.0.sql 吗 | 客户能建出 `idx_wo_line_plan` 索引吗 |
|---|---|---|
| `mvn clean package`（正常发版） | **没有**（clean 删了 target） | **不能** ⇒ 排程冲突查询全表扫 |
| `make-dist.sh --no-build`（用现成 jar） | 有 | 能 |

⇒ 同一份代码，**发版路径不同，客户拿到的能力不同**。这种缺陷没有任何报错，
   单测全绿、审计全过——因为缺的东西从来就没被任何人读过。

已有的两道检查都拦不到：
- `make-dist.sh` 的「jar 比源文件新」比的是**时间戳**。文件根本不在 src 里时，没有源文件可比。
- `qc-gate.py` 阶段⑥ 核对 `tools/dist-manifest.txt`，那是**包内文件**（7 条 bin 脚本），
  不管 **jar 内资源**。

# 检查什么

对每一组「jar 内路径 ↔ 源码路径」：
1. **文件名集合必须完全相同**（jar 有 src 没有 = 幽灵文件；src 有 jar 没有 = 漏编译）
2. **同名文件内容 sha256 必须相同**（防止打包后又被改）

# 用法

    python tools/check-jar-resources.py [--jar target/device-hub.jar] [--quiet]

退出码：0 = 一致；1 = 有差异（打印具体哪个文件）。
"""
import argparse
import hashlib
import os
import sys
import zipfile

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))

# 需要逐字节核对的资源目录：jar 内前缀 → 源码目录（相对项目根）
# 只列「由文件驱动行为」的资源：缺一个就有一块功能静默失效。
RESOURCE_GROUPS = [
    ("BOOT-INF/classes/db/migrations/", "src/main/resources/db/migrations/"),
    ("BOOT-INF/classes/alarmcodes/", "src/main/resources/alarmcodes/"),
]


def sha256_bytes(data):
    return hashlib.sha256(data).hexdigest()


def list_jar(jar, prefix):
    """返回 {文件名: bytes}（只取直接子文件，不递归子目录）"""
    out = {}
    with zipfile.ZipFile(jar) as z:
        for info in z.infolist():
            name = info.filename
            if not name.startswith(prefix) or info.is_dir():
                continue
            rest = name[len(prefix):]
            if "/" in rest or not rest:
                continue  # 只关心本层文件
            out[rest] = z.read(name)
    return out


def list_src(src_dir):
    out = {}
    if not os.path.isdir(src_dir):
        return out
    for f in sorted(os.listdir(src_dir)):
        p = os.path.join(src_dir, f)
        if os.path.isfile(p):
            with open(p, "rb") as fh:
                out[f] = fh.read()
    return out


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--jar", default=os.path.join(ROOT, "target", "device-hub.jar"))
    ap.add_argument("--quiet", action="store_true")
    args = ap.parse_args()

    jar = args.jar
    if not os.path.isfile(jar):
        print("找不到 jar：%s —— 先跑 mvn package" % jar)
        return 1

    errors = []
    checked = 0
    for jar_prefix, src_rel in RESOURCE_GROUPS:
        src_dir = os.path.join(ROOT, src_rel)
        in_jar = list_jar(jar, jar_prefix)
        in_src = list_src(src_dir)

        only_jar = sorted(set(in_jar) - set(in_src))
        only_src = sorted(set(in_src) - set(in_jar))
        # 幽灵文件：jar 里有但源码里没有 —— 它会在运行时生效，但没人能在仓库里改它
        for f in only_jar:
            errors.append("%s%s 只存在于 jar 里，源码目录 %s 没有它 "
                          "⇒ 这个文件没人能维护，下次 clean 构建就会消失"
                          % (jar_prefix, f, src_rel))
        # 漏编译：源码有但 jar 里没有 —— 客户拿不到
        for f in only_src:
            errors.append("%s%s 在源码里但没进 jar "
                          "⇒ 客户机上这块功能/数据不存在（是不是没重新 package？）"
                          % (src_rel, f))

        for f in sorted(set(in_jar) & set(in_src)):
            checked += 1
            hj, hs = sha256_bytes(in_jar[f]), sha256_bytes(in_src[f])
            if hj != hs:
                errors.append("%s%s 内容不一致：jar=%s… 源码=%s… "
                              "（jar 不是这份源码编出来的，或打包后被改过）"
                              % (src_rel, f, hj[:12], hs[:12]))

    if errors:
        print("jar 内资源与源码不一致（%d 处）：" % len(errors))
        for e in errors:
            print("  - " + e)
        return 1

    if not args.quiet:
        print("jar 内资源与源码一致：逐字节核对 %d 个文件 ✓" % checked)
    return 0


if __name__ == "__main__":
    sys.exit(main())
