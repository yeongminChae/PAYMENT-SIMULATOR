package com.chaeyeongmin.payment_sim.api.payment.service.transaction;

import com.chaeyeongmin.payment_sim.api.payment.dto.enums.CancelResultStatus;
import com.chaeyeongmin.payment_sim.api.payment.dto.response.CancelResponse;
import com.chaeyeongmin.payment_sim.api.payment.service.support.CancelEventRecorder;
import com.chaeyeongmin.payment_sim.api.payment.service.support.CancelResponseFactory;
import com.chaeyeongmin.payment_sim.domain.model.PaymentCancel;
import com.chaeyeongmin.payment_sim.domain.policy.CancelStatus;
import com.chaeyeongmin.payment_sim.domain.policy.cancel.CancelCardVerificationPolicy;
import com.chaeyeongmin.payment_sim.infra.repository.PaymentAttemptRepository;
import com.chaeyeongmin.payment_sim.infra.repository.PaymentCancelRepository;
import com.chaeyeongmin.payment_sim.infra.repository.dto.CancelResultUpdateParam;
import com.chaeyeongmin.payment_sim.van.client.dto.VanInquiryResponse;
import com.chaeyeongmin.payment_sim.van.client.dto.VanInquiryResultCode;
import com.chaeyeongmin.payment_sim.van.client.dto.VanInquiryTargetType;
import com.chaeyeongmin.payment_sim.van.client.tcp.protocol.inquiry.VanInquiryStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Cancel inquiry 최종 저장 transaction의 동시성 수렴 테스트.
 *
 * <p>
 * updateUnknownTimeoutToFinal이 0 row를 반환하는 상황은 보통 같은 UNKNOWN_TIMEOUT row를
 * 다른 요청이 먼저 확정했다는 뜻이다. 이때 기존 재취소 응답 규칙을 쓰지 않고,
 * 현재 DB 상태를 inquiry 응답으로 그대로 돌려주는지가 핵심이다.
 */
class PaymentCancelTransactionServiceCancelInquiryTest {

    private static final String CANCEL_POS_TRX = "2376-20260903-9991-2001";
    private static final String ORIGINAL_POS_TRX = "2376-20260903-9991-1001";
    private static final int ORIGINAL_ATTEMPT_SEQ = 1;
    private static final String CANCEL_APPROVAL_NO = "CANCEL-APPROVAL-0001";

    private PaymentCancelTransactionService transactionService;
    private PaymentCancelRepository cancelRepository;

    @BeforeEach
    void setUp() {
        cancelRepository = mock(PaymentCancelRepository.class);
        transactionService = new PaymentCancelTransactionService(
                cancelRepository,
                mock(PaymentAttemptRepository.class),
                mock(CancelCardVerificationPolicy.class),
                new CancelResponseFactory(),
                mock(CancelEventRecorder.class)
        );
    }

    @Test
    @DisplayName("CANCEL inquiry update miss면 posTrx로 재조회하고 이미 CANCELLED면 CANCELLED 응답을 반환한다")
    void finalizeCancelInquiry_updateEmptyAndRereadCancelled_shouldReturnCancelled() {
        PaymentCancel unknownTimeout = cancel(CancelStatus.UNKNOWN_TIMEOUT, null, "TIMEOUT");
        PaymentCancel rereadCancelled = cancel(CancelStatus.CANCELLED, CANCEL_APPROVAL_NO, null);

        when(cancelRepository.updateUnknownTimeoutToFinal(any(CancelResultUpdateParam.class)))
                .thenReturn(Optional.empty());
        when(cancelRepository.findByPosTrx(CANCEL_POS_TRX))
                .thenReturn(Optional.of(rereadCancelled));

        CancelResponse response = transactionService.finalizeCancelInquiry(
                unknownTimeout,
                vanCancelledResponse()
        );

        assertEquals(CancelResultStatus.CANCELLED, response.cancelStatus());
        assertEquals(CANCEL_APPROVAL_NO, response.cancelApprovalNo());
        verify(cancelRepository).updateUnknownTimeoutToFinal(any(CancelResultUpdateParam.class));
        verify(cancelRepository).findByPosTrx(CANCEL_POS_TRX);
    }

    private PaymentCancel cancel(
            CancelStatus status,
            String cancelApprovalNo,
            String declineCode
    ) {
        return new PaymentCancel(
                CANCEL_POS_TRX,
                ORIGINAL_POS_TRX,
                ORIGINAL_ATTEMPT_SEQ,
                status,
                "VAN-CANCEL-INQUIRY-0001",
                cancelApprovalNo,
                declineCode
        );
    }

    private VanInquiryResponse vanCancelledResponse() {
        return VanInquiryResponse.builder()
                .targetType(VanInquiryTargetType.CANCEL)
                .targetTrxNo(CANCEL_POS_TRX)
                .targetAttemptSeq(null)
                .resultCode(VanInquiryResultCode.SUCCESS)
                .status(VanInquiryStatus.CANCELLED)
                .vanTrxId("VAN-CANCEL-INQUIRY-0001")
                .approvalNo(null)
                .cancelApprovalNo(CANCEL_APPROVAL_NO)
                .declineCode(null)
                .message("CANCELLED")
                .respondedAt(LocalDateTime.now())
                .build();
    }
}
