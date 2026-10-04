package com.chaeyeongmin.payment_sim.payment.application.approval.transaction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.chaeyeongmin.payment_sim.infra.repository.PaymentAttemptRepository;
import com.chaeyeongmin.payment_sim.infra.repository.dto.AttemptResultUpdateParam;
import com.chaeyeongmin.payment_sim.infra.repository.dto.PaymentAttemptUpdatedRow;
import com.chaeyeongmin.payment_sim.infra.repository.dto.PaymentEventLogInsertParam;
import com.chaeyeongmin.payment_sim.payment.api.approval.ApproveResponse;
import com.chaeyeongmin.payment_sim.payment.application.approval.support.ApprovalEventRecorder;
import com.chaeyeongmin.payment_sim.payment.application.approval.support.ApprovalResponseFactory;
import com.chaeyeongmin.payment_sim.payment.application.approval.transaction.model.ApprovalPrepareResult;
import com.chaeyeongmin.payment_sim.payment.application.event.PaymentEventLogRecorder;
import com.chaeyeongmin.payment_sim.payment.domain.approval.PaymentAttempt;
import com.chaeyeongmin.payment_sim.payment.domain.approval.PaymentFinalStatus;
import com.chaeyeongmin.payment_sim.payment.domain.card.CardIdentity;
import com.chaeyeongmin.payment_sim.payment.domain.event.PaymentEventType;
import com.chaeyeongmin.payment_sim.van.client.dto.VanApproveResponse;
import com.chaeyeongmin.payment_sim.van.client.dto.enums.VanDeclineCode;
import com.chaeyeongmin.payment_sim.van.client.dto.enums.VanResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
import java.util.Optional;

class ApprovalFinalizeTxServiceTest {

    private PaymentAttemptRepository repository;
    private PaymentEventLogRecorder paymentEventLogRecorder;
    private ApprovalFinalizeTxService transactionService;

    @BeforeEach
    void setUp() {
        repository = mock(PaymentAttemptRepository.class);
        paymentEventLogRecorder = mock(PaymentEventLogRecorder.class);
        transactionService = new ApprovalFinalizeTxService(
                repository,
                new ApprovalEventRecorder(paymentEventLogRecorder),
                new ApprovalResponseFactory()
        );
    }

    @Test
    void VAN_승인_응답_update_성공이면_DB_updated_row_기준_APPROVED를_응답한다() {
        String trx = "2376-20260827-9991-0001";
        int attemptSeq = 1;
        ApprovalPrepareResult prepared = prepared(trx, attemptSeq);
        VanApproveResponse vanResponse = vanApprovedResponse(trx, attemptSeq);
        PaymentAttemptUpdatedRow updatedRow = updatedRow(
                trx,
                attemptSeq,
                PaymentFinalStatus.APPROVED,
                "AP-DB-001",
                null,
                "VAN-DB-001"
        );

        when(repository.updateAttemptResult(any())).thenReturn(Optional.of(updatedRow));

        ApproveResponse response = transactionService.applyVanResult(prepared, vanResponse);

        assertThat(response.finalStatus()).isEqualTo(PaymentFinalStatus.APPROVED);
        assertThat(response.approvalNo()).isEqualTo("AP-DB-001");
        assertThat(response.cardSummary().cardBin()).isEqualTo("99999999");
        verify(repository, never()).findByPosTrxAndAttemptSeq(anyString(), anyInt());

        ArgumentCaptor<PaymentEventLogInsertParam> eventCaptor =
                ArgumentCaptor.forClass(PaymentEventLogInsertParam.class);
        verify(paymentEventLogRecorder).record(eventCaptor.capture());
        assertThat(eventCaptor.getValue().eventType()).isEqualTo(PaymentEventType.APPROVE_FINALIZED);
    }

    @Test
    void VAN_응답_update_miss후_DB가_이미_final이면_DB_상태를_정본으로_응답한다() {
        String trx = "2376-20260827-9991-0002";
        int attemptSeq = 1;
        ApprovalPrepareResult prepared = prepared(trx, attemptSeq);

        when(repository.updateAttemptResult(any())).thenReturn(Optional.empty());
        when(repository.findByPosTrxAndAttemptSeq(trx, attemptSeq))
                .thenReturn(Optional.of(paymentAttempt(
                        PaymentFinalStatus.APPROVED.name(),
                        "AP-DB-002",
                        null,
                        "VAN-DB-002"
                )));

        ApproveResponse response = transactionService.applyVanResult(
                prepared,
                vanDeclinedResponse(trx, attemptSeq)
        );

        assertThat(response.finalStatus()).isEqualTo(PaymentFinalStatus.APPROVED);
        assertThat(response.approvalNo()).isEqualTo("AP-DB-002");
        assertThat(response.declineCode()).isNull();
        verify(paymentEventLogRecorder, never()).record(any());
    }

    @Test
    void VAN_응답_update_miss후_DB가_PROCESSING이면_retryLater를_응답한다() {
        String trx = "2376-20260827-9991-0003";
        int attemptSeq = 1;
        ApprovalPrepareResult prepared = prepared(trx, attemptSeq);

        when(repository.updateAttemptResult(any())).thenReturn(Optional.empty());
        when(repository.findByPosTrxAndAttemptSeq(trx, attemptSeq))
                .thenReturn(Optional.of(paymentAttempt(null, null, null, null)));

        ApproveResponse response = transactionService.applyVanResult(
                prepared,
                vanApprovedResponse(trx, attemptSeq)
        );

        assertThat(response.finalStatus()).isEqualTo(PaymentFinalStatus.PROCESSING);
        assertThat(response.cardSummary().cardBin()).isEqualTo(prepared.cardIdentity().cardBin());
        verify(paymentEventLogRecorder, never()).record(any());
    }

    @Test
    void VAN_응답_update_miss후_attempt가_없으면_UNKNOWN_AFTER_UPDATE_MISS를_응답하고_이벤트를_기록한다() {
        String trx = "2376-20260827-9991-0004";
        int attemptSeq = 1;
        ApprovalPrepareResult prepared = prepared(trx, attemptSeq);

        when(repository.updateAttemptResult(any())).thenReturn(Optional.empty());
        when(repository.findByPosTrxAndAttemptSeq(trx, attemptSeq)).thenReturn(Optional.empty());

        ApproveResponse response = transactionService.applyVanResult(
                prepared,
                vanApprovedResponse(trx, attemptSeq)
        );

        assertThat(response.finalStatus()).isEqualTo(PaymentFinalStatus.UNKNOWN_TIMEOUT);
        assertThat(response.declineCode()).isEqualTo("UNKNOWN_AFTER_UPDATE_MISS");

        ArgumentCaptor<PaymentEventLogInsertParam> eventCaptor =
                ArgumentCaptor.forClass(PaymentEventLogInsertParam.class);
        verify(paymentEventLogRecorder).record(eventCaptor.capture());
        assertThat(eventCaptor.getValue().eventType()).isEqualTo(PaymentEventType.APPROVE_UNKNOWN_TIMEOUT);
    }

    @Test
    void VAN_응답_timeout이면_PROCESSING_attempt를_UNKNOWN_TIMEOUT으로_확정한다() {
        String trx = "2376-20260827-9991-0005";
        int attemptSeq = 1;
        ApprovalPrepareResult prepared = prepared(trx, attemptSeq);
        PaymentAttemptUpdatedRow updatedRow = updatedRow(
                trx,
                attemptSeq,
                PaymentFinalStatus.UNKNOWN_TIMEOUT,
                null,
                VanDeclineCode.TIMEOUT.code(),
                null
        );

        when(repository.updateAttemptResult(any())).thenReturn(Optional.of(updatedRow));

        ApproveResponse response = transactionService.markUnknownTimeout(prepared);

        ArgumentCaptor<AttemptResultUpdateParam> captor = ArgumentCaptor.forClass(AttemptResultUpdateParam.class);
        verify(repository).updateAttemptResult(captor.capture());
        verify(repository, never()).findByPosTrxAndAttemptSeq(anyString(), anyInt());

        AttemptResultUpdateParam param = captor.getValue();
        assertThat(param.posTrx()).isEqualTo(trx);
        assertThat(param.attemptSeq()).isEqualTo(attemptSeq);
        assertThat(param.finalStatus()).isEqualTo(PaymentFinalStatus.UNKNOWN_TIMEOUT);
        assertThat(param.approvalNo()).isNull();
        assertThat(param.vanTrxId()).isNull();
        assertThat(param.declineCode()).isEqualTo(VanDeclineCode.TIMEOUT.code());

        assertThat(response.finalStatus()).isEqualTo(PaymentFinalStatus.UNKNOWN_TIMEOUT);
        assertThat(response.declineCode()).isEqualTo(VanDeclineCode.TIMEOUT.code());

        ArgumentCaptor<PaymentEventLogInsertParam> eventCaptor =
                ArgumentCaptor.forClass(PaymentEventLogInsertParam.class);
        verify(paymentEventLogRecorder).record(eventCaptor.capture());
        assertThat(eventCaptor.getValue().eventType()).isEqualTo(PaymentEventType.APPROVE_UNKNOWN_TIMEOUT);
    }

    private ApprovalPrepareResult prepared(String trx, int attemptSeq) {
        return ApprovalPrepareResult.created(
                trx,
                attemptSeq,
                CardIdentity.unknown("41111111", "1111")
        );
    }

    private PaymentAttemptUpdatedRow updatedRow(
            String posTrx,
            int attemptSeq,
            PaymentFinalStatus finalStatus,
            String approvalNo,
            String declineCode,
            String vanTrxId
    ) {
        return new PaymentAttemptUpdatedRow(
                posTrx,
                attemptSeq,
                finalStatus,
                approvalNo,
                declineCode,
                "99999999",
                "9999",
                "VISA",
                vanTrxId
        );
    }

    private PaymentAttempt paymentAttempt(
            String finalStatus,
            String approvalNo,
            String declineCode,
            String vanTrxId
    ) {
        return new PaymentAttempt(
                finalStatus,
                approvalNo,
                declineCode,
                "88888888",
                "8888",
                "MASTER",
                "fingerprint",
                1,
                10000,
                vanTrxId
        );
    }

    private VanApproveResponse vanApprovedResponse(String posTrx, int attemptSeq) {
        return VanApproveResponse.builder()
                .posTrx(posTrx)
                .attemptSeq(attemptSeq)
                .cardBin("41111111")
                .cardLast4("1111")
                .vanResult(VanResult.APPROVED)
                .finalStatus(PaymentFinalStatus.APPROVED)
                .approvalNo("AP-VAN-001")
                .vanTrxId("VAN-001")
                .message("approved")
                .respondedAt(LocalDateTime.now())
                .build();
    }

    private VanApproveResponse vanDeclinedResponse(String posTrx, int attemptSeq) {
        return VanApproveResponse.builder()
                .posTrx(posTrx)
                .attemptSeq(attemptSeq)
                .cardBin("41111111")
                .cardLast4("1111")
                .vanResult(VanResult.DECLINED)
                .finalStatus(PaymentFinalStatus.DECLINED)
                .declineCode(VanDeclineCode.DO_NOT_HONOR)
                .vanTrxId("VAN-002")
                .message("declined")
                .respondedAt(LocalDateTime.now())
                .build();
    }

}
