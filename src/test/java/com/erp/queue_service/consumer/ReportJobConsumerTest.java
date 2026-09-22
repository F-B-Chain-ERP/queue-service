package com.erp.queue_service.consumer;

import com.erp.queue_service.handler.ReportJobDispatcher;
import com.erp.queue_service.messaging.ReportMessage;
import com.rabbitmq.client.Channel;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class ReportJobConsumerTest {

    @Test
    void forwardsRedeliveryFlagAndAcknowledgesSuccessfulDispatch() throws Exception {
        ReportJobDispatcher dispatcher = mock(ReportJobDispatcher.class);
        Channel channel = mock(Channel.class);
        ReportJobConsumer consumer = new ReportJobConsumer(dispatcher);
        ReportMessage message = new ReportMessage();
        message.setJobId(UUID.randomUUID());

        consumer.consumeReportJob(message, channel, 42L, true);

        verify(dispatcher).dispatch(message, true);
        verify(channel).basicAck(42L, false);
    }
}
