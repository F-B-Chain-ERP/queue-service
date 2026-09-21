#!/usr/bin/env bash
# ==============================================================================
# Build Docker Image ở Local và chuyển/load lên Server qua SSH
# Chạy tại thư mục gốc monorepo (ERP-UTT) trên máy Local
# Cách dùng: bash queue-service/deploy/scripts/02-build-push.sh [tag] [server_ip] [server_user]
# ==============================================================================

set -e

TAG=${1:-latest}
SERVER_IP=${2:-163.61.72.183}
SERVER_USER=${3:-devops}
IMAGE_NAME="ghcr.io/f-b-chain-erp/erp-queue:$TAG"

echo "=========================================================="
echo "  BUILD & LOAD IMAGE QUEUE SERVICE LÊN SERVER"
echo "  Image: $IMAGE_NAME"
echo "  Server Target: $SERVER_USER@$SERVER_IP"
echo "=========================================================="

if [ -d "queue-service" ] && [ -d "core-model" ]; then
  ROOT_DIR="."
elif [ -d "../queue-service" ] && [ -d "../core-model" ]; then
  ROOT_DIR=".."
elif [ -d "../../queue-service" ] && [ -d "../../core-model" ]; then
  ROOT_DIR="../.."
else
  echo "[!] Vui lòng chạy script từ thư mục root của dự án ERP-UTT!"
  exit 1
fi

echo "▶ 1. Build Docker image từ Local..."
docker build -f queue-service/Dockerfile -t "$IMAGE_NAME" "$ROOT_DIR"

echo "▶ 2. Lưu và truyền trực tiếp image lên Server qua SSH..."
docker save "$IMAGE_NAME" | ssh "$SERVER_USER@$SERVER_IP" "docker load"

echo ""
echo "=========================================================="
echo "  HOÀN THÀNH: Image $IMAGE_NAME đã được load thành công trên server $SERVER_IP!"
echo "=========================================================="
