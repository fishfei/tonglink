#!/usr/bin/env bash
# 服务器上加载镜像并启动轻量模式容器。
set -euo pipefail

tar_file="${1:-dist/tonglink-studio-lite.tar}"

echo "==> 加载镜像 $tar_file"
docker load -i "$tar_file"

echo "==> 启动容器"
docker compose -f docker/docker-compose.lite.yml up -d

echo "==> 服务地址 http://localhost:8080 ，内置管理员账号见登录页一键填充"
docker compose -f docker/docker-compose.lite.yml logs -f tonglink
