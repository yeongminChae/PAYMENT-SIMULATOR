package com.chaeyeongmin.payment_sim.payment.application.approval.support;

import com.chaeyeongmin.payment_sim.payment.api.approval.ApproveResponse;
import com.chaeyeongmin.payment_sim.payment.api.common.CardSummary;
import com.chaeyeongmin.payment_sim.payment.domain.approval.PaymentFinalStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ApprovalResponseFactoryTest {

    private static final String POS_TRX = "2376-20260930-9991-0001";
    private static final int ATTEMPT_SEQ = 1;
    private static final String APPROVAL_NO = "APPROVAL-0001";
    private static final String DECLINE_CODE = "DO_NOT_HONOR";
    private static final CardSummary CARD_SUMMARY =
            new CardSummary("41111111", "1111", "VISA");

    private ApprovalResponseFactory factory;

    @BeforeEach
    void setUp() {
        factory = new ApprovalResponseFactory();
    }

    @Test
    void approved_status는_approved_response로_매핑한다() {
        ApproveResponse response = factory.fromStatus(
                PaymentFinalStatus.APPROVED,
                POS_TRX,
                ATTEMPT_SEQ,
                APPROVAL_NO,
                null,
                CARD_SUMMARY
        );

        assertThat(response.finalStatus()).isEqualTo(PaymentFinalStatus.APPROVED);
        assertThat(response.posTrx()).isEqualTo(POS_TRX);
        assertThat(response.attemptSeq()).isEqualTo(ATTEMPT_SEQ);
        assertThat(response.approvalNo()).isEqualTo(APPROVAL_NO);
        assertThat(response.declineCode()).isNull();
        assertThat(response.cardSummary()).isEqualTo(CARD_SUMMARY);
    }

    @Test
    void declined_status는_declined_response로_매핑한다() {
        ApproveResponse response = factory.fromStatus(
                PaymentFinalStatus.DECLINED,
                POS_TRX,
                ATTEMPT_SEQ,
                null,
                DECLINE_CODE,
                CARD_SUMMARY
        );

        assertThat(response.finalStatus()).isEqualTo(PaymentFinalStatus.DECLINED);
        assertThat(response.approvalNo()).isNull();
        assertThat(response.declineCode()).isEqualTo(DECLINE_CODE);
        assertThat(response.cardSummary()).isEqualTo(CARD_SUMMARY);
    }

    @Test
    void unknown_timeout_status는_unknownTimeout_response로_매핑한다() {
        ApproveResponse response = factory.fromStatus(
                PaymentFinalStatus.UNKNOWN_TIMEOUT,
                POS_TRX,
                ATTEMPT_SEQ,
                null,
                DECLINE_CODE,
                CARD_SUMMARY
        );

        assertThat(response.finalStatus()).isEqualTo(PaymentFinalStatus.UNKNOWN_TIMEOUT);
        assertThat(response.approvalNo()).isNull();
        assertThat(response.declineCode()).isEqualTo(DECLINE_CODE);
        assertThat(response.cardSummary()).isEqualTo(CARD_SUMMARY);
    }

    @Test
    void processing_status는_retryLater_response로_매핑한다() {
        ApproveResponse response = factory.fromStatus(
                PaymentFinalStatus.PROCESSING,
                POS_TRX,
                ATTEMPT_SEQ,
                null,
                null,
                CARD_SUMMARY
        );

        assertThat(response.finalStatus()).isEqualTo(PaymentFinalStatus.PROCESSING);
        assertThat(response.approvalNo()).isNull();
        assertThat(response.declineCode()).isNull();
        assertThat(response.cardSummary()).isEqualTo(CARD_SUMMARY);
    }

}
