#!/usr/bin/env bash
# ==============================================================================
# SCRIPT ROLLBACK THỦ CÔNG CHO QUEUE SERVICE
# Sử dụng khi cần khôi phục lại phiên bản trước đó ngay lập tức
#
# Cách dùng:
#   bash deploy/scripts/rollback.sh [optional_target_tag] [optional_image_name]
# ==============================================================================

set -eo pipefail

PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
STATE_FILE="${PROJECT_DIR}/.last-stable-tag"
STATE_IMAGE_FILE="${PROJECT_DIR}/.last-stable-image"
COMPOSE_FILE="${PROJECT_DIR}/deploy/docker/queue.yml"

cd "${PROJECT_DIR}"

TARGET_TAG=${1:-""}
TARGET_IMAGE=${2:-""}

if [ -z "${TARGET_TAG}" ]; then
  if [ -f "${STATE_FILE}" ]; then
    TARGET_TAG=$(cat "${STATE_FILE}")
  else
    echo "❌ Không tìm thấy file lưu mốc stable (${STATE_FILE})."
    echo "👉 Vui lòng chỉ định tag cần rollback: bash $0 <tag_name>"
    exit 1
  fi
fi

if [ -z "${TARGET_IMAGE}" ]; then
  if [ -f "${STATE_IMAGE_FILE}" ]; then
    TARGET_IMAGE=$(cat "${STATE_IMAGE_FILE}")
  else
    TARGET_IMAGE="ghcr.io/f-b-chain-erp/erp-queue"
  fi
fi

DETECTED_NETWORK=$(docker inspect erp-rabbitmq --format '{{range $k, $v := .NetworkSettings.Networks}}{{$k}}{{end}}' 2>/dev/null || true)
if [ -z "${DETECTED_NETWORK}" ]; then
  DETECTED_NETWORK=$(docker inspect erp-backend --format '{{range $k, $v := .NetworkSettings.Networks}}{{$k}}{{end}}' 2>/dev/null || true)
fi
ERP_NETWORK="${DETECTED_NETWORK:-backend-service_default}"

echo "=========================================================="
echo "  🔄 BẮT ĐẦU ROLLBACK QUEUE SERVICE"
echo "  Mục tiêu Rollback: ${TARGET_IMAGE}:${TARGET_TAG}"
echo "  Network          : ${ERP_NETWORK}"
echo "=========================================================="

ERP_NETWORK="${ERP_NETWORK}" IMAGE_NAME="${TARGET_IMAGE}" IMAGE_TAG="${TARGET_TAG}" \
  docker compose -f "${COMPOSE_FILE}" up -d --force-recreate queue-service

echo ""
echo "▶ Kiểm tra trạng thái sau rollback..."
sleep 5
docker compose -f "${COMPOSE_FILE}" ps queue-service

echo ""
echo "=========================================================="
echo "  ✅ ĐÃ HOÀN TẤT ROLLBACK VỀ: ${TARGET_IMAGE}:${TARGET_TAG}"
echo "  Xem logs: docker logs -f erp-queue"
echo "=========================================================="
