#!/bin/bash
# 发布包端到端验证。
#
# 真身是 verify-release.py（jar 改版、库操作、HTTP、进程管理在 Windows 上
# 用 bash 太容易踩路径和编码的坑，脚本主体放在 Python 里，这里只做转发）。
#
# 用法：
#   bash tools/verify-release.sh                    # 验 dist 里最新的包
#   bash tools/verify-release.sh --version 1.0.22
#   bash tools/verify-release.sh --keep             # 保留验证库和临时目录
#
# 退出码：0 = 全部通过，1 = 有失败项。
exec python "$(dirname "$0")/verify-release.py" "$@"
