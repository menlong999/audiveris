#!/usr/bin/env bash
# build-local.sh — 一键构建 Mac (Apple Silicon arm64) 原生 Audiveris Base 镜像
#
# 用法:
#   ./build-local.sh
#
set -euo pipefail

_log() { echo "[audiveris-build] $*"; }
_sep() { echo "[audiveris-build] ────────────────────────────────────────────"; }
_die() { echo "[audiveris-build] ERROR: $*" >&2; exit 1; }
_ok()  { echo "[audiveris-build] ✓ $*"; }

_sep
_log "正在 Mac 本地构建 Audiveris arm64 原生基础镜像..."

if ! command -v docker >/dev/null 2>&1; then
  _die "未检测到 Docker，请确保 Docker Desktop 已启动并运行。"
fi

# 执行 Docker 构建，指定 Dockerfile.local，并打标为最新本地底座
docker build \
  -f Dockerfile.local \
  -t choral-worker-audiveris-base:latest \
  .

_sep
_ok "本地 arm64 基础底座镜像构建成功！"
_log "镜像名称: choral-worker-audiveris-base:latest"
_log "提示：你现在可以回到 choral_backend 仓库，直接运行以下命令极速启动本地开发环境："
_log "  ./scripts/deploy-worker-local.sh"
_sep
