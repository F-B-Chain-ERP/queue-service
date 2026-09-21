#!/usr/bin/env bash
# ==============================================================================
# SCRIPT DEPLOY TỰ ĐỘNG CHO QUEUE SERVICE (HỖ TRỢ ROLLBACK TỰ ĐỘNG)
# Sử dụng trong CI/CD hoặc gọi trực tiếp trên server
#
# Cách dùng:
#   bash deploy/scripts/deploy.sh [image_name] [image_tag]
# Ví dụ:
#   bash deploy/scripts/deploy.sh ghcr.io/f-b-chain-erp/erp-queue sha-abc1234
# ==============================================================================

set -eo pipefail

IMAGE_NAME=${1:-"ghcr.io/f-b-chain-erp/erp-queue"}
IMAGE_TAG=${2:-"latest"}
PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
STATE_FILE="${PROJECT_DIR}/.last-stable-tag"
STATE_IMAGE_FILE="${PROJECT_DIR}/.last-stable-image"
COMPOSE_FILE="${PROJECT_DIR}/deploy/docker/queue.yml"

echo "=========================================================="
echo "  🚀 BẮT ĐẦU TRIỂN KHAI QUEUE SERVICE"
echo "  Target Image : ${IMAGE_NAME}:${IMAGE_TAG}"
echo "  Directory    : ${PROJECT_DIR}"
echo "  Timestamp    : $(date '+%Y-%m-%d %H:%M:%S')"
echo "=========================================================="

cd "${PROJECT_DIR}"

# 1. Tự động phát hiện Docker network của hệ thống ERP hiện tại (từ erp-rabbitmq hoặc erp-backend)
DETECTED_NETWORK=$(docker inspect erp-rabbitmq --format '{{range $k, $v := .NetworkSettings.Networks}}{{$k}}{{end}}' 2>/dev/null || true)
if [ -z "${DETECTED_NETWORK}" ]; then
  DETECTED_NETWORK=$(docker inspect erp-backend --format '{{range $k, $v := .NetworkSettings.Networks}}{{$k}}{{end}}' 2>/dev/null || true)
fi

ERP_NETWORK="${DETECTED_NETWORK:-backend-service_default}"
echo "ℹ️  Docker Network được chọn: ${ERP_NETWORK}"

# 2. Ghi nhận image & tag hiện tại đang chạy làm mốc rollback
PREV_CONTAINER_IMAGE=$(docker inspect erp-queue --format='{{.Config.Image}}' 2>/dev/null || true)
if [ -n "${PREV_CONTAINER_IMAGE}" ]; then
  PREV_SAVED_IMAGE="${PREV_CONTAINER_IMAGE%:*}"
  PREV_SAVED_TAG="${PREV_CONTAINER_IMAGE##*:}"
  echo "ℹ️  Container hiện tại đang chạy: ${PREV_CONTAINER_IMAGE}"
elif [ -f "${STATE_FILE}" ]; then
  PREV_SAVED_TAG=$(cat "${STATE_FILE}")
  PREV_SAVED_IMAGE=$(cat "${STATE_IMAGE_FILE}" 2>/dev/null || echo "${IMAGE_NAME}")
  echo "ℹ️  Lấy mốc stable trước đó từ file: ${PREV_SAVED_IMAGE}:${PREV_SAVED_TAG}"
else
  PREV_SAVED_IMAGE=""
  PREV_SAVED_TAG=""
  echo "ℹ️  Không tìm thấy phiên bản trước đó (lần đầu deploy)."
fi

# 3. Pull Docker image mới
echo ""
echo "▶ [1/4] Kéo image mới từ registry..."
docker pull "${IMAGE_NAME}:${IMAGE_TAG}"

# 4. Khởi động container với image mới
echo ""
echo "▶ [2/4] Khởi chạy container erp-queue..."
ERP_NETWORK="${ERP_NETWORK}" IMAGE_NAME="${IMAGE_NAME}" IMAGE_TAG="${IMAGE_TAG}" \
  docker compose -f "${COMPOSE_FILE}" up -d --force-recreate queue-service

# 5. Kiểm tra sức khỏe (Health Check)
echo ""
echo "▶ [3/4] Kiểm tra trạng thái khởi động (Health Check)..."
MAX_RETRIES=20 # 20 * 5s = 100 giây tối đa
COUNT=0
HEALTHY=false

while [ $COUNT -lt $MAX_RETRIES ]; do
  sleep 5
  COUNT=$((COUNT + 1))

  STATUS=$(docker inspect --format='{{json .State.Health.Status}}' erp-queue 2>/dev/null || echo "\"unknown\"")
  RUNNING=$(docker inspect --format='{{json .State.Running}}' erp-queue 2>/dev/null || echo "false")

  echo "  [Thử ${COUNT}/${MAX_RETRIES}] Trạng thái container: running=${RUNNING}, health=${STATUS}"

  if [ "${STATUS}" = "\"healthy\"" ]; then
    HEALTHY=true
    break
  fi

  # Fallback kiểm tra trực tiếp qua curl nếu healthcheck của docker đang starting
  HTTP_CODE=$(curl -s -o /dev/null -w "%{http_code}" http://127.0.0.1:8090/actuator/health 2>/dev/null || echo "000")
  if [ "${HTTP_CODE}" = "200" ]; then
    echo "  [OK] Endpoint http://127.0.0.1:8090/actuator/health phản hồi HTTP 200!"
    HEALTHY=true
    break
  fi

  if [ "${RUNNING}" != "true" ]; then
    echo "❌ Container đã dừng đột ngột!"
    break
  fi
done

# 6. Xử lý kết quả (Success hoặc Tự động Rollback)
echo ""
echo "▶ [4/4] Đánh giá kết quả..."
if [ "${HEALTHY}" = true ]; then
  echo "=========================================================="
  echo "  ✅ DEPLOY THÀNH CÔNG QUEUE SERVICE!"
  echo "  Phiên bản mới: ${IMAGE_NAME}:${IMAGE_TAG} đã hoạt động ổn định."
  echo "=========================================================="

  # Lưu lại mốc stable
  echo "${IMAGE_TAG}" > "${STATE_FILE}"
  echo "${IMAGE_NAME}" > "${STATE_IMAGE_FILE}"

  # Dọn dẹp images cũ không dùng
  docker image prune -f --filter "until=72h" 2>/dev/null || true
  exit 0
else
  echo "=========================================================="
  echo "  ❌ DEPLOY THẤT BẠI! Container không vượt qua Health Check."
  echo "  Xem logs gần nhất:"
  echo "----------------------------------------------------------"
  docker logs --tail 50 erp-queue || true
  echo "=========================================================="

  # Kích hoạt Rollback tự động nếu có phiên bản cũ
  if [ -n "${PREV_SAVED_TAG}" ] && [ "${PREV_SAVED_TAG}" != "${IMAGE_TAG}" ]; then
    echo ""
    echo "⚠️  TIẾN HÀNH ROLLBACK TỰ ĐỘNG VỀ: ${PREV_SAVED_IMAGE}:${PREV_SAVED_TAG}"
    ERP_NETWORK="${ERP_NETWORK}" IMAGE_NAME="${PREV_SAVED_IMAGE}" IMAGE_TAG="${PREV_SAVED_TAG}" \
      docker compose -f "${COMPOSE_FILE}" up -d --force-recreate queue-service
    echo "✅ Rollback hoàn tất. Hệ thống đã được khôi phục về phiên bản trước."
  else
    echo "⚠️  Không có phiên bản trước đó để rollback. Vui lòng kiểm tra logs!"
  fi

  exit 1
fi
