package com.erp.queue_service.repository;

import com.erp.core.dto.report.pos.OrderExportDto;
import com.erp.core.dto.report.pos.ProductSalesSummaryDto;
import jakarta.persistence.EntityManager;
import jakarta.persistence.TypedQuery;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.domain.SliceImpl;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

/**
 * Read-only, projection-based queries used by POS exports.
 *
 * <p>Filters are appended only when present. Besides producing simpler SQL and
 * better query plans, this avoids untyped {@code NULL} parameters in PostgreSQL
 * expressions such as {@code :fromInstant IS NULL}.</p>
 */
@Repository
@Transactional(readOnly = true)
public class PosReportQueryRepository {

    private static final String ORDER_DETAIL_SELECT = """
            SELECT new com.erp.core.dto.report.pos.OrderExportDto(
                o.id,
                o.orderCode,
                o.createdAt,
                o.branchId,
                b.name,
                o.customerName,
                o.customerPhone,
                o.orderType,
                o.status,
                o.paymentMethod,
                o.paymentStatus,
                o.subtotalAmount,
                o.discountAmount,
                o.deliveryFee,
                o.totalAmount
            )
            FROM Order o
            LEFT JOIN Branch b ON o.branchId = b.id
            WHERE 1 = 1
            """;

    private static final String SALES_SUMMARY_SELECT = """
            SELECT new com.erp.core.dto.report.pos.ProductSalesSummaryDto(
                oi.productCode,
                MAX(oi.productName),
                COALESCE(oi.variantName, ''),
                SUM(oi.quantity),
                SUM(oi.totalPrice),
                SUM(COALESCE(oi.unitCogsAmount, 0) * oi.quantity)
            )
            FROM OrderItem oi, Order o
            WHERE oi.orderId = o.id
              AND (oi.status IS NULL OR UPPER(oi.status) = 'ACTIVE')
            """;

    private final EntityManager entityManager;

    public PosReportQueryRepository(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    public Slice<OrderExportDto> findOrderExportSlice(PosReportCriteria criteria, Pageable pageable) {
        StringBuilder hql = new StringBuilder(ORDER_DETAIL_SELECT);
        appendOrderFilters(hql, criteria);
        hql.append(" ORDER BY o.createdAt ASC, o.id ASC");

        TypedQuery<OrderExportDto> query = entityManager.createQuery(hql.toString(), OrderExportDto.class);
        bindOrderFilters(query, criteria);

        int pageSize = pageable.getPageSize();
        query.setFirstResult(Math.toIntExact(pageable.getOffset()));
        query.setMaxResults(pageSize + 1);

        List<OrderExportDto> fetched = query.getResultList();
        boolean hasNext = fetched.size() > pageSize;
        List<OrderExportDto> content = hasNext
                ? new ArrayList<>(fetched.subList(0, pageSize))
                : fetched;

        return new SliceImpl<>(content, pageable, hasNext);
    }

    public List<ProductSalesSummaryDto> summarizeSalesByProduct(PosReportCriteria criteria) {
        StringBuilder hql = new StringBuilder(SALES_SUMMARY_SELECT);
        appendOrderFilters(hql, criteria);
        hql.append("\nGROUP BY oi.productCode, COALESCE(oi.variantName, '')");
        hql.append("\nORDER BY SUM(oi.totalPrice) DESC");

        TypedQuery<ProductSalesSummaryDto> query = entityManager.createQuery(
                hql.toString(), ProductSalesSummaryDto.class);
        bindOrderFilters(query, criteria);
        return query.getResultList();
    }

    private static void appendOrderFilters(StringBuilder hql, PosReportCriteria criteria) {
        if (criteria.branchId() != null) {
            hql.append(" AND o.branchId = :branchId");
        }
        if (criteria.orderType() != null) {
            hql.append(" AND o.orderType = :orderType");
        }
        if (criteria.status() != null) {
            hql.append(" AND o.status = :status");
        }
        if (criteria.fromInstant() != null) {
            hql.append(" AND o.createdAt >= :fromInstant");
        }
        if (criteria.toInstant() != null) {
            hql.append(" AND o.createdAt < :toInstant");
        }
    }

    private static void bindOrderFilters(TypedQuery<?> query, PosReportCriteria criteria) {
        if (criteria.branchId() != null) {
            query.setParameter("branchId", criteria.branchId());
        }
        if (criteria.orderType() != null) {
            query.setParameter("orderType", criteria.orderType());
        }
        if (criteria.status() != null) {
            query.setParameter("status", criteria.status());
        }
        if (criteria.fromInstant() != null) {
            query.setParameter("fromInstant", criteria.fromInstant());
        }
        if (criteria.toInstant() != null) {
            query.setParameter("toInstant", criteria.toInstant());
        }
    }
}
