package com.chaeyeongmin.payment_sim.payment.application.approval.transaction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.chaeyeongmin.payment_sim.infra.repository.PaymentAttemptRepository;
import com.chaeyeongmin.payment_sim.infra.repository.PaymentExternalInfoRepository;
import com.chaeyeongmin.payment_sim.infra.repository.dto.AttemptInsertParam;
import com.chaeyeongmin.payment_sim.infra.repository.dto.PaymentEventLogInsertParam;
import com.chaeyeongmin.payment_sim.infra.repository.dto.PaymentExternalInfoInsertParam;
import com.chaeyeongmin.payment_sim.payment.api.approval.ApproveRequest;
import com.chaeyeongmin.payment_sim.payment.api.common.CardInput;
import com.chaeyeongmin.payment_sim.payment.application.approval.support.ApprovalEventRecorder;
import com.chaeyeongmin.payment_sim.payment.application.approval.support.ApprovalResponseFactory;
import com.chaeyeongmin.payment_sim.payment.application.approval.transaction.model.ApprovalPrepareResult;
import com.chaeyeongmin.payment_sim.payment.application.card.service.BinCatalogService;
import com.chaeyeongmin.payment_sim.payment.application.event.PaymentEventLogRecorder;
import com.chaeyeongmin.payment_sim.payment.domain.card.CardFingerprintPolicy;
import com.chaeyeongmin.payment_sim.payment.domain.card.CardIdentity;
import com.chaeyeongmin.payment_sim.payment.domain.event.PaymentEventType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Optional;

class ApprovalPrepareTxServiceTest {

    private PaymentAttemptRepository repository;
    private PaymentExternalInfoRepository infoRepository;
    private PaymentEventLogRecorder paymentEventLogRecorder;
    private BinCatalogService binCatalogService;
    private CardFingerprintPolicy cardFingerprintPolicy;
    private ApprovalPrepareTxService transactionService;

    @BeforeEach
    void setUp() {
        repository = mock(PaymentAttemptRepository.class);
        infoRepository = mock(PaymentExternalInfoRepository.class);
        paymentEventLogRecorder = mock(PaymentEventLogRecorder.class);
        binCatalogService = mock(BinCatalogService.class);
        cardFingerprintPolicy = mock(CardFingerprintPolicy.class);

        transactionService = new ApprovalPrepareTxService(
                binCatalogService,
                repository,
                infoRepository,
                cardFingerprintPolicy,
                new ApprovalEventRecorder(paymentEventLogRecorder),
                new ApprovalResponseFactory()
        );
    }

    @Test
    void 신규_승인이면_PROCESSING_attempt와_external_info를_생성한다() {
        String trx = "2376-20260828-9991-0000";
        ApproveRequest request = new ApproveRequest(
                trx,
                10000,
                new CardInput("4111111111111111", "2812")
        );
        CardIdentity cardIdentity = CardIdentity.unknown("41111111", "1111");

        when(repository.findLatestByPosTrx(trx)).thenReturn(Optional.empty());
        when(repository.insertAttemptSeq(trx)).thenReturn(1);
        when(binCatalogService.identify("41111111", "1111")).thenReturn(cardIdentity);
        when(cardFingerprintPolicy.generate(request.getCard().getPan())).thenReturn("fingerprint");

        ApprovalPrepareResult result = transactionService.prepare(request);

        assertThat(result.isExisting()).isFalse();
        assertThat(result.posTrx()).isEqualTo(trx);
        assertThat(result.attemptSeq()).isEqualTo(1);
        assertThat(result.cardIdentity()).isEqualTo(cardIdentity);

        verify(repository).acquireApprovalSerializationLock(trx);

        ArgumentCaptor<AttemptInsertParam> attemptCaptor = ArgumentCaptor.forClass(AttemptInsertParam.class);
        verify(repository).insertAttempt(attemptCaptor.capture());
        assertThat(attemptCaptor.getValue().posTrx()).isEqualTo(trx);
        assertThat(attemptCaptor.getValue().attemptSeq()).isEqualTo(1);
        assertThat(attemptCaptor.getValue().amount()).isEqualTo(10000);
        assertThat(attemptCaptor.getValue().cardBin()).isEqualTo("41111111");
        assertThat(attemptCaptor.getValue().cardLast4()).isEqualTo("1111");
        assertThat(attemptCaptor.getValue().cardFingerprint()).isEqualTo("fingerprint");

        ArgumentCaptor<PaymentExternalInfoInsertParam> infoCaptor =
                ArgumentCaptor.forClass(PaymentExternalInfoInsertParam.class);
        verify(infoRepository).insert(infoCaptor.capture());
        assertThat(infoCaptor.getValue().posTrx()).isEqualTo(trx);
        assertThat(infoCaptor.getValue().attemptSeq()).isEqualTo(1);
        assertThat(infoCaptor.getValue().maskedCardNo()).isEqualTo("41111111******1111");

        ArgumentCaptor<PaymentEventLogInsertParam> eventCaptor =
                ArgumentCaptor.forClass(PaymentEventLogInsertParam.class);
        verify(paymentEventLogRecorder).record(eventCaptor.capture());
        assertThat(eventCaptor.getValue().eventType()).isEqualTo(PaymentEventType.APPROVE_ATTEMPT_CREATED);
    }

    @Test
    void request_not_sent이면_잠근_PROCESSING_attempt와_external_info를_함께_정리한다() {
        ApprovalPrepareResult prepared = ApprovalPrepareResult.created(
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
        ApprovalPrepareResult prepared = ApprovalPrepareResult.created(
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

}
