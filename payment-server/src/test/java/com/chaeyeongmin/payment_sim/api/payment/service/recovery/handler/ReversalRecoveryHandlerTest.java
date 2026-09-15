package com.chaeyeongmin.payment_sim.api.payment.service.recovery.handler;

import com.chaeyeongmin.payment_sim.api.payment.service.RecoveryFinalizationService;
import com.chaeyeongmin.payment_sim.api.payment.service.transaction.model.RecoveryFinalizeResult;
import com.chaeyeongmin.payment_sim.api.payment.service.transaction.model.RecoveryFinalizeResultType;
import com.chaeyeongmin.payment_sim.domain.model.PaymentReversal;
import com.chaeyeongmin.payment_sim.domain.model.RecoveryTask;
import com.chaeyeongmin.payment_sim.domain.policy.RecoveryStatus;
import com.chaeyeongmin.payment_sim.domain.policy.RecoveryTargetType;
import com.chaeyeongmin.payment_sim.domain.policy.ReversalStatus;
import com.chaeyeongmin.payment_sim.infra.repository.PaymentReversalRepository;
import com.chaeyeongmin.payment_sim.infra.repository.dto.ReversalResultUpdateParam;
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
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ReversalRecoveryHandlerTest {

    private static final String REVERSAL_POS_TRX = "2376-20260915-9991-3001";
    private static final String ORIGINAL_POS_TRX = "2376-20260915-9991-1001";
    private static final int ORIGINAL_ATTEMPT_SEQ = 1;
    private static final String VAN_REVERSAL_TRX_ID = "VAN-REVERSAL-RECOVERY-0001";
    private static final String REVERSAL_APPROVAL_NO = "REVERSAL-APPROVAL-0001";

    private PaymentReversalRepository reversalRepository;
    private VanGateway vanGateway;
    private VanInquiryAssembler vanInquiryAssembler;
    private RecoveryFinalizationService recoveryFinalizationService;
    private ReversalRecoveryHandler handler;

    @BeforeEach
    void setUp() {
        reversalRepository = mock(PaymentReversalRepository.class);
        vanGateway = mock(VanGateway.class);
        vanInquiryAssembler = mock(VanInquiryAssembler.class);
        recoveryFinalizationService = mock(RecoveryFinalizationService.class);
        handler = new ReversalRecoveryHandler(
                reversalRepository,
                vanGateway,
                vanInquiryAssembler,
                recoveryFinalizationService
        );
    }

    @Test
    @DisplayName("targetType은 REVERSAL이다")
    void targetType_shouldReturnReversal() {
        assertThat(handler.targetType()).isEqualTo(RecoveryTargetType.REVERSAL);
    }

    @Test
    @DisplayName("REVERSAL이 아닌 task는 거부한다")
    void handle_nonReversalTask_shouldReject() {
        RecoveryTask task = task(RecoveryTargetType.CANCEL, null, ORIGINAL_POS_TRX, ORIGINAL_ATTEMPT_SEQ);

        assertThatThrownBy(() -> handler.handle(task))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("ReversalRecoveryHandler requires REVERSAL task");

        verifyNoInteractions(reversalRepository, vanInquiryAssembler, vanGateway, recoveryFinalizationService);
    }

    @Test
    @DisplayName("REVERSAL task의 targetAttemptSeq가 non-null이면 거부한다")
    void handle_nonNullTargetAttemptSeq_shouldReject() {
        RecoveryTask task = task(RecoveryTargetType.REVERSAL, 1, ORIGINAL_POS_TRX, ORIGINAL_ATTEMPT_SEQ);

        assertThatThrownBy(() -> handler.handle(task))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Reversal recovery task must not have targetAttemptSeq");

        verifyNoInteractions(reversalRepository, vanInquiryAssembler, vanGateway, recoveryFinalizationService);
    }

    @Test
    @DisplayName("PAYMENT_REVERSAL row가 없으면 외부 호출 없이 TARGET_NOT_FOUND다")
    void handle_targetNotFound_shouldReturnTargetNotFound_withoutVanOrFinalizerCall() {
        when(reversalRepository.findByReversalPosTrx(REVERSAL_POS_TRX)).thenReturn(Optional.empty());

        RecoveryHandlerResult result = handler.handle(reversalTask());

        assertThat(result.resultType()).isEqualTo(RecoveryHandlerResultType.TARGET_NOT_FOUND);
        assertThat(result.observedStatus()).isNull();
        assertThat(result.dbStatus()).isNull();
        verifyNoInteractions(vanInquiryAssembler, vanGateway, recoveryFinalizationService);
    }

    @Test
    @DisplayName("task와 reversal row의 originalPosTrx가 다르면 recovery를 중단한다")
    void handle_originalPosTrxMismatch_shouldReject_withoutVanOrFinalizerCall() {
        when(reversalRepository.findByReversalPosTrx(REVERSAL_POS_TRX))
                .thenReturn(Optional.of(reversal(ReversalStatus.PENDING)));
        RecoveryTask task = task(
                RecoveryTargetType.REVERSAL,
                null,
                ORIGINAL_POS_TRX + "-MISMATCH",
                ORIGINAL_ATTEMPT_SEQ
        );

        assertThatThrownBy(() -> handler.handle(task))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("RECOVERY_REVERSAL_TARGET_IDENTITY_MISMATCH");

        verifyNoInteractions(vanInquiryAssembler, vanGateway, recoveryFinalizationService);
    }

    @Test
    @DisplayName("task와 reversal row의 originalAttemptSeq가 다르면 recovery를 중단한다")
    void handle_originalAttemptSeqMismatch_shouldReject_withoutVanOrFinalizerCall() {
        when(reversalRepository.findByReversalPosTrx(REVERSAL_POS_TRX))
                .thenReturn(Optional.of(reversal(ReversalStatus.PENDING)));
        RecoveryTask task = task(
                RecoveryTargetType.REVERSAL,
                null,
                ORIGINAL_POS_TRX,
                ORIGINAL_ATTEMPT_SEQ + 1
        );

        assertThatThrownBy(() -> handler.handle(task))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("RECOVERY_REVERSAL_TARGET_IDENTITY_MISMATCH");

        verifyNoInteractions(vanInquiryAssembler, vanGateway, recoveryFinalizationService);
    }

    @Test
    @DisplayName("DB가 REVERSED이면 VAN과 finalizer 호출 없이 RESOLVED다")
    void handle_dbReversed_shouldReturnResolved_withoutVanOrFinalizerCall() {
        when(reversalRepository.findByReversalPosTrx(REVERSAL_POS_TRX))
                .thenReturn(Optional.of(reversal(ReversalStatus.REVERSED)));

        RecoveryHandlerResult result = handler.handle(reversalTask());

        assertThat(result.resultType()).isEqualTo(RecoveryHandlerResultType.RESOLVED);
        assertThat(result.observedStatus()).isNull();
        assertThat(result.dbStatus()).isEqualTo("REVERSED");
        verifyNoInteractions(vanInquiryAssembler, vanGateway, recoveryFinalizationService);
    }

    @Test
    @DisplayName("DB가 REVERSAL_DECLINED이면 VAN과 finalizer 호출 없이 RESOLVED다")
    void handle_dbReversalDeclined_shouldReturnResolved_withoutVanOrFinalizerCall() {
        when(reversalRepository.findByReversalPosTrx(REVERSAL_POS_TRX))
                .thenReturn(Optional.of(reversal(ReversalStatus.REVERSAL_DECLINED)));

        RecoveryHandlerResult result = handler.handle(reversalTask());

        assertThat(result.resultType()).isEqualTo(RecoveryHandlerResultType.RESOLVED);
        assertThat(result.observedStatus()).isNull();
        assertThat(result.dbStatus()).isEqualTo("REVERSAL_DECLINED");
        verifyNoInteractions(vanInquiryAssembler, vanGateway, recoveryFinalizationService);
    }

    @Test
    @DisplayName("PENDING에서 VAN NOT_FOUND면 finalizer 호출 없이 STILL_UNRESOLVED다")
    void handle_vanNotFound_shouldReturnStillUnresolved_withoutFinalizerCall() {
        stubInquiry(inquiryResponse(VanInquiryResultCode.NOT_FOUND, VanInquiryStatus.REVERSED));

        RecoveryHandlerResult result = handler.handle(reversalTask());

        assertThat(result.resultType()).isEqualTo(RecoveryHandlerResultType.STILL_UNRESOLVED);
        assertThat(result.observedStatus()).isEqualTo("NOT_FOUND");
        assertThat(result.dbStatus()).isEqualTo("PENDING");
        verify(vanGateway).inquiry(inquiryRequest());
        verifyNoInteractions(recoveryFinalizationService);
    }

    @Test
    @DisplayName("SUCCESS REVERSED를 reversal 승인 결과로 만들어 finalizer에 전달한다")
    void handle_vanReversed_shouldPassReversedParamToFinalizer() {
        stubInquiry(inquiryResponse(VanInquiryResultCode.SUCCESS, VanInquiryStatus.REVERSED));
        when(recoveryFinalizationService.finalizeReversal(any()))
                .thenReturn(finalizeResult(RecoveryFinalizeResultType.APPLIED, "REVERSED", "REVERSED"));

        handler.handle(reversalTask());

        ArgumentCaptor<ReversalResultUpdateParam> captor =
                ArgumentCaptor.forClass(ReversalResultUpdateParam.class);
        verify(recoveryFinalizationService).finalizeReversal(captor.capture());
        ReversalResultUpdateParam intended = captor.getValue();
        assertThat(intended.reversalPosTrx()).isEqualTo(REVERSAL_POS_TRX);
        assertThat(intended.originalPosTrx()).isEqualTo(ORIGINAL_POS_TRX);
        assertThat(intended.originalAttemptSeq()).isEqualTo(ORIGINAL_ATTEMPT_SEQ);
        assertThat(intended.reversalStatus()).isEqualTo(ReversalStatus.REVERSED);
        assertThat(intended.vanReversalTrxId()).isEqualTo(VAN_REVERSAL_TRX_ID);
        assertThat(intended.reversalApprovalNo()).isEqualTo(REVERSAL_APPROVAL_NO);
        assertThat(intended.declineCode()).isNull();
    }

    @Test
    @DisplayName("SUCCESS REVERSAL_DECLINED를 decline 결과로 만들어 finalizer에 전달한다")
    void handle_vanReversalDeclined_shouldPassDeclinedParamToFinalizer() {
        stubInquiry(inquiryResponse(VanInquiryResultCode.SUCCESS, VanInquiryStatus.REVERSAL_DECLINED));
        when(recoveryFinalizationService.finalizeReversal(any()))
                .thenReturn(finalizeResult(
                        RecoveryFinalizeResultType.APPLIED,
                        "REVERSAL_DECLINED",
                        "REVERSAL_DECLINED"
                ));

        handler.handle(reversalTask());

        ArgumentCaptor<ReversalResultUpdateParam> captor =
                ArgumentCaptor.forClass(ReversalResultUpdateParam.class);
        verify(recoveryFinalizationService).finalizeReversal(captor.capture());
        ReversalResultUpdateParam intended = captor.getValue();
        assertThat(intended.reversalPosTrx()).isEqualTo(REVERSAL_POS_TRX);
        assertThat(intended.originalPosTrx()).isEqualTo(ORIGINAL_POS_TRX);
        assertThat(intended.originalAttemptSeq()).isEqualTo(ORIGINAL_ATTEMPT_SEQ);
        assertThat(intended.reversalStatus()).isEqualTo(ReversalStatus.REVERSAL_DECLINED);
        assertThat(intended.vanReversalTrxId()).isEqualTo(VAN_REVERSAL_TRX_ID);
        assertThat(intended.reversalApprovalNo()).isNull();
        assertThat(intended.declineCode()).isEqualTo(VanDeclineCode.ORIGINAL_NOT_REVERSIBLE.code());
    }

    @Test
    @DisplayName("SUCCESS 응답의 targetType이 REVERSAL이 아니면 거부한다")
    void handle_successTargetTypeMismatch_shouldReject_withoutFinalizerCall() {
        stubInquiry(inquiryResponse(
                VanInquiryResultCode.SUCCESS,
                VanInquiryStatus.REVERSED,
                VanInquiryTargetType.CANCEL,
                REVERSAL_POS_TRX,
                null
        ));

        assertInvalidSuccessfulResponse();
    }

    @Test
    @DisplayName("SUCCESS 응답의 targetTrxNo가 다르면 거부한다")
    void handle_successTargetTrxNoMismatch_shouldReject_withoutFinalizerCall() {
        stubInquiry(inquiryResponse(
                VanInquiryResultCode.SUCCESS,
                VanInquiryStatus.REVERSED,
                VanInquiryTargetType.REVERSAL,
                REVERSAL_POS_TRX + "-MISMATCH",
                null
        ));

        assertInvalidSuccessfulResponse();
    }

    @Test
    @DisplayName("SUCCESS REVERSAL 응답의 targetAttemptSeq가 non-null이면 거부한다")
    void handle_successNonNullTargetAttemptSeq_shouldReject_withoutFinalizerCall() {
        stubInquiry(inquiryResponse(
                VanInquiryResultCode.SUCCESS,
                VanInquiryStatus.REVERSED,
                VanInquiryTargetType.REVERSAL,
                REVERSAL_POS_TRX,
                1
        ));

        assertInvalidSuccessfulResponse();
    }

    @Test
    @DisplayName("SUCCESS 응답의 status가 null이면 거부한다")
    void handle_successNullStatus_shouldReject_withoutFinalizerCall() {
        stubInquiry(inquiryResponse(VanInquiryResultCode.SUCCESS, null));

        assertInvalidSuccessfulResponse();
    }

    @Test
    @DisplayName("SUCCESS APPROVED status는 명시적으로 거부한다")
    void handle_approvedStatus_shouldReject_withoutFinalizerCall() {
        stubInquiry(inquiryResponse(VanInquiryResultCode.SUCCESS, VanInquiryStatus.APPROVED));

        assertUnexpectedStatus(VanInquiryStatus.APPROVED);
    }

    @Test
    @DisplayName("SUCCESS CANCELLED status는 명시적으로 거부한다")
    void handle_cancelledStatus_shouldReject_withoutFinalizerCall() {
        stubInquiry(inquiryResponse(VanInquiryResultCode.SUCCESS, VanInquiryStatus.CANCELLED));

        assertUnexpectedStatus(VanInquiryStatus.CANCELLED);
    }

    @Test
    @DisplayName("finalizer APPLIED는 handler RESOLVED로 변환한다")
    void handle_finalizerApplied_shouldMapToResolved() {
        RecoveryHandlerResult result = handleWithFinalizerResult(
                RecoveryFinalizeResultType.APPLIED,
                "REVERSED",
                "REVERSED"
        );

        assertHandlerResult(result, RecoveryHandlerResultType.RESOLVED, "REVERSED", "REVERSED");
    }

    @Test
    @DisplayName("finalizer ALREADY_CONSISTENT는 handler RESOLVED로 변환한다")
    void handle_finalizerAlreadyConsistent_shouldMapToResolved() {
        RecoveryHandlerResult result = handleWithFinalizerResult(
                RecoveryFinalizeResultType.ALREADY_CONSISTENT,
                "REVERSED",
                "REVERSED"
        );

        assertHandlerResult(result, RecoveryHandlerResultType.RESOLVED, "REVERSED", "REVERSED");
    }

    @Test
    @DisplayName("finalizer STILL_UNRESOLVED는 handler STILL_UNRESOLVED로 변환한다")
    void handle_finalizerStillUnresolved_shouldMapResult() {
        RecoveryHandlerResult result = handleWithFinalizerResult(
                RecoveryFinalizeResultType.STILL_UNRESOLVED,
                "REVERSED",
                "PENDING"
        );

        assertHandlerResult(result, RecoveryHandlerResultType.STILL_UNRESOLVED, "REVERSED", "PENDING");
    }

    @Test
    @DisplayName("finalizer TERMINAL_CONFLICT는 handler TERMINAL_CONFLICT로 변환한다")
    void handle_finalizerTerminalConflict_shouldMapResult() {
        RecoveryHandlerResult result = handleWithFinalizerResult(
                RecoveryFinalizeResultType.TERMINAL_CONFLICT,
                "REVERSED",
                "REVERSAL_DECLINED"
        );

        assertHandlerResult(
                result,
                RecoveryHandlerResultType.TERMINAL_CONFLICT,
                "REVERSED",
                "REVERSAL_DECLINED"
        );
    }

    @Test
    @DisplayName("finalizer TARGET_NOT_FOUND는 handler TARGET_NOT_FOUND로 변환한다")
    void handle_finalizerTargetNotFound_shouldMapResult() {
        RecoveryHandlerResult result = handleWithFinalizerResult(
                RecoveryFinalizeResultType.TARGET_NOT_FOUND,
                "REVERSED",
                null
        );

        assertHandlerResult(result, RecoveryHandlerResultType.TARGET_NOT_FOUND, "REVERSED", null);
    }

    private void stubInquiry(VanInquiryResponse response) {
        when(reversalRepository.findByReversalPosTrx(REVERSAL_POS_TRX))
                .thenReturn(Optional.of(reversal(ReversalStatus.PENDING)));
        when(vanInquiryAssembler.getReversalInquiryRequest(REVERSAL_POS_TRX)).thenReturn(inquiryRequest());
        when(vanGateway.inquiry(inquiryRequest())).thenReturn(response);
    }

    private void assertInvalidSuccessfulResponse() {
        assertThatThrownBy(() -> handler.handle(reversalTask()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Invalid VAN inquiry response for REVERSAL recovery");

        verify(recoveryFinalizationService, never()).finalizeReversal(any());
    }

    private void assertUnexpectedStatus(VanInquiryStatus status) {
        assertThatThrownBy(() -> handler.handle(reversalTask()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Unexpected VAN inquiry status for REVERSAL: " + status);

        verify(recoveryFinalizationService, never()).finalizeReversal(any());
    }

    private RecoveryHandlerResult handleWithFinalizerResult(
            RecoveryFinalizeResultType resultType,
            String intendedStatus,
            String dbStatus
    ) {
        stubInquiry(inquiryResponse(VanInquiryResultCode.SUCCESS, VanInquiryStatus.REVERSED));
        when(recoveryFinalizationService.finalizeReversal(any()))
                .thenReturn(finalizeResult(resultType, intendedStatus, dbStatus));

        return handler.handle(reversalTask());
    }

    private void assertHandlerResult(
            RecoveryHandlerResult result,
            RecoveryHandlerResultType resultType,
            String observedStatus,
            String dbStatus
    ) {
        assertThat(result.resultType()).isEqualTo(resultType);
        assertThat(result.observedStatus()).isEqualTo(observedStatus);
        assertThat(result.dbStatus()).isEqualTo(dbStatus);
        verify(recoveryFinalizationService).finalizeReversal(any());
    }

    private RecoveryTask reversalTask() {
        return task(RecoveryTargetType.REVERSAL, null, ORIGINAL_POS_TRX, ORIGINAL_ATTEMPT_SEQ);
    }

    private RecoveryTask task(
            RecoveryTargetType targetType,
            Integer targetAttemptSeq,
            String originalPosTrx,
            int originalAttemptSeq
    ) {
        LocalDateTime now = LocalDateTime.of(2026, 9, 15, 10, 0);
        return new RecoveryTask(
                1L,
                targetType,
                REVERSAL_POS_TRX,
                targetAttemptSeq,
                originalPosTrx,
                originalAttemptSeq,
                RecoveryStatus.RUNNING,
                0,
                null,
                "claim-token",
                now.plusMinutes(5),
                now.minusMinutes(5),
                now
        );
    }

    private PaymentReversal reversal(ReversalStatus status) {
        return new PaymentReversal(
                REVERSAL_POS_TRX,
                ORIGINAL_POS_TRX,
                ORIGINAL_ATTEMPT_SEQ,
                10_000,
                status,
                VAN_REVERSAL_TRX_ID,
                status == ReversalStatus.REVERSED ? REVERSAL_APPROVAL_NO : null,
                status == ReversalStatus.REVERSAL_DECLINED
                        ? VanDeclineCode.ORIGINAL_NOT_REVERSIBLE.code()
                        : null
        );
    }

    private VanInquiryRequest inquiryRequest() {
        return VanInquiryRequest.builder()
                .targetType(VanInquiryTargetType.REVERSAL)
                .targetTrxNo(REVERSAL_POS_TRX)
                .targetAttemptSeq(null)
                .build();
    }

    private VanInquiryResponse inquiryResponse(
            VanInquiryResultCode resultCode,
            VanInquiryStatus status
    ) {
        return inquiryResponse(
                resultCode,
                status,
                VanInquiryTargetType.REVERSAL,
                REVERSAL_POS_TRX,
                null
        );
    }

    private VanInquiryResponse inquiryResponse(
            VanInquiryResultCode resultCode,
            VanInquiryStatus status,
            VanInquiryTargetType targetType,
            String targetTrxNo,
            Integer targetAttemptSeq
    ) {
        return VanInquiryResponse.builder()
                .targetType(targetType)
                .targetTrxNo(targetTrxNo)
                .targetAttemptSeq(targetAttemptSeq)
                .resultCode(resultCode)
                .status(status)
                .vanTrxId(VAN_REVERSAL_TRX_ID)
                .reversalApprovalNo(status == VanInquiryStatus.REVERSED ? REVERSAL_APPROVAL_NO : null)
                .declineCode(status == VanInquiryStatus.REVERSAL_DECLINED
                        ? VanDeclineCode.ORIGINAL_NOT_REVERSIBLE
                        : null)
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
