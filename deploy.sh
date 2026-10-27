#!/usr/bin/env bash
# 知造 SIRP —— 一键部署脚本（Docker Compose 模式）
# 用法：bash deploy.sh
# 做四件事：检查 Docker → 自动生成 .env（随机数据库密码）→ 拉镜像（失败回退本地构建）→ 起服务并等健康
# 唯一前置：服务器已装 Docker（含 compose 插件）。MySQL 在容器里自动建库，数据存卷，重启自动拉起。
# 国内服务器：bash deploy-cn.sh（本地构建 + Maven 阿里云镜像，绕开 GHCR 拉取）

set -euo pipefail

cd "$(dirname "$0")"

say() { printf '\n\033[1;36m==> %s\033[0m\n' "$*"; }
ok()  { printf '\033[1;32m  完成 %s\033[0m\n' "$*"; }
warn(){ printf '\033[1;33m  注意 %s\033[0m\n' "$*"; }
die() { printf '\033[1;31m  失败 %s\033[0m\n' "$*" >&2; exit 1; }

# ---------- 1. 环境检查 ----------
say "检查 Docker 环境"
command -v docker >/dev/null 2>&1 || die "未找到 docker，请先安装：https://docs.docker.com/engine/install/（国内服务器可用 bash deploy-cn.sh，脚本内含安装引导）"
docker compose version >/dev/null 2>&1 || die "缺少 compose 插件，请安装 docker-compose-plugin"
ok "Docker $(docker --version | awk '{print $3}' | tr -d ,) / compose 就绪"

# ---------- 2. 生成 .env（已存在则复用，不覆盖） ----------
if [ ! -f .env ]; then
  say "生成 .env（随机数据库密码）"
  rand_hex() {
    if command -v openssl >/dev/null 2>&1; then openssl rand -hex 16
    else head -c 16 /dev/urandom | od -An -tx1 | tr -d ' \n'; fi
  }
  {
    echo "# 由 deploy.sh 自动生成于 $(date '+%Y-%m-%d %H:%M:%S')"
    echo "APP_PORT=8080"
    echo "DB_PASSWORD=sirp_$(rand_hex)"
    echo "MAVEN_MIRROR="
  } > .env
  chmod 600 .env
  ok ".env 已生成（含随机数据库密码，权限 600）"
else
  ok ".env 已存在，沿用现有配置"
fi

# ---------- 3. 拉镜像；拉不到就本地构建 ----------
say "尝试拉取预构建镜像（ghcr.io，国内网络可能拉不到）"
if docker compose pull 2>/dev/null; then
  ok "预构建镜像就绪"
  docker compose up -d
else
  warn "预构建镜像拉取失败（首次发布前属正常），改用本地构建（首次需数分钟下载 Maven 依赖）"
  docker compose up -d --build
fi

# ---------- 4. 等待健康并打印账号 ----------
say "等待服务就绪（首次启动含数据库建表和演示数据播种，约 1-3 分钟）"
PORT=$(grep -E '^APP_PORT=' .env | cut -d= -f2 | tr -d '[:space:]')
PORT=${PORT:-8080}
for i in $(seq 1 60); do
  if curl -fsS "http://127.0.0.1:${PORT}/api/system/version" >/dev/null 2>&1; then
    ok "服务已就绪（${i}0 秒内）"
    printf '\n\033[1;32m============================================================\n'
    printf '  知造 SIRP · AI 智慧工厂管理系统 部署完成\n'
    printf '  访问:   http://<服务器IP>:%s\n' "$PORT"
    printf '  账号:   admin（初始密码见下方，首次登录立即修改）\n'
    printf '\n'
    printf '  常用:   docker compose logs -f app\n'
    printf '          docker compose ps\n'
    printf '          docker compose exec app cat /app/data/initial-admin.txt\n'
    printf '============================================================\033[0m\n'
    # 管理员初始密码由应用首次启动随机生成并写在 data 卷里
    PASS=$(docker compose exec -T app cat /app/data/initial-admin.txt 2>/dev/null || true)
    if [ -n "$PASS" ]; then
      printf '\n  %s\n' "$PASS"
      warn "登录后请立刻修改密码，并删除容器内的 initial-admin.txt"
    else
      warn "管理员密码文件还没生成（首次播种中），稍后执行：docker compose exec app cat /app/data/initial-admin.txt"
    fi
    exit 0
  fi
  sleep 5
done

warn "300 秒未就绪，查看日志定位："
docker compose ps || true
docker compose logs --tail 50 app || true
exit 1
