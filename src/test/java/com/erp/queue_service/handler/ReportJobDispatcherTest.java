package com.erp.queue_service.handler;

import com.erp.core.enums.ExportFormat;
import com.erp.core.report.ReportDataContext;
import com.erp.queue_service.export.ExportStrategy;
import com.erp.queue_service.export.ExportStrategyFactory;
import com.erp.queue_service.messaging.ReportMessage;
import com.erp.queue_service.notification.ReportSseNotifier;
import com.erp.queue_service.service.MinioStorageService;
import com.erp.queue_service.service.ReportJobStateService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ReportJobDispatcherTest {

    @Mock
    private ModuleReportHandler handler;

    @Mock
    private ExportStrategyFactory strategyFactory;

    @Mock
    private ExportStrategy exportStrategy;

    @Mock
    private MinioStorageService minioStorageService;

    @Mock
    private ReportJobStateService reportJobStateService;

    @Mock
    private ReportSseNotifier reportSseNotifier;

    private com.erp.queue_service.metrics.QueueObservabilityMetrics queueMetrics;
    private ReportJobDispatcher dispatcher;

    @BeforeEach
    void setUp() {
        lenient().when(handler.supports("POS")).thenReturn(true);
        queueMetrics = new com.erp.queue_service.metrics.QueueObservabilityMetrics(new io.micrometer.core.instrument.simple.SimpleMeterRegistry());
        dispatcher = new ReportJobDispatcher(
                List.of(handler),
                strategyFactory,
                minioStorageService,
                reportJobStateService,
                reportSseNotifier,
                queueMetrics
        );
    }

    @Test
    @DisplayName("Dispatch thành công: claimJob -> export -> upload -> markDone -> reportDone")
    void testDispatchSuccess() {
        UUID jobId = UUID.randomUUID();
        ReportMessage msg = new ReportMessage(jobId, "POS", "POS_ORDER_LIST", "EXCEL",
                UUID.randomUUID(), UUID.randomUUID(), Collections.emptyMap(), Instant.now());

        when(reportJobStateService.claimJob(jobId, false)).thenReturn(true);

        ReportDataContext context = new ReportDataContext("Title", "Sub", null, null, List.of(), List.of(), null, null);
        when(handler.generateReportData(msg)).thenReturn(context);
        when(handler.getBaseFileName(msg)).thenReturn("BaoCaoDonHang");

        when(strategyFactory.getStrategy(ExportFormat.EXCEL)).thenReturn(exportStrategy);
        when(exportStrategy.export(context)).thenReturn(new byte[]{1, 2, 3});
        when(exportStrategy.getFileExtension()).thenReturn(".xlsx");
        when(exportStrategy.getContentType()).thenReturn("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");

        when(minioStorageService.buildObjectKey(eq(jobId), anyString())).thenReturn("reports/pos/test.xlsx");
        when(minioStorageService.uploadReportWithKey(any(), eq("reports/pos/test.xlsx"), anyString()))
                .thenReturn("http://minio:9000/reports/pos/test.xlsx");

        dispatcher.dispatch(msg);

        verify(reportJobStateService).claimJob(jobId, false);
        verify(reportJobStateService).markDone(eq(jobId), eq("http://minio:9000/reports/pos/test.xlsx"), eq("reports/pos/test.xlsx"));
        verify(reportSseNotifier).reportDone(eq(msg), eq("http://minio:9000/reports/pos/test.xlsx"), anyString());
        verify(reportJobStateService, never()).markFailed(any(), any());
    }

    @Test
    @DisplayName("Bỏ qua khi không thể claim job (ví dụ đã bị CANCELLED)")
    void testDispatchSkippedWhenClaimFails() {
        UUID jobId = UUID.randomUUID();
        ReportMessage msg = new ReportMessage(jobId, "POS", "POS_ORDER_LIST", "EXCEL",
                UUID.randomUUID(), UUID.randomUUID(), Collections.emptyMap(), Instant.now());

        when(reportJobStateService.claimJob(jobId, false)).thenReturn(false);

        dispatcher.dispatch(msg);

        verify(reportJobStateService).claimJob(jobId, false);
        verifyNoInteractions(strategyFactory);
        verifyNoInteractions(minioStorageService);
        verify(reportJobStateService, never()).markDone(any(), any(), any());
        verify(reportJobStateService, never()).markFailed(any(), any());
    }

    @Test
    @DisplayName("Dispatch thất bại: gọi markFailed và thông báo reportFailed qua SSE")
    void testDispatchFailureCallsMarkFailed() {
        UUID jobId = UUID.randomUUID();
        ReportMessage msg = new ReportMessage(jobId, "POS", "POS_ORDER_LIST", "EXCEL",
                UUID.randomUUID(), UUID.randomUUID(), Collections.emptyMap(), Instant.now());

        when(reportJobStateService.claimJob(jobId, false)).thenReturn(true);
        when(handler.generateReportData(msg)).thenThrow(new RuntimeException("Lỗi kết nối cơ sở dữ liệu"));

        assertThatThrownBy(() -> dispatcher.dispatch(msg))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Lỗi xử lý báo cáo: Lỗi kết nối cơ sở dữ liệu");

        verify(reportJobStateService).claimJob(jobId, false);
        verify(reportJobStateService).markFailed(eq(jobId), eq("Lỗi kết nối cơ sở dữ liệu"));
        verify(reportSseNotifier).reportFailed(eq(msg), eq("Lỗi kết nối cơ sở dữ liệu"));
    }
}
