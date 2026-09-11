package com.chaeyeongmin.payment_sim.api.payment.service.recovery.handler;

import com.chaeyeongmin.payment_sim.api.payment.service.RecoveryFinalizationService;
import com.chaeyeongmin.payment_sim.api.payment.service.transaction.model.RecoveryFinalizeResult;
import com.chaeyeongmin.payment_sim.api.payment.service.transaction.model.RecoveryFinalizeResultType;
import com.chaeyeongmin.payment_sim.domain.model.PaymentCancel;
import com.chaeyeongmin.payment_sim.domain.model.RecoveryTask;
import com.chaeyeongmin.payment_sim.domain.policy.CancelStatus;
import com.chaeyeongmin.payment_sim.domain.policy.RecoveryStatus;
import com.chaeyeongmin.payment_sim.domain.policy.RecoveryTargetType;
import com.chaeyeongmin.payment_sim.infra.repository.PaymentCancelRepository;
import com.chaeyeongmin.payment_sim.infra.repository.dto.CancelResultUpdateParam;
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

class CancelRecoveryHandlerTest {

    private static final String CANCEL_POS_TRX = "2376-20260910-9991-2001";
    private static final String ORIGINAL_POS_TRX = "2376-20260910-9991-1001";
    private static final int ORIGINAL_ATTEMPT_SEQ = 1;
    private static final String VAN_CANCEL_TRX_ID = "VAN-CANCEL-RECOVERY-0001";
    private static final String CANCEL_APPROVAL_NO = "CANCEL-APPROVAL-0001";

    private PaymentCancelRepository cancelRepository;
    private VanGateway vanGateway;
    private VanInquiryAssembler vanInquiryAssembler;
    private RecoveryFinalizationService recoveryFinalizationService;
    private CancelRecoveryHandler handler;

    @BeforeEach
    void setUp() {
        cancelRepository = mock(PaymentCancelRepository.class);
        vanGateway = mock(VanGateway.class);
        vanInquiryAssembler = mock(VanInquiryAssembler.class);
        recoveryFinalizationService = mock(RecoveryFinalizationService.class);
        handler = new CancelRecoveryHandler(
                cancelRepository,
                vanGateway,
                vanInquiryAssembler,
                recoveryFinalizationService
        );
    }

    @Test
    @DisplayName("targetType은 CANCEL이다")
    void targetType_shouldReturnCancel() {
        assertThat(handler.targetType()).isEqualTo(RecoveryTargetType.CANCEL);
    }

    @Test
    @DisplayName("CANCEL이 아닌 task는 거부한다")
    void handle_nonCancelTask_shouldReject() {
        RecoveryTask task = task(RecoveryTargetType.APPROVAL, ORIGINAL_POS_TRX, ORIGINAL_ATTEMPT_SEQ);

        assertThatThrownBy(() -> handler.handle(task))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("CancelRecoveryHandler requires CANCEL task");

        verifyNoInteractions(cancelRepository, vanInquiryAssembler, vanGateway, recoveryFinalizationService);
    }

    @Test
    @DisplayName("target row가 없으면 TARGET_NOT_FOUND이고 VAN을 호출하지 않는다")
    void handle_targetNotFound_shouldReturnTargetNotFound_withoutVanCall() {
        when(cancelRepository.findByPosTrx(CANCEL_POS_TRX)).thenReturn(Optional.empty());

        RecoveryHandlerResult result = handler.handle(cancelTask());

        assertThat(result.resultType()).isEqualTo(RecoveryHandlerResultType.TARGET_NOT_FOUND);
        assertThat(result.observedStatus()).isNull();
        assertThat(result.dbStatus()).isNull();
        verifyNoInteractions(vanInquiryAssembler, vanGateway, recoveryFinalizationService);
    }

    @Test
    @DisplayName("DB가 CANCELLED이면 VAN과 finalizer 호출 없이 RESOLVED다")
    void handle_dbCancelled_shouldReturnResolved_withoutVanOrFinalizerCall() {
        when(cancelRepository.findByPosTrx(CANCEL_POS_TRX))
                .thenReturn(Optional.of(cancel(CancelStatus.CANCELLED)));

        RecoveryHandlerResult result = handler.handle(cancelTask());

        assertThat(result.resultType()).isEqualTo(RecoveryHandlerResultType.RESOLVED);
        assertThat(result.dbStatus()).isEqualTo("CANCELLED");
        verifyNoInteractions(vanInquiryAssembler, vanGateway, recoveryFinalizationService);
    }

    @Test
    @DisplayName("DB가 CANCEL_DECLINED이면 VAN과 finalizer 호출 없이 RESOLVED다")
    void handle_dbCancelDeclined_shouldReturnResolved_withoutVanOrFinalizerCall() {
        when(cancelRepository.findByPosTrx(CANCEL_POS_TRX))
                .thenReturn(Optional.of(cancel(CancelStatus.CANCEL_DECLINED)));

        RecoveryHandlerResult result = handler.handle(cancelTask());

        assertThat(result.resultType()).isEqualTo(RecoveryHandlerResultType.RESOLVED);
        assertThat(result.dbStatus()).isEqualTo("CANCEL_DECLINED");
        verifyNoInteractions(vanInquiryAssembler, vanGateway, recoveryFinalizationService);
    }

    @Test
    @DisplayName("DB가 PENDING이면 VAN Inquiry를 수행한다")
    void handle_dbPending_shouldCallVanInquiry() {
        VanInquiryRequest request = inquiryRequest();
        when(cancelRepository.findByPosTrx(CANCEL_POS_TRX))
                .thenReturn(Optional.of(cancel(CancelStatus.PENDING)));
        when(vanInquiryAssembler.getCancelInquiryRequest(CANCEL_POS_TRX)).thenReturn(request);
        when(vanGateway.inquiry(request)).thenReturn(inquiryResponse(VanInquiryResultCode.NOT_FOUND, null));

        handler.handle(cancelTask());

        verify(vanInquiryAssembler).getCancelInquiryRequest(CANCEL_POS_TRX);
        verify(vanGateway).inquiry(request);
    }

    @Test
    @DisplayName("DB가 UNKNOWN_TIMEOUT이면 VAN Inquiry를 수행한다")
    void handle_dbUnknownTimeout_shouldCallVanInquiry() {
        VanInquiryRequest request = inquiryRequest();
        when(cancelRepository.findByPosTrx(CANCEL_POS_TRX))
                .thenReturn(Optional.of(cancel(CancelStatus.UNKNOWN_TIMEOUT)));
        when(vanInquiryAssembler.getCancelInquiryRequest(CANCEL_POS_TRX)).thenReturn(request);
        when(vanGateway.inquiry(request)).thenReturn(inquiryResponse(VanInquiryResultCode.NOT_FOUND, null));

        handler.handle(cancelTask());

        verify(vanInquiryAssembler).getCancelInquiryRequest(CANCEL_POS_TRX);
        verify(vanGateway).inquiry(request);
    }

    @Test
    @DisplayName("VAN NOT_FOUND면 observedStatus를 구분하고 finalizer를 호출하지 않는다")
    void handle_vanNotFound_shouldReturnStillUnresolved_withoutFinalizerCall() {
        stubInquiry(CancelStatus.PENDING, inquiryResponse(VanInquiryResultCode.NOT_FOUND, null));

        RecoveryHandlerResult result = handler.handle(cancelTask());

        assertThat(result.resultType()).isEqualTo(RecoveryHandlerResultType.STILL_UNRESOLVED);
        assertThat(result.observedStatus()).isEqualTo("NOT_FOUND");
        assertThat(result.dbStatus()).isEqualTo("PENDING");
        verifyNoInteractions(recoveryFinalizationService);
    }

    @Test
    @DisplayName("VAN UNKNOWN이면 observedStatus를 구분하고 finalizer를 호출하지 않는다")
    void handle_vanUnknown_shouldReturnStillUnresolved_withoutFinalizerCall() {
        stubInquiry(
                CancelStatus.UNKNOWN_TIMEOUT,
                inquiryResponse(VanInquiryResultCode.SUCCESS, VanInquiryStatus.UNKNOWN)
        );

        RecoveryHandlerResult result = handler.handle(cancelTask());

        assertThat(result.resultType()).isEqualTo(RecoveryHandlerResultType.STILL_UNRESOLVED);
        assertThat(result.observedStatus()).isEqualTo("UNKNOWN");
        assertThat(result.dbStatus()).isEqualTo("UNKNOWN_TIMEOUT");
        verifyNoInteractions(recoveryFinalizationService);
    }

    @Test
    @DisplayName("VAN CANCELLED와 finalizer APPLIED는 RESOLVED로 변환한다")
    void handle_vanCancelledAndFinalizerApplied_shouldReturnResolved() {
        stubInquiry(
                CancelStatus.PENDING,
                inquiryResponse(VanInquiryResultCode.SUCCESS, VanInquiryStatus.CANCELLED)
        );
        when(recoveryFinalizationService.finalizeCancel(any()))
                .thenReturn(finalizeResult(RecoveryFinalizeResultType.APPLIED, "CANCELLED", "CANCELLED"));

        RecoveryHandlerResult result = handler.handle(cancelTask());

        assertThat(result.resultType()).isEqualTo(RecoveryHandlerResultType.RESOLVED);
        assertThat(result.observedStatus()).isEqualTo("CANCELLED");
        assertThat(result.dbStatus()).isEqualTo("CANCELLED");

        ArgumentCaptor<CancelResultUpdateParam> captor = ArgumentCaptor.forClass(CancelResultUpdateParam.class);
        verify(recoveryFinalizationService).finalizeCancel(captor.capture());
        assertThat(captor.getValue().posTrx()).isEqualTo(CANCEL_POS_TRX);
        assertThat(captor.getValue().originalPosTrx()).isEqualTo(ORIGINAL_POS_TRX);
        assertThat(captor.getValue().originalAttemptSeq()).isEqualTo(ORIGINAL_ATTEMPT_SEQ);
        assertThat(captor.getValue().cancelStatus()).isEqualTo(CancelStatus.CANCELLED);
        assertThat(captor.getValue().vanCancelTrxId()).isEqualTo(VAN_CANCEL_TRX_ID);
        assertThat(captor.getValue().cancelApprovalNo()).isEqualTo(CANCEL_APPROVAL_NO);
    }

    @Test
    @DisplayName("VAN CANCEL_DECLINED와 finalizer ALREADY_CONSISTENT는 RESOLVED로 변환한다")
    void handle_vanCancelDeclinedAndFinalizerAlreadyConsistent_shouldReturnResolved() {
        stubInquiry(
                CancelStatus.UNKNOWN_TIMEOUT,
                inquiryResponse(VanInquiryResultCode.SUCCESS, VanInquiryStatus.CANCEL_DECLINED)
        );
        when(recoveryFinalizationService.finalizeCancel(any()))
                .thenReturn(finalizeResult(
                        RecoveryFinalizeResultType.ALREADY_CONSISTENT,
                        "CANCEL_DECLINED",
                        "CANCEL_DECLINED"
                ));

        RecoveryHandlerResult result = handler.handle(cancelTask());

        assertThat(result.resultType()).isEqualTo(RecoveryHandlerResultType.RESOLVED);

        ArgumentCaptor<CancelResultUpdateParam> captor = ArgumentCaptor.forClass(CancelResultUpdateParam.class);
        verify(recoveryFinalizationService).finalizeCancel(captor.capture());
        assertThat(captor.getValue().cancelStatus()).isEqualTo(CancelStatus.CANCEL_DECLINED);
        assertThat(captor.getValue().vanCancelTrxId()).isEqualTo(VAN_CANCEL_TRX_ID);
        assertThat(captor.getValue().declineCode()).isEqualTo(VanDeclineCode.DO_NOT_HONOR.code());
    }

    @Test
    @DisplayName("finalizer STILL_UNRESOLVED는 handler STILL_UNRESOLVED로 변환한다")
    void handle_finalizerStillUnresolved_shouldMapResult() {
        stubTerminalInquiryAndFinalizer(
                RecoveryFinalizeResultType.STILL_UNRESOLVED,
                "CANCELLED",
                "UNKNOWN_TIMEOUT"
        );

        RecoveryHandlerResult result = handler.handle(cancelTask());

        assertThat(result.resultType()).isEqualTo(RecoveryHandlerResultType.STILL_UNRESOLVED);
        assertThat(result.observedStatus()).isEqualTo("CANCELLED");
        assertThat(result.dbStatus()).isEqualTo("UNKNOWN_TIMEOUT");
    }

    @Test
    @DisplayName("finalizer TERMINAL_CONFLICT는 handler TERMINAL_CONFLICT로 변환한다")
    void handle_finalizerTerminalConflict_shouldMapResult() {
        stubTerminalInquiryAndFinalizer(
                RecoveryFinalizeResultType.TERMINAL_CONFLICT,
                "CANCELLED",
                "CANCEL_DECLINED"
        );

        RecoveryHandlerResult result = handler.handle(cancelTask());

        assertThat(result.resultType()).isEqualTo(RecoveryHandlerResultType.TERMINAL_CONFLICT);
        assertThat(result.observedStatus()).isEqualTo("CANCELLED");
        assertThat(result.dbStatus()).isEqualTo("CANCEL_DECLINED");
    }

    @Test
    @DisplayName("finalizer TARGET_NOT_FOUND는 handler TARGET_NOT_FOUND로 변환한다")
    void handle_finalizerTargetNotFound_shouldMapResult() {
        stubTerminalInquiryAndFinalizer(
                RecoveryFinalizeResultType.TARGET_NOT_FOUND,
                "CANCELLED",
                null
        );

        RecoveryHandlerResult result = handler.handle(cancelTask());

        assertThat(result.resultType()).isEqualTo(RecoveryHandlerResultType.TARGET_NOT_FOUND);
        assertThat(result.observedStatus()).isEqualTo("CANCELLED");
        assertThat(result.dbStatus()).isNull();
    }

    @ParameterizedTest
    @EnumSource(value = VanInquiryStatus.class, names = {"APPROVED", "DECLINED"})
    @DisplayName("CANCEL Inquiry의 approval 계열 VAN status는 명시적으로 거부한다")
    void handle_approvalVanStatus_shouldReject(VanInquiryStatus status) {
        stubInquiry(CancelStatus.PENDING, inquiryResponse(VanInquiryResultCode.SUCCESS, status));

        assertThatThrownBy(() -> handler.handle(cancelTask()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Unexpected VAN inquiry status for CANCEL: " + status);

        verifyNoInteractions(recoveryFinalizationService);
    }

    @Test
    @DisplayName("task와 cancel row의 original identity가 다르면 recovery를 진행하지 않는다")
    void handle_originalIdentityMismatch_shouldReject_withoutVanOrFinalizerCall() {
        when(cancelRepository.findByPosTrx(CANCEL_POS_TRX))
                .thenReturn(Optional.of(cancel(CancelStatus.PENDING)));
        RecoveryTask mismatchedTask = task(
                RecoveryTargetType.CANCEL,
                ORIGINAL_POS_TRX + "-MISMATCH",
                ORIGINAL_ATTEMPT_SEQ
        );

        assertThatThrownBy(() -> handler.handle(mismatchedTask))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("RECOVERY_CANCEL_TARGET_IDENTITY_MISMATCH");

        verifyNoInteractions(vanInquiryAssembler, vanGateway, recoveryFinalizationService);
    }

    private void stubInquiry(CancelStatus dbStatus, VanInquiryResponse response) {
        VanInquiryRequest request = inquiryRequest();
        when(cancelRepository.findByPosTrx(CANCEL_POS_TRX))
                .thenReturn(Optional.of(cancel(dbStatus)));
        when(vanInquiryAssembler.getCancelInquiryRequest(CANCEL_POS_TRX)).thenReturn(request);
        when(vanGateway.inquiry(request)).thenReturn(response);
    }

    private void stubTerminalInquiryAndFinalizer(
            RecoveryFinalizeResultType resultType,
            String intendedStatus,
            String dbStatus
    ) {
        stubInquiry(
                CancelStatus.UNKNOWN_TIMEOUT,
                inquiryResponse(VanInquiryResultCode.SUCCESS, VanInquiryStatus.CANCELLED)
        );
        when(recoveryFinalizationService.finalizeCancel(any()))
                .thenReturn(finalizeResult(resultType, intendedStatus, dbStatus));
    }

    private RecoveryTask cancelTask() {
        return task(RecoveryTargetType.CANCEL, ORIGINAL_POS_TRX, ORIGINAL_ATTEMPT_SEQ);
    }

    private RecoveryTask task(
            RecoveryTargetType targetType,
            String originalPosTrx,
            int originalAttemptSeq
    ) {
        LocalDateTime now = LocalDateTime.of(2026, 9, 10, 10, 0);
        return new RecoveryTask(
                1L,
                targetType,
                CANCEL_POS_TRX,
                null,
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

    private PaymentCancel cancel(CancelStatus status) {
        return new PaymentCancel(
                CANCEL_POS_TRX,
                ORIGINAL_POS_TRX,
                ORIGINAL_ATTEMPT_SEQ,
                status,
                VAN_CANCEL_TRX_ID,
                status == CancelStatus.CANCELLED ? CANCEL_APPROVAL_NO : null,
                status == CancelStatus.CANCEL_DECLINED ? VanDeclineCode.DO_NOT_HONOR.code() : null
        );
    }

    private VanInquiryRequest inquiryRequest() {
        return VanInquiryRequest.builder()
                .targetType(VanInquiryTargetType.CANCEL)
                .targetTrxNo(CANCEL_POS_TRX)
                .build();
    }

    private VanInquiryResponse inquiryResponse(
            VanInquiryResultCode resultCode,
            VanInquiryStatus status
    ) {
        return VanInquiryResponse.builder()
                .targetType(VanInquiryTargetType.CANCEL)
                .targetTrxNo(CANCEL_POS_TRX)
                .targetAttemptSeq(null)
                .resultCode(resultCode)
                .status(status)
                .vanTrxId(VAN_CANCEL_TRX_ID)
                .cancelApprovalNo(status == VanInquiryStatus.CANCELLED ? CANCEL_APPROVAL_NO : null)
                .declineCode(status == VanInquiryStatus.CANCEL_DECLINED ? VanDeclineCode.DO_NOT_HONOR : null)
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
