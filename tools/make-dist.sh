#!/usr/bin/env bash
#
# 打一份可以直接交给客户的包。
#
#   tools/make-dist.sh                 用现有的 target/device-hub.jar 打包
#   tools/make-dist.sh --build         先 mvn package 再打包
#   tools/make-dist.sh --version 1.1.0 手工指定版本（一般不用，版本从 jar 里读）
#
# 包里只有这几样东西：jar、install.sh、bin/ 四个运维脚本、README。
# 打完立刻跑 audit-dist.sh，审计不过就删掉 zip —— 宁可这次不发，也不要发一个
# 带着私钥或演示数据的包出去。
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/.." && pwd)"
BASE="$ROOT/dist"

VERSION=""
DO_BUILD=0
while [ $# -gt 0 ]; do
  case "$1" in
    --version) VERSION="${2:-}"; shift ;;
    --build)   DO_BUILD=1 ;;
    -h|--help) sed -n '2,12p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
    *) echo "未知参数：$1"; exit 1 ;;
  esac
  shift
done

say() { printf '\n== %s\n' "$*"; }
ok()  { printf '   %s\n' "$*"; }
warn() { printf '   [注意] %s\n' "$*"; }
die() { printf '失败：%s\n' "$*" >&2; exit 1; }

JAR="$ROOT/target/device-hub.jar"

# ---------- 打包前断言：jar 必须是**当前**代码编出来的 ----------
# 2026-11-04 真机实测踩到：改完代码只跑了 `mvn test`（不产出 jar），
# make-dist.sh 拿到的还是上一次的 target/device-hub.jar ——
# 交付包里**没有**新功能，服务器上 404，而本地单测全绿、审计全过。
# 所以这里断言 jar 的时间戳比所有源文件新。
#
# ⚠️ 2026-11-06 修：这段原本**无条件在构建之前**跑，于是 `--build` 被自己的门禁废掉了——
#    有源文件改动时 jar 必然是旧的，检查直接 die，永远走不到构建那一步。
#    （表现出来就是"改了代码就必须手动 mvn clean package，--build 是个摆设"。）
#    现在包成函数：
#      · 不带 --build（用现成 jar）→ 打包前必须验，这是原意
#      · 带 --build → 验构建**之后**的 jar（构建前旧是正常的）
assert_jar_fresh() {
JAR_MTIME=$(python -c "import os,sys;print(int(os.path.getmtime(sys.argv[1])))" "$JAR" 2>/dev/null || echo 0)
if [ "$JAR_MTIME" = "0" ]; then
  die "找不到 $JAR —— 先跑 mvn package，或加 --build 让本脚本构建"
fi
STALE=$(python - "$JAR" "$ROOT/src" <<'PYEOF'
import os, sys
jar, src = sys.argv[1], sys.argv[2]
jm = os.path.getmtime(jar)
newer = []
for base, _, files in os.walk(src):
    for f in files:
        if f.endswith(('.java', '.yml', '.sql')):
            p = os.path.join(base, f)
            if os.path.getmtime(p) > jm + 1:
                newer.append(os.path.relpath(p, os.path.dirname(src)))
print(len(newer))
PYEOF
)
if [ "$STALE" != "0" ]; then
  die "jar 比源文件旧（$STALE 个源文件更新）—— 跑 mvn clean package 后再打包，否则交付包里是上一版代码"
fi
say "jar 时效检查通过（比所有源文件新）"
}

if [ "$DO_BUILD" -eq 0 ]; then
  assert_jar_fresh
fi

# ---------- 1. 构建 ----------
if [ "$DO_BUILD" -eq 1 ]; then
  # ---------- 报警码字典校验（必须在构建之前） ----------
  # 为什么放在构建前而不是构建后：错的字典如果已经编进 jar，
  # target/ 里就躺着一个坏 jar —— 而 tools/release.sh --no-build
  # （快速重发）会直接把它打进去发出去，门禁反而漏了。
  # 「坏字典根本产不出 jar」才是我们要的效果。
  #
  # 为什么是 node 而不是 python：本机没有可用的 python（WindowsApps 下只有
  # Store 存根，真执行会跳商店），而 node 是前端工具链的既有依赖。
  # 校验器是零依赖静态检查，不需要数据库、不需要 jar，任何环境都能跑。
  say "校验报警码字典（scripts/check-alarmcodes.mjs）"
  if command -v node >/dev/null 2>&1; then
    # ⚠️ 必须断言「脚本确实产出了输出」：
    # 本脚本曾被改到只剩注释头、没有 main() 调用 —— 于是它永远退出 0，
    # 看起来门禁在守，实际什么都没查（**假绿比没有门禁更危险**）。
    # 光看退出码抓不住这种失效，必须看它有没有说话。
    CHK_OUT="$(node "$ROOT/scripts/check-alarmcodes.mjs" 2>&1)" || {
      printf '%s\n' "$CHK_OUT"
      die "报警码字典校验未通过 —— 字典没写对，不允许打包（详见上面的阻断项）"
    }
    printf '%s\n' "$CHK_OUT"
    # 脚本没输出 = 它没在跑（被改残了 / main() 丢失 / 提前 return）
    if [ -z "$CHK_OUT" ]; then
      die "报警码字典校验脚本没有输出 —— 它可能已失效（例如 main() 调用丢失），门禁会变成假绿"
    fi

    # ---------- 变异验证：证明校验器本身有效 ----------
    # 「跑一下 exit 0」不能证明门禁有效 —— 只有「故意注入错误后它必须 exit 1」才能。
    # 本轮就栽过：暂存区里的校验器是个空壳，跑一下就 exit 0，什么都拦不住。
    say "变异验证报警码校验器（scripts/mutation-check-alarmcodes.mjs）"
    MUT_OUT="$(node "$ROOT/scripts/mutation-check-alarmcodes.mjs" 2>&1)" || {
      printf '%s\n' "$MUT_OUT"
      die "校验器变异验证未通过 —— 门禁拦不住错误，不允许打包"
    }
    printf '%s\n' "$MUT_OUT"
    if [ -z "$MUT_OUT" ]; then
      die "变异验证脚本没有输出 —— 它可能已失效，门禁会变成假绿"
    fi
  else
    die "找不到 node。字典校验需要 Node 20+（校验器是零依赖脚本）"
  fi
  ok "报警码字典校验通过"

  MVN_BIN="${MVN:-}"
  if [ -z "$MVN_BIN" ]; then
    if command -v mvn >/dev/null 2>&1; then
      MVN_BIN="mvn"
    elif [ -x "$ROOT/../tools/apache-maven-3.9.9/bin/mvn" ]; then
      MVN_BIN="$ROOT/../tools/apache-maven-3.9.9/bin/mvn"
    else
      die "找不到 mvn。用 MVN=/path/to/mvn 指定，或者先自己 mvn package"
    fi
  fi
  [ -n "${JAVA_HOME:-}" ] || command -v java >/dev/null 2>&1 \
    || die "需要 JAVA_HOME 或 PATH 里有 java"
  # ---------- 前端构建（Vue 3 + Vite，ADR-002）：frontend/ 存在则必须成功 ----------
  if [ -d "$ROOT/frontend" ]; then
    command -v npm >/dev/null 2>&1 \
      || die "frontend/ 存在但本机没有 npm。装 Node 20+，或临时移走 frontend/ 目录"
    say "构建前端（frontend/ → static/app/）"
    NPM_REG="${NPM_REGISTRY:-https://registry.npmmirror.com}"
    ( cd "$ROOT/frontend" \
        && { npm ci --registry="$NPM_REG" --no-fund --no-audit >/dev/null 2>&1 \
             || npm install --registry="$NPM_REG" --no-fund --no-audit; } \
        && npm run build ) || die "前端构建失败——jar 会缺新版界面，已中止打包"
    ok "前端构建完成"
  fi

  say "构建 jar（$MVN_BIN）"
  # 必须 clean：mvn 的资源拷贝是覆盖式，不清 target/classes 会把历史构建的
  # 旧 hash chunk（旧 JS/CSS）一起打进 jar，包越滚越大还带着已删代码。
  ( cd "$ROOT" && "$MVN_BIN" -q -DskipTests clean package ) || die "构建失败，看上面的报错"
  ok "构建完成"
fi

[ -f "$JAR" ] || die "没有 $JAR，先跑 tools/make-dist.sh --build"

# --build 路径：构建完再验一次，确认这个 jar 确实是刚用当前源码编出来的
if [ "$DO_BUILD" -eq 1 ]; then
  assert_jar_fresh
fi

# ---------- 打包前断言：jar 内资源必须与源码目录一致（DH-83） ----------
# 2026-11-05 踩到：`db/migrations/1.9.0.sql`（DH-72 排程的迁移脚本）**从未提交过 git**，
# 只活在本地 target/classes 里。后果极有欺骗性：
#   · mvn clean package（正常发版）→ clean 删掉 target → jar 里没有 → 客户建不出 idx_wo_line_plan
#   · make-dist.sh（不构建，用现成 jar）→ jar 里可能有残留的它 → 客户有
# ⇒ 同一份代码，发版路径不同，客户拿到的能力不同，而单测全绿、审计全过。
# 上面那道「jar 比源文件新」比的是**时间戳**：文件根本不在 src 里时，没有源文件可比。
# ⚠️ 位置必须在构建之后：--build 时 jar 是刚产出的，检查它才有意义。
say "校验 jar 内资源与源码一致（tools/check-jar-resources.py）"
CHK_RES_OUT="$(python "$ROOT/tools/check-jar-resources.py" 2>&1)" || {
  printf '%s\n' "$CHK_RES_OUT"
  die "jar 内资源与源码不一致 —— 交付包会缺东西，不允许打包（详见上面的差异）"
}
printf '%s\n' "$CHK_RES_OUT"
[ -n "$CHK_RES_OUT" ] \
  || die "jar 资源校验脚本没有输出 —— 它可能已失效，门禁会变成假绿"
ok "jar 内资源与源码一致"

# ---------- 2. 版本号 ----------
# 从 jar 里的 pom.properties 读，和运行时显示的版本是同一个来源。
# 从 pom.xml 里 sed 会先抓到 <parent> 的版本，闹过笑话。
if [ -z "$VERSION" ]; then
  VERSION="$(python - "$JAR" <<'PY'
import sys, zipfile
with zipfile.ZipFile(sys.argv[1]) as z:
    for n in z.namelist():
        if n.endswith("pom.properties") and "device-hub" in n:
            for line in z.read(n).decode("utf-8", "ignore").splitlines():
                if line.startswith("version="):
                    print(line.split("=", 1)[1].strip())
                    break
            break
PY
)"
fi
[ -n "$VERSION" ] || die "读不到版本号，用 --version 手工指定"

STAGE="$BASE/device-hub-$VERSION"
ZIP="$BASE/device-hub-$VERSION.zip"

say "打包 device-hub $VERSION"

# ---------- 3. 摆文件 ----------
rm -rf "$STAGE"
mkdir -p "$STAGE/bin"

# ---------- 按发行清单复制（单一真相来源：tools/dist-manifest.txt）----------
# 以前这里硬编码了一份复制清单，而 install.sh 与交付冒烟脚本各写了一份
# 「包里该有什么」—— 三处各写一遍，迟早会对不上。
# 2026-11-04 真机实测就栽在这：install.sh 的复制清单漏了 bin/backup.sh 与
# bin/restore.sh → 客户机上备份功能根本不存在，而 247 单测全绿、审计 0 阻断。
# 现在只有一个清单，make-dist / qc-gate / delivery-smoke 都读它。
MANIFEST="$HERE/dist-manifest.txt"
[ -f "$MANIFEST" ] || die "缺发行清单 $MANIFEST"
_manifest_n=0
while IFS= read -r rel; do
  rel="$(printf '%s' "$rel" | tr -d '\r')"
  case "$rel" in ""|\#*) continue ;; esac
  # device-hub.jar 是**构建产物**（在 target/），其余是源码文件（项目根目录）
  src="$ROOT/$rel"
  [ "$rel" = "device-hub.jar" ] && src="$JAR"
  [ -f "$src" ] || die "缺 $rel（发行清单 tools/dist-manifest.txt 要求，源 $src）"
  mkdir -p "$STAGE/$(dirname "$rel")"
  cp "$src" "$STAGE/$rel"
  case "$rel" in
    *.sh) chmod 755 "$STAGE/$rel" ;;
    *)    chmod 644 "$STAGE/$rel" ;;
  esac
  _manifest_n=$((_manifest_n + 1))
done < "$MANIFEST"
ok "已按发行清单复制 $_manifest_n 个文件"

JAR_SHA="$(sha256sum "$STAGE/device-hub.jar" | awk '{print $1}')"
cat > "$STAGE/VERSION.txt" <<EOF
device-hub $VERSION
构建时间：$(date '+%Y-%m-%d %H:%M:%S')
jar sha256：$JAR_SHA

安装：
  sudo ./install.sh

安装前请先读 README.md 第十节（部署与升级）。
EOF
chmod 644 "$STAGE/VERSION.txt"

# 逐个文件的校验和，客户可以自己核对（防传输损坏，也防中间被替换）
( cd "$STAGE" && find . -type f ! -name SHA256SUMS -exec sha256sum {} \; | sort -k2 ) > "$STAGE/SHA256SUMS"
chmod 644 "$STAGE/SHA256SUMS"

ok "文件已就位：$(ls -1 "$STAGE" | tr '\n' ' ')"

# ---------- 4. 强制审计 ----------
say "内容审计"
if ! bash "$HERE/audit-dist.sh" "$STAGE"; then
  echo
  die "审计不通过，不产出 zip。目录留在 $STAGE 供你核对"
fi

# ---------- 5. 压缩 ----------
say "生成压缩包"
rm -f "$ZIP"
python - "$STAGE" "$ZIP" <<'PY' || die "压缩失败"
import os, shutil, sys, zipfile
src, out = sys.argv[1], sys.argv[2]
shutil.make_archive(out[:-4], "zip", root_dir=os.path.dirname(src),
                    base_dir=os.path.basename(src))

# shutil.make_archive **不保存 Unix 权限**（Windows 上打的包尤其明显）：
# 解压出来 install.sh 是 -rw-rw-rw-，客户第一条 `./install.sh` 直接 Permission denied。
# 这里把权限写进 zip 的 external_attr，Info-ZIP 的 unzip 会还原。
# 双保险：文档里的命令也统一写成 `sudo bash install.sh`，不依赖执行位。
with zipfile.ZipFile(out) as z:
    entries = [(i, z.read(i.filename)) for i in z.infolist()]
with zipfile.ZipFile(out, "w", zipfile.ZIP_DEFLATED) as z:
    for info, data in entries:
        mode = 0o755 if info.filename.endswith(".sh") else 0o644
        info.external_attr = (mode & 0xFFFF) << 16
        z.writestr(info, data)
print("   已写入 Unix 权限（.sh = 755）")
PY

ZIP_SHA="$(sha256sum "$ZIP" | awk '{print $1}')"
ZIP_SIZE=$(( $(wc -c < "$ZIP") / 1024 / 1024 ))
ok "已生成 dist/$(basename "$ZIP")（${ZIP_SIZE} MB）"
ok "sha256：$ZIP_SHA"

# ---------- 5.5 更新源清单 version.json ----------
# install.sh --online 靠它发现新版本。url 指向 GitHub Release 的资产：
# 打 Release 时 tag 用 v$VERSION，资产名保持 device-hub-$VERSION.zip，url 即可对上。
RELEASE_URL="https://github.com/ZhuangRenyang/KnowFab-SIRP/releases/download/v${VERSION}/$(basename "$ZIP")"
cat > "$STAGE/version.json" <<JSON
{
  "version": "$VERSION",
  "url": "$RELEASE_URL",
  "sha256": "$ZIP_SHA"
}
JSON
# 同步一份到 dist/ 根：这才是 --online 安装/检查更新读取的更新源清单（进 git）
cp "$STAGE/version.json" "$BASE/version.json"
ok "已生成 dist/version.json（version=$VERSION）"
warn "记得把 zip 上传到 GitHub Release（tag: v$VERSION），version.json 的下载链接才会生效"

cat <<EOF

发给客户的就这一个文件：
  $(basename "$ZIP")
  sha256: $ZIP_SHA

客户侧：
  unzip $(basename "$ZIP")
  cd device-hub-$VERSION
  sha256sum -c SHA256SUMS        # 核对完整性
  sudo ./install.sh

提醒：这个包不带 application.yml，配置由 install.sh 按现场生成（管理员 admin / 123456）。
EOF
