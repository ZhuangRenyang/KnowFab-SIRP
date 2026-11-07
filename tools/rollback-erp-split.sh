#!/usr/bin/env bash
# erp 拆包：批次打标与回退工具。
#
# ⭐ 为什么回滚**不能**「只回滚某一批」：
#   批次是**串行**的：B3 之后，B4/B5/B6 的文件已经在子包里、import 指向
#   `erp.stock.*` 这类新包名 ⇒ 只回滚 B3（把 stock 搬回 com.factory.hub.erp）
#   会让后面几批的 import 全部指向不存在的包 ⇒ **编译不过**。
#   ⇒ **回滚粒度只能是「从第 N 批起，往后全部回退」**。
#
# ⛔ 为什么**不用** `git checkout <旧sha> -- src/main/java/com/factory/hub/erp` 做单批回滚：
#   那只把文件搬回来，**忘了改「留下那侧」的 import** ⇒ 半回滚状态 ⇒ 编译不过。
#   ⇒ 一律用 **tag + reset**（已推送时用 **revert**），保证工作树是自洽的。
#
# 用法：
#   tools/rollback-erp-split.sh --status
#   tools/rollback-erp-split.sh --tag 0                 # 当前 HEAD 打上 erp-split-base
#   tools/rollback-erp-split.sh --tag 3                 # 当前 HEAD 打上 erp-split-b3
#   tools/rollback-erp-split.sh --from-batch 3          # 回退第 3 批及以后（保留前 2 批）
#   tools/rollback-erp-split.sh --from-batch 3 --revert  # 已推送：倒序 revert，不改历史
#   tools/rollback-erp-split.sh --from-batch 3 --dry-run
#
# 退出码：0 = 成功；1 = 失败；2 = 用法/环境错误
#
# ⚠️ 本脚本只做**版本控制层**的回退，不碰业务代码、不跑测试。
#   回退之后必须重新跑 tools/verify-batch-acceptance.sh 确认树是自洽的。

set -uo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT" || exit 2

TAG_PREFIX="erp-split"
BASE_TAG="${TAG_PREFIX}-base"
BATCH_TAG_RE="${TAG_PREFIX}-b[0-9]+"

say()  { printf '\n\033[1m== %s\033[0m\n' "$*"; }
line() { printf '  %s %-26s %s\n' "$1" "$2" "$3"; }
die()  { printf '\n✗ %s\n' "$*" >&2; exit 1; }
usage() {
  sed -n '2,26p' "$0" | sed 's/^# \{0,1\}//'
  exit 2
}

# ---------------- 参数 ----------------
MODE=""
BATCH=""
TARGET_TAG=""
DRY=0
REVERT=0
ASSUME_YES=0

while [ $# -gt 0 ]; do
  case "$1" in
    --status)     MODE="status"; shift ;;
    --tag)        MODE="tag"; BATCH="${2:-}"; shift 2 ;;
    --from-batch) MODE="rollback"; BATCH="${2:-}"; shift 2 ;;
    --dry-run)    DRY=1; shift ;;
    --revert)     REVERT=1; shift ;;
    --yes|-y)     ASSUME_YES=1; shift ;;
    -h|--help)    usage ;;
    *) echo "未知参数：$1" >&2; usage ;;
  esac
done

[ -n "$MODE" ] || usage
command -v git >/dev/null 2>&1 || { echo "缺少 git" >&2; exit 2; }
git rev-parse --git-dir >/dev/null 2>&1 || { echo "不在 git 仓库里：$ROOT" >&2; exit 2; }

# ---------------- 工具函数 ----------------

# 所有 erp-split-* 的 tag，按批次号排序
list_tags() {
  git tag -l "${TAG_PREFIX}-*" 2>/dev/null | sort -V
}

# 当前已完成的最高批次号（0 = 一个批次都还没做）
applied_batch() {
  local max=0 t n
  for t in $(list_tags); do
    n="${t#${TAG_PREFIX}-b}"
    case "$n" in
      ''|*[!0-9]*) continue ;;
    esac
    [ "$n" -gt "$max" ] && max="$n"
  done
  echo "$max"
}

# 已跟踪文件是否有改动（未跟踪文件不算：reset --hard 不会删它们）
tracked_dirty() {
  [ -n "$(git status --porcelain --untracked-files=no 2>/dev/null)" ]
}

dirty_list() {
  git status --porcelain --untracked-files=no 2>/dev/null
}

require_clean_tree() {
  if tracked_dirty; then
    echo "✗ 工作树有**已跟踪文件**的改动，拒绝执行（回退会丢掉它们）：" >&2
    dirty_list | sed 's/^/       /' >&2
    echo "  ⇒ 先 commit 或 stash，再回退。" >&2
    exit 1
  fi
}

confirm() {
  local prompt="$1"
  if [ "$ASSUME_YES" = "1" ]; then return 0; fi
  if [ ! -t 0 ]; then
    echo "✗ 非交互环境，必须显式加 --yes 才会真的执行（dry-run 请用 --dry-run）" >&2
    exit 1
  fi
  printf '%s [y/N] ' "$prompt"
  local a=""
  read -r a
  case "$a" in
    y|Y|yes|YES) return 0 ;;
    *) echo "已取消。"; exit 1 ;;
  esac
}

# ---------------- --status ----------------
if [ "$MODE" = "status" ]; then
  say "erp 拆包批次状态"
  line "·" "当前 HEAD" "$(git log --oneline -1)"
  line "·" "分支" "$(git branch --show-current)"
  line "·" "已推送" "$(git rev-parse --abbrev-ref --symbolic-full-name '@{u}' 2>/dev/null || echo '(无上游)')"

  echo ""
  echo "  批次 tag（${TAG_PREFIX}-base = 拆分前基线）："
  if [ -z "$(list_tags)" ]; then
    line "—" "(还没有任何批次 tag)" ""
  else
    for t in $(list_tags); do
      local_desc="$(git log --oneline -1 "$t" 2>/dev/null)"
      line "·" "$t" "$local_desc"
    done
  fi

  echo ""
  line "·" "已完成的最高批次" "$(applied_batch)"
  if tracked_dirty; then
    line "✗" "工作树" "有已跟踪文件的改动（回退会被拒绝）"
  else
    line "✓" "工作树" "已跟踪文件干净"
  fi
  echo ""
  echo "  下一步建议："
  echo "    开始拆之前：  tools/rollback-erp-split.sh --tag 0"
  echo "    每批验收通过：tools/rollback-erp-split.sh --tag N"
  echo "    要回退第 N 批起：tools/rollback-erp-split.sh --from-batch N --dry-run"
  exit 0
fi

# ---------------- --tag ----------------
if [ "$MODE" = "tag" ]; then
  case "$BATCH" in
    ''|*[!0-9]*) die "--tag 需要一个非负整数批次号（0 = 拆分前基线）" ;;
  esac
  require_clean_tree

  if [ "$BATCH" = "0" ]; then
    TARGET_TAG="$BASE_TAG"
  else
    TARGET_TAG="${TAG_PREFIX}-b${BATCH}"
  fi

  if git rev-parse -q --verify "refs/tags/$TARGET_TAG" >/dev/null 2>&1; then
    die "tag $TARGET_TAG 已存在 ⇒ 不覆盖（tag 是回退锚点，覆盖了就失去意义）"
  fi

  # 一致性：批次号必须接得上（不能跳号打 tag，否则中间批次没有锚点）
  prev="$(applied_batch)"
  if [ "$BATCH" -ne 0 ] && [ "$BATCH" -ne $((prev + 1)) ]; then
    die "批次号不连续：当前最高批次是 $prev，本次要打 $BATCH ⇒ 跳号了。
  ⇒ 中间批次没有锚点 ⇒ 出事时无法只回退你真正想回退的那一段。
  ⇒ 请按 B1→B6 顺序打 tag。"
  fi

  say "打批次 tag"
  line "·" "目标 tag" "$TARGET_TAG"
  line "·" "指向" "$(git log --oneline -1)"
  if [ "$DRY" = "1" ]; then
    line "—" "dry-run" "未真的打 tag"
    exit 0
  fi
  git tag -a "$TARGET_TAG" -m "erp 拆包批次 $BATCH（验收通过后打）" >/dev/null 2>&1 \
    || die "打 tag 失败"
  line "✓" "已打 tag" "$TARGET_TAG"
  echo ""
  echo "  ⇒ 确认本批已通过 tools/verify-batch-acceptance.sh 后再打 tag。"
  echo "  ⇒ tag 一旦打好就是回退锚点，别手删。"
  exit 0
fi

# ---------------- --from-batch（回退）----------------
case "$BATCH" in
  ''|*[!0-9]*) die "--from-batch 需要一个正整数批次号" ;;
esac
[ "$BATCH" -ge 1 ] || die "--from-batch 需要 >= 1 的批次号"

# 目标 = 第 N-1 批的 tag（N=1 ⇒ base）
if [ "$BATCH" -eq 1 ]; then
  TARGET_TAG="$BASE_TAG"
else
  TARGET_TAG="${TAG_PREFIX}-b$((BATCH - 1))"
fi

say "回退计划"
line "·" "回退范围" "第 ${BATCH} 批及以后全部回退（保留前 $((BATCH - 1)) 批）"
line "·" "目标 tag" "$TARGET_TAG"

if ! git rev-parse -q --verify "refs/tags/$TARGET_TAG" >/dev/null 2>&1; then
  die "找不到 tag $TARGET_TAG ⇒ 没法回退到那个状态。
  ⇒ 确认这个 tag 打过（tools/rollback-erp-split.sh --status）。
  ⇒ 注意：**回退只能回退到某个已打好的批次边界**，不能回退到批次中间。"
fi

TARGET_SHA="$(git rev-parse "$TARGET_TAG^{commit}")"
HEAD_SHA="$(git rev-parse HEAD)"

if [ "$TARGET_SHA" = "$HEAD_SHA" ]; then
  line "✓" "无需回退" "HEAD 已经在 $TARGET_TAG 上"
  exit 0
fi

if ! git merge-base --is-ancestor "$TARGET_SHA" "$HEAD_SHA"; then
  die "$TARGET_TAG 不是 HEAD 的祖先 ⇒ 历史不是线性的，不能用 reset 回退。
  ⇒ 这种情况请人工检查（git log --oneline --graph -20）。"
fi

# 会被丢掉的提交
DROPPED="$(git log --oneline "$TARGET_SHA..$HEAD_SHA" 2>/dev/null)"
DROPPED_N="$(printf '%s\n' "$DROPPED" | grep -c . )"

line "·" "将回退的提交数" "$DROPPED_N"
if [ "$DROPPED_N" -gt 0 ]; then
  printf '%s\n' "$DROPPED" | sed 's/^/       /'
fi

if [ "$REVERT" = "1" ]; then
  line "·" "方式" "revert（倒序生成反向提交，**不改写历史** ⇒ 已推送也安全）"
else
  line "·" "方式" "reset --hard（**改写历史** ⇒ 已推送的分支需要 force-push）"
  if git rev-parse --abbrev-ref --symbolic-full-name '@{u}' >/dev/null 2>&1; then
    line "⚠" "提醒" "当前分支有上游；若已推送，reset 后需要 force-push 或改用 --revert"
  fi
fi

if [ "$DRY" = "1" ]; then
  line "—" "dry-run" "未真的执行"
  echo ""
  echo "  真要执行时去掉 --dry-run，并加 --yes（非交互环境）。"
  exit 0
fi

require_clean_tree
confirm "确认回退到 $TARGET_TAG？" || exit 1

if [ "$REVERT" = "1" ]; then
  say "倒序 revert"
  # 从 HEAD 往回逐个 revert：先取要回退的提交列表（倒序），逐个 revert
  mapfile -t COMMITS < <(git rev-list --reverse "$TARGET_SHA..$HEAD_SHA")
  for c in "${COMMITS[@]}"; do
    line "·" "revert" "$(git log --oneline -1 "$c")"
    git revert --no-edit "$c" >/dev/null 2>&1 || {
      echo "✗ revert 失败（可能有冲突）。当前状态：$(git status --short | head -5)" >&2
      echo "  ⇒ 手工解决冲突后 git revert --continue；或 git revert --abort 放弃。" >&2
      exit 1
    }
  done
  line "✓" "已 revert" "${#COMMITS[@]} 个提交"
else
  say "reset --hard"
  git reset --hard "$TARGET_TAG" >/dev/null 2>&1 || die "reset 失败"
  line "✓" "已回退到" "$TARGET_TAG"
fi

echo ""
say "回退后必做"
line "·" "1" "bash tools/verify-batch-acceptance.sh   ← 确认树自洽（守恒/悬空 import/包结构）"
line "·" "2" "mvn clean test                         ← 后端/QA 跑；surefire 须仍是 51 报告 / 409 用例"
line "·" "3" "真启动一次（tools/verify-boot-smoke.sh）← 确认 Spring 容器起得来"
echo ""
line "·" "若要重新开始" "tools/rollback-erp-split.sh --tag 0"
exit 0
