package com.chaeyeongmin.payment_sim.payment.application.inquiry.transaction;

import com.chaeyeongmin.payment_sim.infra.repository.PaymentAttemptRepository;
import com.chaeyeongmin.payment_sim.infra.repository.PaymentInquiryRepository;
import com.chaeyeongmin.payment_sim.infra.repository.dto.AttemptResultUpdateParam;
import com.chaeyeongmin.payment_sim.infra.repository.dto.PaymentAttemptUpdatedRow;
import com.chaeyeongmin.payment_sim.payment.api.common.CardSummary;
import com.chaeyeongmin.payment_sim.payment.api.inquiry.InquiryResponse;
import com.chaeyeongmin.payment_sim.payment.domain.approval.PaymentAttempt;
import com.chaeyeongmin.payment_sim.payment.domain.approval.PaymentFinalStatus;
import com.chaeyeongmin.payment_sim.van.client.dto.VanInquiryResultCode;
import com.chaeyeongmin.payment_sim.van.client.dto.VanInquiryTargetType;
import com.chaeyeongmin.payment_sim.van.client.dto.VanInquiryResponse;
import com.chaeyeongmin.payment_sim.van.client.dto.enums.VanDeclineCode;
import com.chaeyeongmin.payment_sim.van.client.tcp.protocol.inquiry.VanInquiryStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ApprovalInquiryTxServiceTest {

    private ApprovalInquiryTxService service;
    private PaymentInquiryRepository paymentInquiryRepository;
    private PaymentAttemptRepository paymentAttemptRepository;

    private final String posTrx = "2376-20260215-9991-0201";
    private final int attemptSeq = 1;
    private final CardSummary fallbackCardSummary = new CardSummary("40000000", "0000", "VISA");

    @BeforeEach
    void setUp() {
        paymentInquiryRepository = mock(PaymentInquiryRepository.class);
        paymentAttemptRepository = mock(PaymentAttemptRepository.class);

        service = new ApprovalInquiryTxService(
                paymentInquiryRepository,
                paymentAttemptRepository
        );
    }

    @Test
    void vanApproved_updateSuccess_shouldReturnApprovedFromUpdatedRow() {
        VanInquiryResponse vanResponse = vanInquiryResApproved(posTrx, attemptSeq);
        PaymentAttemptUpdatedRow updatedRow = updatedRowApproved(
                "DB-APPROVAL-0001",
                "99999999",
                "9999"
        );

        when(paymentInquiryRepository.updateUnknownToFinal(any(AttemptResultUpdateParam.class)))
                .thenReturn(Optional.of(updatedRow));

        InquiryResponse response = service.applyResolvedResult(
                posTrx,
                attemptSeq,
                vanResponse,
                fallbackCardSummary
        );

        assertEquals(PaymentFinalStatus.APPROVED, response.finalStatus());
        assertEquals("DB-APPROVAL-0001", response.approvalNo());
        assertEquals("99999999", response.cardSummary().cardBin());
        assertEquals("9999", response.cardSummary().cardLast4());

        verify(paymentInquiryRepository, times(1))
                .updateUnknownToFinal(any(AttemptResultUpdateParam.class));
        verify(paymentAttemptRepository, never())
                .findByPosTrxAndAttemptSeq(posTrx, attemptSeq);
    }

    @Test
    void vanDeclined_updateSuccess_shouldReturnDeclinedFromUpdatedRow() {
        VanInquiryResponse vanResponse = vanInquiryResDeclined(posTrx, attemptSeq);
        PaymentAttemptUpdatedRow updatedRow = updatedRowDeclined(
                "DB_DECLINED",
                "88888888",
                "8881"
        );

        when(paymentInquiryRepository.updateUnknownToFinal(any(AttemptResultUpdateParam.class)))
                .thenReturn(Optional.of(updatedRow));

        InquiryResponse response = service.applyResolvedResult(
                posTrx,
                attemptSeq,
                vanResponse,
                fallbackCardSummary
        );

        assertEquals(PaymentFinalStatus.DECLINED, response.finalStatus());
        assertEquals("DB_DECLINED", response.declineCode());
        assertEquals("88888888", response.cardSummary().cardBin());
        assertEquals("8881", response.cardSummary().cardLast4());

        verify(paymentInquiryRepository, times(1))
                .updateUnknownToFinal(any(AttemptResultUpdateParam.class));
        verify(paymentAttemptRepository, never())
                .findByPosTrxAndAttemptSeq(posTrx, attemptSeq);
    }

    @Test
    void vanApproved_updateMiss_rereadApproved_shouldReturnApprovedFromRereadRow() {
        VanInquiryResponse vanResponse = vanInquiryResApproved(posTrx, attemptSeq);
        PaymentAttempt rereadApproved = paymentAttempt(
                "APPROVED",
                "DB-REREAD-APPROVAL-0001",
                null,
                "77777777",
                "7772",
                "DB-REREAD-VAN-TRX-0001"
        );

        when(paymentInquiryRepository.updateUnknownToFinal(any(AttemptResultUpdateParam.class)))
                .thenReturn(Optional.empty());
        when(paymentAttemptRepository.findByPosTrxAndAttemptSeq(posTrx, attemptSeq))
                .thenReturn(Optional.of(rereadApproved));

        InquiryResponse response = service.applyResolvedResult(
                posTrx,
                attemptSeq,
                vanResponse,
                fallbackCardSummary
        );

        assertEquals(PaymentFinalStatus.APPROVED, response.finalStatus());
        assertEquals("DB-REREAD-APPROVAL-0001", response.approvalNo());
        assertEquals("77777777", response.cardSummary().cardBin());
        assertEquals("7772", response.cardSummary().cardLast4());

        verify(paymentInquiryRepository, times(1))
                .updateUnknownToFinal(any(AttemptResultUpdateParam.class));
        verify(paymentAttemptRepository, times(1))
                .findByPosTrxAndAttemptSeq(posTrx, attemptSeq);
    }

    @Test
    void vanDeclined_updateMiss_rereadDeclined_shouldReturnDeclinedFromRereadRow() {
        VanInquiryResponse vanResponse = vanInquiryResDeclined(posTrx, attemptSeq);
        PaymentAttempt rereadDeclined = paymentAttempt(
                "DECLINED",
                null,
                "DB_REREAD_DECLINED",
                "66666666",
                "6661",
                "DB-REREAD-VAN-TRX-0002"
        );

        when(paymentInquiryRepository.updateUnknownToFinal(any(AttemptResultUpdateParam.class)))
                .thenReturn(Optional.empty());
        when(paymentAttemptRepository.findByPosTrxAndAttemptSeq(posTrx, attemptSeq))
                .thenReturn(Optional.of(rereadDeclined));

        InquiryResponse response = service.applyResolvedResult(
                posTrx,
                attemptSeq,
                vanResponse,
                fallbackCardSummary
        );

        assertEquals(PaymentFinalStatus.DECLINED, response.finalStatus());
        assertEquals("DB_REREAD_DECLINED", response.declineCode());
        assertEquals("66666666", response.cardSummary().cardBin());
        assertEquals("6661", response.cardSummary().cardLast4());

        verify(paymentInquiryRepository, times(1))
                .updateUnknownToFinal(any(AttemptResultUpdateParam.class));
        verify(paymentAttemptRepository, times(1))
                .findByPosTrxAndAttemptSeq(posTrx, attemptSeq);
    }

    @Test
    void updateMiss_rereadMissing_shouldReturnUnknownTimeoutDefenseResponse() {
        VanInquiryResponse vanResponse = vanInquiryResApproved(posTrx, attemptSeq);

        when(paymentInquiryRepository.updateUnknownToFinal(any(AttemptResultUpdateParam.class)))
                .thenReturn(Optional.empty());
        when(paymentAttemptRepository.findByPosTrxAndAttemptSeq(posTrx, attemptSeq))
                .thenReturn(Optional.empty());

        InquiryResponse response = service.applyResolvedResult(
                posTrx,
                attemptSeq,
                vanResponse,
                fallbackCardSummary
        );

        assertEquals(PaymentFinalStatus.UNKNOWN_TIMEOUT, response.finalStatus());
        assertEquals(posTrx, response.posTrx());
        assertEquals(attemptSeq, response.attemptSeq());
        assertEquals(fallbackCardSummary, response.cardSummary());

        verify(paymentInquiryRepository, times(1))
                .updateUnknownToFinal(any(AttemptResultUpdateParam.class));
        verify(paymentAttemptRepository, times(1))
                .findByPosTrxAndAttemptSeq(posTrx, attemptSeq);
    }

    private PaymentAttemptUpdatedRow updatedRowApproved(
            String approvalNo,
            String cardBin,
            String cardLast4
    ) {
        return new PaymentAttemptUpdatedRow(
                posTrx,
                attemptSeq,
                PaymentFinalStatus.APPROVED,
                approvalNo,
                null,
                cardBin,
                cardLast4,
                "VISA",
                posTrx + "-" + String.format("%02d", attemptSeq)
        );
    }

    private PaymentAttemptUpdatedRow updatedRowDeclined(
            String declineCode,
            String cardBin,
            String cardLast4
    ) {
        return new PaymentAttemptUpdatedRow(
                posTrx,
                attemptSeq,
                PaymentFinalStatus.DECLINED,
                null,
                declineCode,
                cardBin,
                cardLast4,
                "VISA",
                posTrx + "-" + String.format("%02d", attemptSeq)
        );
    }

    private PaymentAttempt paymentAttempt(
            String finalStatus,
            String approvalNo,
            String declineCode,
            String cardBin,
            String cardLast4,
            String vanTrxId
    ) {
        return new PaymentAttempt(
                finalStatus,
                approvalNo,
                declineCode,
                cardBin,
                cardLast4,
                "VISA",
                "test-card-fingerprint",
                attemptSeq,
                10000,
                vanTrxId
        );
    }

    private VanInquiryResponse vanInquiryResApproved(String posTrx, int attemptSeq) {
        return VanInquiryResponse.builder()
                .targetType(VanInquiryTargetType.APPROVAL)
                .targetTrxNo(posTrx)
                .targetAttemptSeq(attemptSeq)
                .resultCode(VanInquiryResultCode.SUCCESS)
                .status(VanInquiryStatus.APPROVED)
                .approvalNo("VAN-APPROVAL-0001")
                .cancelApprovalNo(null)
                .declineCode(null)
                .vanTrxId("VAN-INQ-TRX-0001")
                .message("APPROVED_BY_INQUIRY")
                .respondedAt(LocalDateTime.now())
                .build();
    }

    private VanInquiryResponse vanInquiryResDeclined(String posTrx, int attemptSeq) {
        return VanInquiryResponse.builder()
                .targetType(VanInquiryTargetType.APPROVAL)
                .targetTrxNo(posTrx)
                .targetAttemptSeq(attemptSeq)
                .resultCode(VanInquiryResultCode.SUCCESS)
                .status(VanInquiryStatus.DECLINED)
                .approvalNo(null)
                .cancelApprovalNo(null)
                .declineCode(VanDeclineCode.DO_NOT_HONOR)
                .vanTrxId("VAN-INQ-TRX-0002")
                .message("DECLINED_BY_INQUIRY")
                .respondedAt(LocalDateTime.now())
                .build();
    }
}
