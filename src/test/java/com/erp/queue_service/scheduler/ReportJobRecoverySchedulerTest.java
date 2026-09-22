package com.erp.queue_service.scheduler;

import com.erp.core.domain.ReportJob;
import com.erp.queue_service.messaging.ReportMessage;
import com.erp.queue_service.messaging.ReportRetryPublisher;
import com.erp.queue_service.notification.ReportSseNotifier;
import com.erp.queue_service.repository.ReportJobRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ReportJobRecoverySchedulerTest {

    @Mock
    private ReportJobRepository reportJobRepository;

    @Mock
    private ReportSseNotifier reportSseNotifier;

    @Mock
    private ReportRetryPublisher retryPublisher;

    @Test
    void resetsStalledJobAndRepublishesItWithOriginalParams() {
        ReportJob job = stalledJob();
        when(reportJobRepository.findStalledJobs(any())).thenReturn(List.of(job));

        ReportJobRecoveryScheduler scheduler = new ReportJobRecoveryScheduler(
                reportJobRepository,
                reportSseNotifier,
                retryPublisher,
                new ObjectMapper()
        );
        ReflectionTestUtils.setField(scheduler, "timeoutMinutes", 30);
        ReflectionTestUtils.setField(scheduler, "maxAttempts", 3);

        scheduler.recoverStalledJobs();

        assertThat(job.getStatus()).isEqualTo("PENDING");
        assertThat(job.getStartedAt()).isNull();
        assertThat(job.getHeartbeatAt()).isNull();
        ArgumentCaptor<ReportMessage> message = ArgumentCaptor.forClass(ReportMessage.class);
        verify(retryPublisher).publish(message.capture());
        assertThat(message.getValue().getJobId()).isEqualTo(job.getId());
        assertThat(message.getValue().getParams())
                .containsEntry("status", "COMPLETED")
                .containsEntry("fromDate", "2026-09-01");

        InOrder commitBeforePublish = inOrder(reportJobRepository, retryPublisher);
        commitBeforePublish.verify(reportJobRepository).saveAndFlush(job);
        commitBeforePublish.verify(retryPublisher).publish(any(ReportMessage.class));
    }

    private static ReportJob stalledJob() {
        ReportJob job = new ReportJob();
        job.setId(UUID.randomUUID());
        job.setModule("POS");
        job.setReportType("POS_ORDER_LIST");
        job.setFormat("EXCEL");
        job.setRequestedBy(UUID.randomUUID());
        job.setBranchId(UUID.randomUUID());
        job.setStatus("PROCESSING");
        job.setAttemptCount(1);
        job.setCreatedAt(Instant.parse("2026-09-21T08:00:00Z"));
        job.setStartedAt(Instant.parse("2026-09-21T08:01:00Z"));
        job.setHeartbeatAt(Instant.parse("2026-09-21T08:02:00Z"));
        job.setRequestParams("{\"status\":\"COMPLETED\",\"fromDate\":\"2026-09-01\"}");
        return job;
    }
}
