package com.chaeyeongmin.payment_sim.api.payment.service.transaction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.chaeyeongmin.payment_sim.api.payment.dto.response.ApproveResponse;
import com.chaeyeongmin.payment_sim.api.payment.event.PaymentEventLogRecorder;
import com.chaeyeongmin.payment_sim.api.payment.service.BinCatalogService;
import com.chaeyeongmin.payment_sim.api.payment.service.transaction.model.PaymentApprovalPrepareResult;
import com.chaeyeongmin.payment_sim.domain.model.CardIdentity;
import com.chaeyeongmin.payment_sim.domain.policy.PaymentEventType;
import com.chaeyeongmin.payment_sim.domain.policy.card.CardFingerprintPolicy;
import com.chaeyeongmin.payment_sim.domain.status.PaymentFinalStatus;
import com.chaeyeongmin.payment_sim.infra.repository.PaymentAttemptRepository;
import com.chaeyeongmin.payment_sim.infra.repository.PaymentExternalInfoRepository;
import com.chaeyeongmin.payment_sim.infra.repository.dto.AttemptResultUpdateParam;
import com.chaeyeongmin.payment_sim.infra.repository.dto.PaymentAttemptUpdatedRow;
import com.chaeyeongmin.payment_sim.infra.repository.dto.PaymentEventLogInsertParam;
import com.chaeyeongmin.payment_sim.van.client.dto.enums.VanDeclineCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Optional;

class PaymentApprovalTransactionServiceTest {

    private PaymentAttemptRepository repository;
    private PaymentExternalInfoRepository infoRepository;
    private PaymentApprovalTransactionService transactionService;
    private PaymentEventLogRecorder paymentEventLogRecorder;

    @BeforeEach
    void setUp() {
        repository = mock(PaymentAttemptRepository.class);
        infoRepository = mock(PaymentExternalInfoRepository.class);
        paymentEventLogRecorder = mock(PaymentEventLogRecorder.class);

        transactionService = new PaymentApprovalTransactionService(
                mock(BinCatalogService.class),
                repository,
                infoRepository,
                mock(CardFingerprintPolicy.class),
                paymentEventLogRecorder
        );
    }

    @Test
    void VAN_응답_timeout이면_PROCESSING_attempt를_UNKNOWN_TIMEOUT으로_확정한다() {
        // given
        String trx = "2376-20260827-9991-0001";
        int attemptSeq = 1;

        CardIdentity cardIdentity =
                CardIdentity.unknown("41111111", "1111");

        PaymentApprovalPrepareResult prepared =
                PaymentApprovalPrepareResult.created(
                        trx,
                        attemptSeq,
                        cardIdentity
                );

        PaymentAttemptUpdatedRow updatedRow = updatedRowUnknownTimeout(
                trx,
                attemptSeq,
                "99999999",
                "9999"
        );

        when(repository.updateAttemptResult(any())).thenReturn(Optional.of(updatedRow));

        // when
        ApproveResponse response = transactionService.finalizeUnknownTimeout(prepared);

        // then
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

        ArgumentCaptor<PaymentEventLogInsertParam> eventCaptor = ArgumentCaptor.forClass(PaymentEventLogInsertParam.class);
        verify(paymentEventLogRecorder).record(eventCaptor.capture());
        PaymentEventLogInsertParam event = eventCaptor.getValue();
        assertThat(event.eventType()).isEqualTo(PaymentEventType.APPROVE_UNKNOWN_TIMEOUT);
    }

    @Test
    void request_not_sent이면_잠근_PROCESSING_attempt와_external_info를_함께_정리한다() {
        PaymentApprovalPrepareResult prepared = PaymentApprovalPrepareResult.created(
                "2376-20260828-9991-0001",
                2,
                CardIdentity.unknown("41111111", "1111")
        );
        when(repository.lockProcessingAttemptForCleanup(prepared.posTrx(), prepared.attemptSeq()))
                .thenReturn(Optional.of(prepared.attemptSeq()));
        when(infoRepository.deleteByPosTrxAndAttemptSeq(prepared.posTrx(), prepared.attemptSeq()))
                .thenReturn(1);
        when(repository.deleteProcessingAttempt(prepared.posTrx(), prepared.attemptSeq()))
                .thenReturn(1);

        transactionService.cleanupRequestNotSent(prepared);

        verify(infoRepository).deleteByPosTrxAndAttemptSeq(prepared.posTrx(), prepared.attemptSeq());
        verify(repository).deleteProcessingAttempt(prepared.posTrx(), prepared.attemptSeq());
        verify(repository, never()).insertAttemptSeq(anyString());
    }

    @Test
    void request_not_sent정리_시점에_더는_PROCESSING이_아니면_삭제하지_않는다() {
        PaymentApprovalPrepareResult prepared = PaymentApprovalPrepareResult.created(
                "2376-20260828-9991-0002",
                1,
                CardIdentity.unknown("41111111", "1111")
        );
        when(repository.lockProcessingAttemptForCleanup(prepared.posTrx(), prepared.attemptSeq()))
                .thenReturn(Optional.empty());

        transactionService.cleanupRequestNotSent(prepared);

        verifyNoInteractions(infoRepository);
        verify(repository, never()).deleteProcessingAttempt(anyString(), anyInt());
    }

    private PaymentAttemptUpdatedRow updatedRowUnknownTimeout(
            String posTrx,
            int attemptSeq,
            String cardBin,
            String cardLast4
    ) {
        // VAN 응답을 받지 못해 UNKNOWN_TIMEOUT으로 저장된 뒤
        // PAYMENT_ATTEMPT UPDATE ... RETURNING이 돌려준 row를 흉내낸다.
        return new PaymentAttemptUpdatedRow(
                posTrx,
                attemptSeq,
                PaymentFinalStatus.UNKNOWN_TIMEOUT,
                null,
                VanDeclineCode.TIMEOUT.code(),
                cardBin,
                cardLast4,
                "VISA",
                null
        );

    }

}
