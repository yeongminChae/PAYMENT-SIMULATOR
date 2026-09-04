package com.chaeyeongmin.van_sim.transaction.tcp;

import com.chaeyeongmin.van_sim.transaction.approval.tcp.ApprovalTcpHandler;
import com.chaeyeongmin.van_sim.transaction.cancel.tcp.CancelTcpHandler;
import com.chaeyeongmin.van_sim.transaction.inquiry.tcp.InquiryTcpHandler;
import com.chaeyeongmin.van_sim.transaction.reversal.tcp.ReversalTcpHandler;
import com.chaeyeongmin.van_sim.transaction.tcp.exception.VanTcpMessageException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class VanTcpMessageDispatcherTest {

    @Mock
    private ApprovalTcpHandler approvalTcpHandler;

    @Mock
    private InquiryTcpHandler inquiryTcpHandler;

    @Mock
    private CancelTcpHandler cancelTcpHandler;

    @Mock
    private ReversalTcpHandler reversalTcpHandler;

    private VanTcpMessageDispatcher dispatcher;

    @BeforeEach
    void setUp() {
        ObjectMapper objectMapper = JsonMapper.builder().build();
        dispatcher = new VanTcpMessageDispatcher(
                objectMapper,
                approvalTcpHandler,
                inquiryTcpHandler,
                cancelTcpHandler,
                reversalTcpHandler
        );
    }

    @Test
    void APPROVAL_payload를_기존_ApprovalTcpHandler로_전달한다() {
        byte[] payload = payload("APPROVAL");
        byte[] expected = "approval-response".getBytes(StandardCharsets.UTF_8);
        when(approvalTcpHandler.handle(payload)).thenReturn(expected);

        byte[] actual = dispatcher.dispatch(payload);

        assertThat(actual).isSameAs(expected);
        verify(approvalTcpHandler).handle(payload);
        verify(inquiryTcpHandler, never()).handle(payload);
        verify(cancelTcpHandler, never()).handle(payload);
        verify(reversalTcpHandler, never()).handle(payload);
    }

    @Test
    void INQUIRY_payload를_InquiryTcpHandler로_전달한다() {
        byte[] payload = payload("INQUIRY");
        byte[] expected = "inquiry-response".getBytes(StandardCharsets.UTF_8);
        when(inquiryTcpHandler.handle(payload)).thenReturn(expected);

        byte[] actual = dispatcher.dispatch(payload);

        assertThat(actual).isSameAs(expected);
        verify(inquiryTcpHandler).handle(payload);
        verify(approvalTcpHandler, never()).handle(payload);
        verify(cancelTcpHandler, never()).handle(payload);
        verify(reversalTcpHandler, never()).handle(payload);
    }

    @Test
    void CANCEL_payload를_CancelTcpHandler로_전달한다() {
        byte[] payload = payload("CANCEL");
        byte[] expected = "cancel-response".getBytes(StandardCharsets.UTF_8);
        when(cancelTcpHandler.handle(payload)).thenReturn(expected);

        byte[] actual = dispatcher.dispatch(payload);

        assertThat(actual).isSameAs(expected);
        verify(cancelTcpHandler).handle(payload);
        verify(approvalTcpHandler, never()).handle(payload);
        verify(inquiryTcpHandler, never()).handle(payload);
        verify(reversalTcpHandler, never()).handle(payload);
    }

    @Test
    void REVERSAL_payload를_ReversalTcpHandler로_전달한다() {
        byte[] payload = payload("REVERSAL");
        byte[] expected = "reversal-response".getBytes(StandardCharsets.UTF_8);
        when(reversalTcpHandler.handle(payload)).thenReturn(expected);

        byte[] actual = dispatcher.dispatch(payload);

        assertThat(actual).isSameAs(expected);
        verify(reversalTcpHandler).handle(payload);
        verify(approvalTcpHandler, never()).handle(payload);
        verify(inquiryTcpHandler, never()).handle(payload);
        verify(cancelTcpHandler, never()).handle(payload);
    }

    @Test
    void 지원하지_않는_messageType이면_명시적으로_실패한다() {
        byte[] payload = payload("UNKNOWN");

        assertThatThrownBy(() -> dispatcher.dispatch(payload))
                .isInstanceOf(VanTcpMessageException.class)
                .hasMessage("VAN_TCP_MESSAGE_TYPE_UNSUPPORTED: UNKNOWN");

        verify(approvalTcpHandler, never()).handle(payload);
        verify(inquiryTcpHandler, never()).handle(payload);
        verify(cancelTcpHandler, never()).handle(payload);
        verify(reversalTcpHandler, never()).handle(payload);
    }

    private static byte[] payload(String messageType) {
        return ("{\"messageType\":\"" + messageType + "\"}")
                .getBytes(StandardCharsets.UTF_8);
    }
}
