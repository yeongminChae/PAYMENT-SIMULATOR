package com.chaeyeongmin.payment_sim.van.client.tcp;

import org.junit.jupiter.api.Test;
import org.springframework.integration.support.MessageBuilder;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessageHandlingException;

import java.io.UncheckedIOException;
import java.net.ConnectException;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SpringIntegrationVanTcpClientExceptionClassificationTest {

    @Test
    void root_cause만_ConnectException인_일반_MessageHandlingException은_변환하지_않는다() {
        MessageChannel channel = mock(MessageChannel.class);
        Message<byte[]> failedMessage = MessageBuilder.withPayload(new byte[]{1}).build();
        MessageHandlingException handlingException = new MessageHandlingException(
                failedMessage,
                "failed outside TcpNetClientConnectionFactory connect",
                new UncheckedIOException(new ConnectException("connection failed"))
        );

        when(channel.send(any(Message.class), anyLong())).thenThrow(handlingException);

        VanTcpClient client = new SpringIntegrationVanTcpClient(channel, 1000);

        assertThatThrownBy(() -> client.send(new byte[]{1}))
                .isSameAs(handlingException);
    }
}
