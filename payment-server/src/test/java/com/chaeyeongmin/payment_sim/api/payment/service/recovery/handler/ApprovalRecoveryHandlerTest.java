package com.chaeyeongmin.payment_sim.api.payment.service.recovery.handler;

import com.chaeyeongmin.payment_sim.api.payment.service.RecoveryFinalizationService;
import com.chaeyeongmin.payment_sim.api.payment.service.transaction.model.RecoveryFinalizeResult;
import com.chaeyeongmin.payment_sim.api.payment.service.transaction.model.RecoveryFinalizeResultType;
import com.chaeyeongmin.payment_sim.domain.model.PaymentAttempt;
import com.chaeyeongmin.payment_sim.domain.model.RecoveryTask;
import com.chaeyeongmin.payment_sim.domain.policy.RecoveryStatus;
import com.chaeyeongmin.payment_sim.domain.policy.RecoveryTargetType;
import com.chaeyeongmin.payment_sim.domain.status.PaymentFinalStatus;
import com.chaeyeongmin.payment_sim.infra.repository.PaymentAttemptRepository;
import com.chaeyeongmin.payment_sim.infra.repository.dto.AttemptResultUpdateParam;
import com.chaeyeongmin.payment_sim.van.client.assembler.VanInquiryAssembler;
import com.chaeyeongmin.payment_sim.van.client.dto.VanInquiryRequest;
import com.chaeyeongmin.payment_sim.van.client.dto.VanInquiryResponse;
import com.chaeyeongmin.payment_sim.van.client.dto.VanInquiryResultCode;
import com.chaeyeongmin.payment_sim.van.client.dto.VanInquiryTargetType;
import com.chaeyeongmin.payment_sim.van.client.dto.enums.VanDeclineCode;
import com.chaeyeongmin.payment_sim.van.client.tcp.protocol.inquiry.VanInquiryStatus;
import com.chaeyeongmin.payment_sim.van.gateway.VanGateway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ApprovalRecoveryHandlerTest {

    private static final String POS_TRX = "2301-20260910-9991-1001";
    private static final int ATTEMPT_SEQ = 1;
    private static final String CARD_LAST4 = "1234";
    private static final String STORED_VAN_TRX_ID = "VAN-APPROVAL-STORED-0001";
    private static final String RESPONSE_VAN_TRX_ID = "VAN-APPROVAL-RECOVERY-0001";
    private static final String APPROVAL_NO = "APPROVAL-0001";

    private PaymentAttemptRepository attemptRepository;
    private VanGateway vanGateway;
    private VanInquiryAssembler vanInquiryAssembler;
    private RecoveryFinalizationService recoveryFinalizationService;
    private ApprovalRecoveryHandler handler;

    @BeforeEach
    void setUp() {
        attemptRepository = mock(PaymentAttemptRepository.class);
        vanGateway = mock(VanGateway.class);
        vanInquiryAssembler = mock(VanInquiryAssembler.class);
        recoveryFinalizationService = mock(RecoveryFinalizationService.class);
        handler = new ApprovalRecoveryHandler(
                attemptRepository,
                vanGateway,
                vanInquiryAssembler,
                recoveryFinalizationService
        );
    }

    @Test
    @DisplayName("targetType은 APPROVAL이다")
    void targetType_shouldReturnApproval() {
        assertThat(handler.targetType()).isEqualTo(RecoveryTargetType.APPROVAL);
    }

    @Test
    @DisplayName("APPROVAL이 아닌 task는 거부한다")
    void handle_nonApprovalTask_shouldReject() {
        RecoveryTask task = task(RecoveryTargetType.CANCEL, ATTEMPT_SEQ);

        assertThatThrownBy(() -> handler.handle(task))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("ApprovalRecoveryHandler requires APPROVAL task");

        verifyNoInteractions(attemptRepository, vanInquiryAssembler, vanGateway, recoveryFinalizationService);
    }

    @Test
    @DisplayName("targetAttemptSeq가 없는 APPROVAL task는 거부한다")
    void handle_missingTargetAttemptSeq_shouldReject() {
        RecoveryTask task = task(RecoveryTargetType.APPROVAL, null);

        assertThatThrownBy(() -> handler.handle(task))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Approval recovery task requires targetAttemptSeq");

        verifyNoInteractions(attemptRepository, vanInquiryAssembler, vanGateway, recoveryFinalizationService);
    }

    @Test
    @DisplayName("target row가 없으면 TARGET_NOT_FOUND이고 VAN을 호출하지 않는다")
    void handle_targetNotFound_shouldReturnTargetNotFound_withoutVanCall() {
        when(attemptRepository.findByPosTrxAndAttemptSeq(POS_TRX, ATTEMPT_SEQ))
                .thenReturn(Optional.empty());

        RecoveryHandlerResult result = handler.handle(approvalTask());

        assertThat(result.resultType()).isEqualTo(RecoveryHandlerResultType.TARGET_NOT_FOUND);
        assertThat(result.observedStatus()).isNull();
        assertThat(result.dbStatus()).isNull();
        verifyNoInteractions(vanInquiryAssembler, vanGateway, recoveryFinalizationService);
    }

    @Test
    @DisplayName("DB가 APPROVED이면 VAN과 finalizer 호출 없이 RESOLVED다")
    void handle_dbApproved_shouldReturnResolved_withoutVanOrFinalizerCall() {
        when(attemptRepository.findByPosTrxAndAttemptSeq(POS_TRX, ATTEMPT_SEQ))
                .thenReturn(Optional.of(attempt(PaymentFinalStatus.APPROVED)));

        RecoveryHandlerResult result = handler.handle(approvalTask());

        assertThat(result.resultType()).isEqualTo(RecoveryHandlerResultType.RESOLVED);
        assertThat(result.dbStatus()).isEqualTo("APPROVED");
        verifyNoInteractions(vanInquiryAssembler, vanGateway, recoveryFinalizationService);
    }

    @Test
    @DisplayName("DB가 DECLINED이면 VAN과 finalizer 호출 없이 RESOLVED다")
    void handle_dbDeclined_shouldReturnResolved_withoutVanOrFinalizerCall() {
        when(attemptRepository.findByPosTrxAndAttemptSeq(POS_TRX, ATTEMPT_SEQ))
                .thenReturn(Optional.of(attempt(PaymentFinalStatus.DECLINED)));

        RecoveryHandlerResult result = handler.handle(approvalTask());

        assertThat(result.resultType()).isEqualTo(RecoveryHandlerResultType.RESOLVED);
        assertThat(result.dbStatus()).isEqualTo("DECLINED");
        verifyNoInteractions(vanInquiryAssembler, vanGateway, recoveryFinalizationService);
    }

    @Test
    @DisplayName("DB가 PROCESSING이면 저장된 attempt 정보로 VAN Inquiry를 수행한다")
    void handle_dbProcessing_shouldCallVanInquiry() {
        VanInquiryRequest request = inquiryRequest();
        when(attemptRepository.findByPosTrxAndAttemptSeq(POS_TRX, ATTEMPT_SEQ))
                .thenReturn(Optional.of(attempt(PaymentFinalStatus.PROCESSING)));
        when(vanInquiryAssembler.getVanInquiryRequest(
                POS_TRX,
                ATTEMPT_SEQ,
                CARD_LAST4,
                STORED_VAN_TRX_ID
        )).thenReturn(request);
        when(vanGateway.inquiry(request)).thenReturn(inquiryResponse(VanInquiryResultCode.NOT_FOUND, null));

        handler.handle(approvalTask());

        verify(vanInquiryAssembler).getVanInquiryRequest(
                POS_TRX,
                ATTEMPT_SEQ,
                CARD_LAST4,
                STORED_VAN_TRX_ID
        );
        verify(vanGateway).inquiry(request);
    }

    @Test
    @DisplayName("DB가 UNKNOWN_TIMEOUT이면 VAN Inquiry를 수행한다")
    void handle_dbUnknownTimeout_shouldCallVanInquiry() {
        VanInquiryRequest request = inquiryRequest();
        when(attemptRepository.findByPosTrxAndAttemptSeq(POS_TRX, ATTEMPT_SEQ))
                .thenReturn(Optional.of(attempt(PaymentFinalStatus.UNKNOWN_TIMEOUT)));
        when(vanInquiryAssembler.getVanInquiryRequest(
                POS_TRX,
                ATTEMPT_SEQ,
                CARD_LAST4,
                STORED_VAN_TRX_ID
        )).thenReturn(request);
        when(vanGateway.inquiry(request)).thenReturn(inquiryResponse(VanInquiryResultCode.NOT_FOUND, null));

        handler.handle(approvalTask());

        verify(vanGateway).inquiry(request);
    }

    @Test
    @DisplayName("VAN NOT_FOUND면 observedStatus를 구분하고 finalizer를 호출하지 않는다")
    void handle_vanNotFound_shouldReturnStillUnresolved_withoutFinalizerCall() {
        stubInquiry(
                PaymentFinalStatus.PROCESSING,
                inquiryResponse(VanInquiryResultCode.NOT_FOUND, null)
        );

        RecoveryHandlerResult result = handler.handle(approvalTask());

        assertThat(result.resultType()).isEqualTo(RecoveryHandlerResultType.STILL_UNRESOLVED);
        assertThat(result.observedStatus()).isEqualTo("NOT_FOUND");
        assertThat(result.dbStatus()).isEqualTo("PROCESSING");
        verifyNoInteractions(recoveryFinalizationService);
    }

    @Test
    @DisplayName("VAN UNKNOWN이면 observedStatus를 구분하고 finalizer를 호출하지 않는다")
    void handle_vanUnknown_shouldReturnStillUnresolved_withoutFinalizerCall() {
        stubInquiry(
                PaymentFinalStatus.UNKNOWN_TIMEOUT,
                inquiryResponse(VanInquiryResultCode.SUCCESS, VanInquiryStatus.UNKNOWN)
        );

        RecoveryHandlerResult result = handler.handle(approvalTask());

        assertThat(result.resultType()).isEqualTo(RecoveryHandlerResultType.STILL_UNRESOLVED);
        assertThat(result.observedStatus()).isEqualTo("UNKNOWN");
        assertThat(result.dbStatus()).isEqualTo("UNKNOWN_TIMEOUT");
        verifyNoInteractions(recoveryFinalizationService);
    }

    @Test
    @DisplayName("VAN APPROVED와 finalizer APPLIED는 RESOLVED로 변환한다")
    void handle_vanApprovedAndFinalizerApplied_shouldReturnResolved() {
        stubInquiry(
                PaymentFinalStatus.PROCESSING,
                inquiryResponse(VanInquiryResultCode.SUCCESS, VanInquiryStatus.APPROVED)
        );
        when(recoveryFinalizationService.finalizeApproval(any()))
                .thenReturn(finalizeResult(RecoveryFinalizeResultType.APPLIED, "APPROVED", "APPROVED"));

        RecoveryHandlerResult result = handler.handle(approvalTask());

        assertThat(result.resultType()).isEqualTo(RecoveryHandlerResultType.RESOLVED);
        assertThat(result.observedStatus()).isEqualTo("APPROVED");
        assertThat(result.dbStatus()).isEqualTo("APPROVED");

        ArgumentCaptor<AttemptResultUpdateParam> captor = ArgumentCaptor.forClass(AttemptResultUpdateParam.class);
        verify(recoveryFinalizationService).finalizeApproval(captor.capture());
        assertThat(captor.getValue().posTrx()).isEqualTo(POS_TRX);
        assertThat(captor.getValue().attemptSeq()).isEqualTo(ATTEMPT_SEQ);
        assertThat(captor.getValue().finalStatus()).isEqualTo(PaymentFinalStatus.APPROVED);
        assertThat(captor.getValue().approvalNo()).isEqualTo(APPROVAL_NO);
        assertThat(captor.getValue().vanTrxId()).isEqualTo(RESPONSE_VAN_TRX_ID);
    }

    @Test
    @DisplayName("VAN DECLINED와 finalizer ALREADY_CONSISTENT는 RESOLVED로 변환한다")
    void handle_vanDeclinedAndFinalizerAlreadyConsistent_shouldReturnResolved() {
        stubInquiry(
                PaymentFinalStatus.UNKNOWN_TIMEOUT,
                inquiryResponse(VanInquiryResultCode.SUCCESS, VanInquiryStatus.DECLINED)
        );
        when(recoveryFinalizationService.finalizeApproval(any()))
                .thenReturn(finalizeResult(
                        RecoveryFinalizeResultType.ALREADY_CONSISTENT,
                        "DECLINED",
                        "DECLINED"
                ));

        RecoveryHandlerResult result = handler.handle(approvalTask());

        assertThat(result.resultType()).isEqualTo(RecoveryHandlerResultType.RESOLVED);

        ArgumentCaptor<AttemptResultUpdateParam> captor = ArgumentCaptor.forClass(AttemptResultUpdateParam.class);
        verify(recoveryFinalizationService).finalizeApproval(captor.capture());
        assertThat(captor.getValue().finalStatus()).isEqualTo(PaymentFinalStatus.DECLINED);
        assertThat(captor.getValue().declineCode()).isEqualTo(VanDeclineCode.DO_NOT_HONOR.code());
        assertThat(captor.getValue().vanTrxId()).isEqualTo(RESPONSE_VAN_TRX_ID);
    }

    @Test
    @DisplayName("finalizer STILL_UNRESOLVED는 handler STILL_UNRESOLVED로 변환한다")
    void handle_finalizerStillUnresolved_shouldMapResult() {
        stubTerminalInquiryAndFinalizer(
                RecoveryFinalizeResultType.STILL_UNRESOLVED,
                "APPROVED",
                "UNKNOWN_TIMEOUT"
        );

        RecoveryHandlerResult result = handler.handle(approvalTask());

        assertThat(result.resultType()).isEqualTo(RecoveryHandlerResultType.STILL_UNRESOLVED);
        assertThat(result.observedStatus()).isEqualTo("APPROVED");
        assertThat(result.dbStatus()).isEqualTo("UNKNOWN_TIMEOUT");
    }

    @Test
    @DisplayName("finalizer TERMINAL_CONFLICT는 handler TERMINAL_CONFLICT로 변환한다")
    void handle_finalizerTerminalConflict_shouldMapResult() {
        stubTerminalInquiryAndFinalizer(
                RecoveryFinalizeResultType.TERMINAL_CONFLICT,
                "APPROVED",
                "DECLINED"
        );

        RecoveryHandlerResult result = handler.handle(approvalTask());

        assertThat(result.resultType()).isEqualTo(RecoveryHandlerResultType.TERMINAL_CONFLICT);
        assertThat(result.observedStatus()).isEqualTo("APPROVED");
        assertThat(result.dbStatus()).isEqualTo("DECLINED");
    }

    @Test
    @DisplayName("finalizer TARGET_NOT_FOUND는 handler TARGET_NOT_FOUND로 변환한다")
    void handle_finalizerTargetNotFound_shouldMapResult() {
        stubTerminalInquiryAndFinalizer(
                RecoveryFinalizeResultType.TARGET_NOT_FOUND,
                "APPROVED",
                null
        );

        RecoveryHandlerResult result = handler.handle(approvalTask());

        assertThat(result.resultType()).isEqualTo(RecoveryHandlerResultType.TARGET_NOT_FOUND);
        assertThat(result.observedStatus()).isEqualTo("APPROVED");
        assertThat(result.dbStatus()).isNull();
    }

    @ParameterizedTest
    @EnumSource(value = VanInquiryStatus.class, names = {"CANCELLED", "CANCEL_DECLINED"})
    @DisplayName("APPROVAL Inquiry의 cancel 계열 VAN status는 명시적으로 거부한다")
    void handle_cancelVanStatus_shouldReject(VanInquiryStatus status) {
        stubInquiry(
                PaymentFinalStatus.PROCESSING,
                inquiryResponse(VanInquiryResultCode.SUCCESS, status)
        );

        assertThatThrownBy(() -> handler.handle(approvalTask()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Unexpected VAN inquiry status for APPROVAL: " + status);

        verifyNoInteractions(recoveryFinalizationService);
    }

    @Test
    @DisplayName("VAN SUCCESS 응답의 status가 null이면 명시적으로 거부한다")
    void handle_successWithNullStatus_shouldReject() {
        stubInquiry(
                PaymentFinalStatus.PROCESSING,
                inquiryResponse(VanInquiryResultCode.SUCCESS, null)
        );

        assertThatThrownBy(() -> handler.handle(approvalTask()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Unexpected VAN inquiry status for APPROVAL: null");

        verifyNoInteractions(recoveryFinalizationService);
    }

    private void stubInquiry(PaymentFinalStatus dbStatus, VanInquiryResponse response) {
        VanInquiryRequest request = inquiryRequest();
        when(attemptRepository.findByPosTrxAndAttemptSeq(POS_TRX, ATTEMPT_SEQ))
                .thenReturn(Optional.of(attempt(dbStatus)));
        when(vanInquiryAssembler.getVanInquiryRequest(
                POS_TRX,
                ATTEMPT_SEQ,
                CARD_LAST4,
                STORED_VAN_TRX_ID
        )).thenReturn(request);
        when(vanGateway.inquiry(request)).thenReturn(response);
    }

    private void stubTerminalInquiryAndFinalizer(
            RecoveryFinalizeResultType resultType,
            String intendedStatus,
            String dbStatus
    ) {
        stubInquiry(
                PaymentFinalStatus.UNKNOWN_TIMEOUT,
                inquiryResponse(VanInquiryResultCode.SUCCESS, VanInquiryStatus.APPROVED)
        );
        when(recoveryFinalizationService.finalizeApproval(any()))
                .thenReturn(finalizeResult(resultType, intendedStatus, dbStatus));
    }

    private RecoveryTask approvalTask() {
        return task(RecoveryTargetType.APPROVAL, ATTEMPT_SEQ);
    }

    private RecoveryTask task(RecoveryTargetType targetType, Integer targetAttemptSeq) {
        LocalDateTime now = LocalDateTime.of(2026, 9, 10, 10, 0);
        return new RecoveryTask(
                1L,
                targetType,
                POS_TRX,
                targetAttemptSeq,
                POS_TRX,
                ATTEMPT_SEQ,
                RecoveryStatus.RUNNING,
                0,
                null,
                "claim-token",
                now.plusMinutes(5),
                now.minusMinutes(5),
                now
        );
    }

    private PaymentAttempt attempt(PaymentFinalStatus status) {
        String finalStatus = status == PaymentFinalStatus.PROCESSING ? null : status.name();
        return new PaymentAttempt(
                finalStatus,
                status == PaymentFinalStatus.APPROVED ? APPROVAL_NO : null,
                status == PaymentFinalStatus.DECLINED ? VanDeclineCode.DO_NOT_HONOR.code() : null,
                "123456",
                CARD_LAST4,
                "VISA",
                "fingerprint",
                ATTEMPT_SEQ,
                10_000,
                STORED_VAN_TRX_ID
        );
    }

    private VanInquiryRequest inquiryRequest() {
        return VanInquiryRequest.builder()
                .targetType(VanInquiryTargetType.APPROVAL)
                .targetTrxNo(POS_TRX)
                .targetAttemptSeq(ATTEMPT_SEQ)
                .vanTrxId(STORED_VAN_TRX_ID)
                .cardLast4(CARD_LAST4)
                .build();
    }

    private VanInquiryResponse inquiryResponse(
            VanInquiryResultCode resultCode,
            VanInquiryStatus status
    ) {
        return VanInquiryResponse.builder()
                .targetType(VanInquiryTargetType.APPROVAL)
                .targetTrxNo(POS_TRX)
                .targetAttemptSeq(ATTEMPT_SEQ)
                .resultCode(resultCode)
                .status(status)
                .vanTrxId(RESPONSE_VAN_TRX_ID)
                .approvalNo(status == VanInquiryStatus.APPROVED ? APPROVAL_NO : null)
                .declineCode(status == VanInquiryStatus.DECLINED ? VanDeclineCode.DO_NOT_HONOR : null)
                .build();
    }

    private RecoveryFinalizeResult finalizeResult(
            RecoveryFinalizeResultType resultType,
            String intendedStatus,
            String dbStatus
    ) {
        return new RecoveryFinalizeResult(resultType, intendedStatus, dbStatus);
    }
}
