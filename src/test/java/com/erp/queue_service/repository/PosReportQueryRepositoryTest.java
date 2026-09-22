package com.erp.queue_service.repository;

import com.erp.core.dto.report.pos.OrderExportDto;
import com.erp.core.dto.report.pos.ProductSalesSummaryDto;
import jakarta.persistence.EntityManager;
import jakarta.persistence.TypedQuery;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Slice;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PosReportQueryRepositoryTest {

    @Mock
    private EntityManager entityManager;

    @Mock
    private TypedQuery<OrderExportDto> orderQuery;

    @Mock
    private TypedQuery<ProductSalesSummaryDto> summaryQuery;

    private PosReportQueryRepository repository;

    @BeforeEach
    void setUp() {
        repository = new PosReportQueryRepository(entityManager);
    }

    @Test
    void doesNotRenderOrBindMissingFilters() {
        when(entityManager.createQuery(anyString(), eq(OrderExportDto.class))).thenReturn(orderQuery);
        when(orderQuery.getResultList()).thenReturn(List.of());

        PosReportCriteria criteria = new PosReportCriteria(null, null, null, null, null);
        repository.findOrderExportSlice(criteria, PageRequest.of(0, 20));

        ArgumentCaptor<String> hql = ArgumentCaptor.forClass(String.class);
        verify(entityManager).createQuery(hql.capture(), eq(OrderExportDto.class));

        assertThat(hql.getValue())
                .doesNotContain("IS NULL")
                .doesNotContain(":branchId", ":orderType", ":status", ":fromInstant", ":toInstant")
                .contains("ORDER BY o.createdAt ASC, o.id ASC");
        verify(orderQuery, never()).setParameter(anyString(), org.mockito.ArgumentMatchers.<Object>any());
    }

    @Test
    void rendersAndBindsOnlyPresentFiltersAndUsesLookAheadPagination() {
        when(entityManager.createQuery(anyString(), eq(OrderExportDto.class))).thenReturn(orderQuery);
        OrderExportDto first = orderDto(UUID.randomUUID(), "ORD-001");
        OrderExportDto lookAhead = orderDto(UUID.randomUUID(), "ORD-002");
        when(orderQuery.getResultList()).thenReturn(List.of(first, lookAhead));

        UUID branchId = UUID.randomUUID();
        Instant fromInstant = Instant.parse("2026-09-01T00:00:00Z");
        PosReportCriteria criteria = new PosReportCriteria(
                branchId, "DELIVERY", null, fromInstant, null);

        Slice<OrderExportDto> result = repository.findOrderExportSlice(criteria, PageRequest.of(2, 1));

        ArgumentCaptor<String> hql = ArgumentCaptor.forClass(String.class);
        verify(entityManager).createQuery(hql.capture(), eq(OrderExportDto.class));
        assertThat(hql.getValue())
                .contains("o.branchId = :branchId")
                .contains("o.orderType = :orderType")
                .contains("o.createdAt >= :fromInstant")
                .doesNotContain(":status", ":toInstant", "IS NULL");
        verify(orderQuery).setParameter("branchId", branchId);
        verify(orderQuery).setParameter("orderType", "DELIVERY");
        verify(orderQuery).setParameter("fromInstant", fromInstant);
        verify(orderQuery).setFirstResult(2);
        verify(orderQuery).setMaxResults(2);
        assertThat(result.getContent()).containsExactly(first);
        assertThat(result.hasNext()).isTrue();
    }

    @Test
    void separatesDynamicFiltersFromSalesSummaryGrouping() {
        when(entityManager.createQuery(anyString(), eq(ProductSalesSummaryDto.class))).thenReturn(summaryQuery);
        when(summaryQuery.getResultList()).thenReturn(List.of());

        Instant toInstant = Instant.parse("2026-10-01T00:00:00Z");
        PosReportCriteria criteria = new PosReportCriteria(null, null, "COMPLETED", null, toInstant);
        repository.summarizeSalesByProduct(criteria);

        ArgumentCaptor<String> hql = ArgumentCaptor.forClass(String.class);
        verify(entityManager).createQuery(hql.capture(), eq(ProductSalesSummaryDto.class));
        assertThat(hql.getValue())
                .contains("o.status = :status")
                .contains("o.createdAt < :toInstant\nGROUP BY")
                .doesNotContain("IS NULL OR o.createdAt");
        verify(summaryQuery).setParameter("status", "COMPLETED");
        verify(summaryQuery).setParameter("toInstant", toInstant);
    }

    private static OrderExportDto orderDto(UUID id, String orderCode) {
        return new OrderExportDto(
                id, orderCode, null, null, null, null, null, null, null,
                null, null, null, null, null, null);
    }
}
