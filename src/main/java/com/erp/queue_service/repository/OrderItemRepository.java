package com.erp.queue_service.repository;

import com.erp.core.domain.OrderItem;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * Repository truy cập chi tiết sản phẩm trong đơn hàng — dữ liệu nguồn cho
 * báo cáo tổng hợp doanh thu POS theo sản phẩm/biến thể (kèm COGS, lãi gộp).
 */
public interface OrderItemRepository extends JpaRepository<OrderItem, UUID> {

    /** Lấy toàn bộ chi tiết đơn của một tập hợp đơn hàng (dùng cho grouped query tránh N+1). */
    List<OrderItem> findByOrderIdIn(Collection<UUID> orderIds);

}
