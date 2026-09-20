package com.erp.queue_service.util;

import java.util.UUID;

/**
 * Quy ước kênh Redis Pub/Sub dùng chung giữa queue-service và backend-service.
 *
 * <p><b>Bắt buộc phải khớp với backend:</b> kênh thông báo realtime SSE của backend
 * ({@code com.erp.backend_service.util.RedisKeys##notificationChannel(UUID)}) có dạng
 * {@code "notification:" + accountId}. Queue-service publish vào đúng kênh này để backend
 * {@code NotificationRedisListener} đẩy tiếp qua SSE tới trình duyệt (SseEmitterRegistry).</p>
 */
public final class QueueRedisKeys {

    private static final String NOTIFICATION_CHANNEL_PREFIX = "notification:";

    private QueueRedisKeys() {
    }

    /** Kênh thông báo realtime theo account — phải trùng với {@code RedisKeys.notificationChannel(accountId)} ở backend. */
    public static String notificationChannel(UUID accountId) {
        return NOTIFICATION_CHANNEL_PREFIX + accountId;
    }
}
