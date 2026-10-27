#!/usr/bin/env bash
# 知造 SIRP —— 国内一键部署脚本（本地构建 + Maven 阿里云镜像）
# 与 deploy.sh 的唯一区别：不走 GHCR 拉取（国内网络拉不到），直接本地构建镜像，
# 并给 Maven 挂阿里云公共仓加速依赖下载。Docker 官方镜像建议先给 daemon 配加速器。
# 用法：bash deploy-cn.sh

set -euo pipefail

cd "$(dirname "$0")"

say() { printf '\n\033[1;36m==> %s\033[0m\n' "$*"; }
ok()  { printf '\033[1;32m  完成 %s\033[0m\n' "$*"; }
warn(){ printf '\033[1;33m  注意 %s\033[0m\n' "$*"; }
die() { printf '\033[1;31m  失败 %s\033[0m\n' "$*" >&2; exit 1; }

# ---------- 1. 环境检查 ----------
say "检查 Docker 环境"
command -v docker >/dev/null 2>&1 || die "未找到 docker。国内安装：
  curl -fsSL https://get.docker.com | bash -s docker --mirror Aliyun
  安装后建议给 daemon 配镜像加速器（/etc/docker/daemon.json 的 registry-mirrors）"
docker compose version >/dev/null 2>&1 || die "缺少 compose 插件，请安装 docker-compose-plugin"
ok "Docker $(docker --version | awk '{print $3}' | tr -d ,) / compose 就绪"

# ---------- 2. 生成 .env（已存在则复用） ----------
if [ ! -f .env ]; then
  say "生成 .env（随机数据库密码 + 国内 Maven 镜像）"
  rand_hex() {
    if command -v openssl >/dev/null 2>&1; then openssl rand -hex 16
    else head -c 16 /dev/urandom | od -An -tx1 | tr -d ' \n'; fi
  }
  {
    echo "# 由 deploy-cn.sh 自动生成于 $(date '+%Y-%m-%d %H:%M:%S')"
    echo "APP_PORT=8080"
    echo "DB_PASSWORD=sirp_$(rand_hex)"
    echo "MAVEN_MIRROR=https://maven.aliyun.com/repository/public"
    echo "NPM_REGISTRY=https://registry.npmmirror.com"
  } > .env
  chmod 600 .env
  ok ".env 已生成（Maven/NPM 镜像已指向国内源）"
else
  ok ".env 已存在，沿用现有配置"
fi

# ---------- 3. 本地构建并启动 ----------
say "本地构建镜像（首次需数分钟：Maven 走阿里云仓下载依赖）"
docker compose up -d --build

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
    printf '\n'
    printf '  常用:   docker compose logs -f app\n'
    printf '          docker compose ps\n'
    printf '          docker compose exec app cat /app/data/initial-admin.txt\n'
    printf '============================================================\033[0m\n'
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
