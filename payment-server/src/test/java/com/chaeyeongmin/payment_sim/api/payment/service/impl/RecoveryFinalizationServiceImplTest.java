package com.chaeyeongmin.payment_sim.api.payment.service.impl;

import com.chaeyeongmin.payment_sim.api.payment.service.transaction.model.RecoveryFinalizeResult;
import com.chaeyeongmin.payment_sim.api.payment.service.transaction.model.RecoveryFinalizeResultType;
import com.chaeyeongmin.payment_sim.domain.model.PaymentAttempt;
import com.chaeyeongmin.payment_sim.domain.model.PaymentCancel;
import com.chaeyeongmin.payment_sim.domain.model.PaymentReversal;
import com.chaeyeongmin.payment_sim.domain.policy.CancelStatus;
import com.chaeyeongmin.payment_sim.domain.policy.ReversalStatus;
import com.chaeyeongmin.payment_sim.domain.status.PaymentFinalStatus;
import com.chaeyeongmin.payment_sim.infra.repository.PaymentAttemptRepository;
import com.chaeyeongmin.payment_sim.infra.repository.PaymentCancelRepository;
import com.chaeyeongmin.payment_sim.infra.repository.PaymentReversalRepository;
import com.chaeyeongmin.payment_sim.infra.repository.dto.AttemptResultUpdateParam;
import com.chaeyeongmin.payment_sim.infra.repository.dto.CancelResultUpdateParam;
import com.chaeyeongmin.payment_sim.infra.repository.dto.PaymentAttemptUpdatedRow;
import com.chaeyeongmin.payment_sim.infra.repository.dto.ReversalResultUpdateParam;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class RecoveryFinalizationServiceImplTest {

    private PaymentAttemptRepository attemptRepository;
    private PaymentCancelRepository cancelRepository;
    private PaymentReversalRepository reversalRepository;
    private RecoveryFinalizationServiceImpl service;

    @BeforeEach
    void setUp() {
        attemptRepository = mock(PaymentAttemptRepository.class);
        cancelRepository = mock(PaymentCancelRepository.class);
        reversalRepository = mock(PaymentReversalRepository.class);
        service = new RecoveryFinalizationServiceImpl(
                attemptRepository,
                cancelRepository,
                reversalRepository
        );
    }

    @Test
    @DisplayName("approval PROCESSING row를 APPROVED로 확정하면 APPLIED를 반환한다")
    void approval_PROCESSING_to_APPROVED_returns_APPLIED() {
        // finalizeApproval(): 승인 recovery 대상이 아직 PROCESSING이면 APPROVED terminal 확정이 가능하다.
        AttemptResultUpdateParam intended = AttemptResultUpdateParam.approved(
                "R6P6-APP-APPLIED-APPROVED",
                1,
                "APPROVAL-001",
                "VAN-APP-001"
        );
        when(attemptRepository.updateRecoverableToFinal(intended))
                .thenReturn(Optional.of(updatedAttempt(intended, PaymentFinalStatus.APPROVED)));

        // result: repository가 updated row를 반환했으므로 finalizeApproval()은 이번 recovery가 확정했다고 봐야 한다.
        RecoveryFinalizeResult result = service.finalizeApproval(intended);

        // conditional update 성공 케이스이므로 APPLIED와 APPROVED dbStatus를 기대한다.
        assertThat(result.resultType()).isEqualTo(RecoveryFinalizeResultType.APPLIED);
        assertThat(result.intendedStatus()).isEqualTo("APPROVED");
        assertThat(result.dbStatus()).isEqualTo("APPROVED");
    }

    @Test
    @DisplayName("approval UNKNOWN_TIMEOUT row를 DECLINED로 확정하면 APPLIED를 반환한다")
    void approval_UNKNOWN_TIMEOUT_to_DECLINED_returns_APPLIED() {
        // finalizeApproval(): UNKNOWN_TIMEOUT 승인 row도 VAN inquiry 결과가 DECLINED면 terminal로 수렴시킨다.
        AttemptResultUpdateParam intended = AttemptResultUpdateParam.declined(
                "R6P6-APP-APPLIED-DECLINED",
                1,
                "05",
                "VAN-APP-002"
        );
        when(attemptRepository.updateRecoverableToFinal(intended))
                .thenReturn(Optional.of(updatedAttempt(intended, PaymentFinalStatus.DECLINED)));

        // result: repository가 DECLINED updated row를 반환했으므로 finalizeApproval()은 확정 성공으로 처리해야 한다.
        RecoveryFinalizeResult result = service.finalizeApproval(intended);

        // UNKNOWN_TIMEOUT에서 terminal로 수렴한 호출이므로 APPLIED와 DECLINED dbStatus를 기대한다.
        assertThat(result.resultType()).isEqualTo(RecoveryFinalizeResultType.APPLIED);
        assertThat(result.intendedStatus()).isEqualTo("DECLINED");
        assertThat(result.dbStatus()).isEqualTo("DECLINED");
    }

    @Test
    @DisplayName("approval update miss 후 같은 terminal이면 ALREADY_CONSISTENT를 반환한다")
    void approval_same_terminal_after_miss_returns_ALREADY_CONSISTENT() {
        // finalizeApproval(): update miss 후 DB가 같은 terminal이면 다른 recovery 흐름이 이미 같은 결론을 쓴 것이다.
        AttemptResultUpdateParam intended = AttemptResultUpdateParam.approved(
                "R6P6-APP-CONSISTENT",
                1,
                "APPROVAL-002",
                "VAN-APP-003"
        );
        when(attemptRepository.updateRecoverableToFinal(intended)).thenReturn(Optional.empty());
        when(attemptRepository.findByPosTrxAndAttemptSeq(intended.posTrx(), intended.attemptSeq()))
                .thenReturn(Optional.of(attempt(PaymentFinalStatus.APPROVED)));

        // result: update miss 후 reread한 DB 상태가 intended와 같은 APPROVED라야 한다.
        RecoveryFinalizeResult result = service.finalizeApproval(intended);

        // 다른 흐름이 이미 같은 결론을 저장한 상황이므로 ALREADY_CONSISTENT를 기대한다.
        assertThat(result.resultType()).isEqualTo(RecoveryFinalizeResultType.ALREADY_CONSISTENT);
        assertThat(result.dbStatus()).isEqualTo("APPROVED");
    }

    @Test
    @DisplayName("approval update miss 후 다른 terminal이면 TERMINAL_CONFLICT를 반환한다")
    void approval_different_terminal_after_miss_returns_TERMINAL_CONFLICT() {
        // finalizeApproval(): DB terminal과 intended terminal이 다르면 기존 terminal을 덮지 않고 conflict로 판정한다.
        AttemptResultUpdateParam intended = AttemptResultUpdateParam.declined(
                "R6P6-APP-CONFLICT",
                1,
                "05",
                "VAN-APP-004"
        );
        when(attemptRepository.updateRecoverableToFinal(intended)).thenReturn(Optional.empty());
        when(attemptRepository.findByPosTrxAndAttemptSeq(intended.posTrx(), intended.attemptSeq()))
                .thenReturn(Optional.of(attempt(PaymentFinalStatus.APPROVED)));

        // result: update miss 후 reread한 DB 상태가 intended DECLINED와 다른 APPROVED라야 한다.
        RecoveryFinalizeResult result = service.finalizeApproval(intended);

        // 이미 존재하는 APPROVED terminal을 덮지 말고 TERMINAL_CONFLICT로 알려야 한다.
        assertThat(result.resultType()).isEqualTo(RecoveryFinalizeResultType.TERMINAL_CONFLICT);
        assertThat(result.intendedStatus()).isEqualTo("DECLINED");
        assertThat(result.dbStatus()).isEqualTo("APPROVED");
    }

    @Test
    @DisplayName("approval update miss 후 PROCESSING이면 STILL_UNRESOLVED를 반환한다")
    void approval_processing_after_miss_returns_STILL_UNRESOLVED() {
        // finalizeApproval(): update miss 후 재조회 결과도 PROCESSING이면 아직 확정 주체가 없으므로 미해결로 남긴다.
        AttemptResultUpdateParam intended = AttemptResultUpdateParam.approved(
                "R6P6-APP-STILL-PROCESSING",
                1,
                "APPROVAL-003",
                "VAN-APP-005"
        );
        when(attemptRepository.updateRecoverableToFinal(intended)).thenReturn(Optional.empty());
        when(attemptRepository.findByPosTrxAndAttemptSeq(intended.posTrx(), intended.attemptSeq()))
                .thenReturn(Optional.of(attempt(PaymentFinalStatus.PROCESSING)));

        // result: update miss 후 reread해도 PROCESSING이면 recovery finalization은 아직 확정되지 않은 것이다.
        RecoveryFinalizeResult result = service.finalizeApproval(intended);

        // terminal도 target missing도 아니므로 STILL_UNRESOLVED를 기대한다.
        assertThat(result.resultType()).isEqualTo(RecoveryFinalizeResultType.STILL_UNRESOLVED);
        assertThat(result.dbStatus()).isEqualTo("PROCESSING");
    }

    @Test
    @DisplayName("approval update miss 후 UNKNOWN_TIMEOUT이면 STILL_UNRESOLVED를 반환한다")
    void approval_unknown_timeout_after_miss_returns_STILL_UNRESOLVED() {
        // finalizeApproval(): update miss 후 UNKNOWN_TIMEOUT이 남아 있으면 terminal 확정이 되지 않은 상태로 본다.
        AttemptResultUpdateParam intended = AttemptResultUpdateParam.declined(
                "R6P6-APP-STILL-UNKNOWN",
                1,
                "05",
                "VAN-APP-006"
        );
        when(attemptRepository.updateRecoverableToFinal(intended)).thenReturn(Optional.empty());
        when(attemptRepository.findByPosTrxAndAttemptSeq(intended.posTrx(), intended.attemptSeq()))
                .thenReturn(Optional.of(attempt(PaymentFinalStatus.UNKNOWN_TIMEOUT)));

        // result: update miss 후 reread해도 UNKNOWN_TIMEOUT이면 아직 terminal fact가 DB에 없다.
        RecoveryFinalizeResult result = service.finalizeApproval(intended);

        // 다음 recovery 시도 여지를 남기는 STILL_UNRESOLVED를 기대한다.
        assertThat(result.resultType()).isEqualTo(RecoveryFinalizeResultType.STILL_UNRESOLVED);
        assertThat(result.dbStatus()).isEqualTo("UNKNOWN_TIMEOUT");
    }

    @Test
    @DisplayName("approval update miss 후 row가 없으면 TARGET_NOT_FOUND를 반환한다")
    void approval_missing_row_after_miss_returns_TARGET_NOT_FOUND() {
        // finalizeApproval(): update도 miss이고 정확한 attempt 재조회도 실패하면 recovery 대상이 사라진 것이다.
        AttemptResultUpdateParam intended = AttemptResultUpdateParam.approved(
                "R6P6-APP-NOT-FOUND",
                1,
                "APPROVAL-004",
                "VAN-APP-007"
        );
        when(attemptRepository.updateRecoverableToFinal(intended)).thenReturn(Optional.empty());
        when(attemptRepository.findByPosTrxAndAttemptSeq(intended.posTrx(), intended.attemptSeq()))
                .thenReturn(Optional.empty());

        // result: update miss 후 reread 결과도 empty면 finalizeApproval()이 처리할 대상이 없다.
        RecoveryFinalizeResult result = service.finalizeApproval(intended);

        // missing target은 TARGET_NOT_FOUND로 표현하고 dbStatus는 null이어야 한다.
        assertThat(result.resultType()).isEqualTo(RecoveryFinalizeResultType.TARGET_NOT_FOUND);
        assertThat(result.dbStatus()).isNull();
    }

    @Test
    @DisplayName("approval invalid intended target은 repository 호출 전에 reject한다")
    void approval_invalid_target_is_rejected_before_repository_call() {
        // finalizeApproval(): APPROVED/DECLINED가 아닌 intended target은 DB write 시도 전에 거부한다.
        AttemptResultUpdateParam intended = AttemptResultUpdateParam.unknownTimeout(
                "R6P6-APP-INVALID",
                1,
                "TIMEOUT",
                "VAN-APP-008"
        );

        // result: UNKNOWN_TIMEOUT은 approval recovery의 intended terminal이 아니므로 즉시 예외를 기대한다.
        assertThatThrownBy(() -> service.finalizeApproval(intended))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Approval recovery target must be APPROVED or DECLINED");
        // reject 케이스에서 repository가 호출되면 잘못된 target으로 DB write를 시도할 수 있으므로 호출 자체가 없어야 한다.
        verifyNoInteractions(attemptRepository);
    }

    @Test
    @DisplayName("cancel PENDING row를 CANCELLED로 확정하면 APPLIED를 반환한다")
    void cancel_PENDING_to_CANCELLED_returns_APPLIED() {
        // finalizeCancel(): 취소 recovery 대상이 PENDING이면 CANCELLED terminal 확정이 가능하다.
        CancelResultUpdateParam intended = CancelResultUpdateParam.cancelled(
                "R6P6-CAN-APPLIED-CANCELLED",
                "R6P6-CAN-ORIGINAL",
                1,
                "VAN-CAN-001",
                "CANCEL-APPROVAL-001"
        );
        when(cancelRepository.updateRecoverableToFinal(intended))
                .thenReturn(Optional.of(cancel(intended, CancelStatus.CANCELLED)));

        // result: repository가 updated row를 반환했으므로 finalizeCancel()은 이번 recovery가 확정했다고 봐야 한다.
        RecoveryFinalizeResult result = service.finalizeCancel(intended);

        // conditional update 성공 케이스이므로 APPLIED와 CANCELLED dbStatus를 기대한다.
        assertThat(result.resultType()).isEqualTo(RecoveryFinalizeResultType.APPLIED);
        assertThat(result.dbStatus()).isEqualTo("CANCELLED");
    }

    @Test
    @DisplayName("cancel UNKNOWN_TIMEOUT row를 CANCEL_DECLINED로 확정하면 APPLIED를 반환한다")
    void cancel_UNKNOWN_TIMEOUT_to_CANCEL_DECLINED_returns_APPLIED() {
        // finalizeCancel(): UNKNOWN_TIMEOUT 취소 row도 VAN inquiry 결과가 거절이면 CANCEL_DECLINED로 수렴시킨다.
        CancelResultUpdateParam intended = CancelResultUpdateParam.declined(
                "R6P6-CAN-APPLIED-DECLINED",
                "R6P6-CAN-ORIGINAL",
                1,
                "VAN-CAN-002",
                "05"
        );
        when(cancelRepository.updateRecoverableToFinal(intended))
                .thenReturn(Optional.of(cancel(intended, CancelStatus.CANCEL_DECLINED)));

        // result: repository가 CANCEL_DECLINED updated row를 반환했으므로 확정 성공으로 처리해야 한다.
        RecoveryFinalizeResult result = service.finalizeCancel(intended);

        // UNKNOWN_TIMEOUT에서 terminal로 수렴한 호출이므로 APPLIED와 CANCEL_DECLINED dbStatus를 기대한다.
        assertThat(result.resultType()).isEqualTo(RecoveryFinalizeResultType.APPLIED);
        assertThat(result.dbStatus()).isEqualTo("CANCEL_DECLINED");
    }

    @Test
    @DisplayName("cancel update miss 후 같은 terminal이면 ALREADY_CONSISTENT를 반환한다")
    void cancel_same_terminal_after_miss_returns_ALREADY_CONSISTENT() {
        // finalizeCancel(): update miss 후 같은 CURRENT_TRX_NO가 같은 terminal이면 반복 finalization으로 본다.
        CancelResultUpdateParam intended = CancelResultUpdateParam.cancelled(
                "R6P6-CAN-CONSISTENT",
                "R6P6-CAN-ORIGINAL",
                1,
                "VAN-CAN-003",
                "CANCEL-APPROVAL-002"
        );
        when(cancelRepository.updateRecoverableToFinal(intended)).thenReturn(Optional.empty());
        when(cancelRepository.findByPosTrx(intended.posTrx()))
                .thenReturn(Optional.of(cancel(intended, CancelStatus.CANCELLED)));

        // result: update miss 후 reread한 cancel row가 intended와 같은 CANCELLED라야 한다.
        RecoveryFinalizeResult result = service.finalizeCancel(intended);

        // 반복 finalization 또는 선행 확정으로 보고 ALREADY_CONSISTENT를 기대한다.
        assertThat(result.resultType()).isEqualTo(RecoveryFinalizeResultType.ALREADY_CONSISTENT);
        assertThat(result.dbStatus()).isEqualTo("CANCELLED");
    }

    @Test
    @DisplayName("cancel update miss 후 다른 terminal이면 TERMINAL_CONFLICT를 반환한다")
    void cancel_different_terminal_after_miss_returns_TERMINAL_CONFLICT() {
        // finalizeCancel(): 이미 CANCELLED인 row를 CANCEL_DECLINED로 바꾸지 않고 terminal conflict로 반환한다.
        CancelResultUpdateParam intended = CancelResultUpdateParam.declined(
                "R6P6-CAN-CONFLICT",
                "R6P6-CAN-ORIGINAL",
                1,
                "VAN-CAN-004",
                "05"
        );
        when(cancelRepository.updateRecoverableToFinal(intended)).thenReturn(Optional.empty());
        when(cancelRepository.findByPosTrx(intended.posTrx()))
                .thenReturn(Optional.of(cancel(intended, CancelStatus.CANCELLED)));

        // result: update miss 후 reread한 DB 상태가 intended CANCEL_DECLINED와 다른 CANCELLED라야 한다.
        RecoveryFinalizeResult result = service.finalizeCancel(intended);

        // 이미 CANCELLED인 terminal을 덮지 말고 TERMINAL_CONFLICT로 알려야 한다.
        assertThat(result.resultType()).isEqualTo(RecoveryFinalizeResultType.TERMINAL_CONFLICT);
        assertThat(result.dbStatus()).isEqualTo("CANCELLED");
    }

    @Test
    @DisplayName("cancel update miss 후 PENDING이면 STILL_UNRESOLVED를 반환한다")
    void cancel_pending_after_miss_returns_STILL_UNRESOLVED() {
        // finalizeCancel(): update miss 후 PENDING이 다시 읽히면 아직 취소 terminal 확정이 안 된 상태다.
        CancelResultUpdateParam intended = CancelResultUpdateParam.cancelled(
                "R6P6-CAN-STILL-PENDING",
                "R6P6-CAN-ORIGINAL",
                1,
                "VAN-CAN-005",
                "CANCEL-APPROVAL-003"
        );
        when(cancelRepository.updateRecoverableToFinal(intended)).thenReturn(Optional.empty());
        when(cancelRepository.findByPosTrx(intended.posTrx()))
                .thenReturn(Optional.of(cancel(intended, CancelStatus.PENDING)));

        // result: update miss 후 reread해도 PENDING이면 취소 결과가 아직 확정되지 않은 것이다.
        RecoveryFinalizeResult result = service.finalizeCancel(intended);

        // terminal도 target missing도 아니므로 STILL_UNRESOLVED를 기대한다.
        assertThat(result.resultType()).isEqualTo(RecoveryFinalizeResultType.STILL_UNRESOLVED);
        assertThat(result.dbStatus()).isEqualTo("PENDING");
    }

    @Test
    @DisplayName("cancel update miss 후 UNKNOWN_TIMEOUT이면 STILL_UNRESOLVED를 반환한다")
    void cancel_unknown_timeout_after_miss_returns_STILL_UNRESOLVED() {
        // finalizeCancel(): update miss 후 UNKNOWN_TIMEOUT이 남으면 VAN 처리 결과가 아직 DB에 수렴되지 않은 것이다.
        CancelResultUpdateParam intended = CancelResultUpdateParam.declined(
                "R6P6-CAN-STILL-UNKNOWN",
                "R6P6-CAN-ORIGINAL",
                1,
                "VAN-CAN-006",
                "05"
        );
        when(cancelRepository.updateRecoverableToFinal(intended)).thenReturn(Optional.empty());
        when(cancelRepository.findByPosTrx(intended.posTrx()))
                .thenReturn(Optional.of(cancel(intended, CancelStatus.UNKNOWN_TIMEOUT)));

        // result: update miss 후 reread해도 UNKNOWN_TIMEOUT이면 아직 terminal fact가 DB에 없다.
        RecoveryFinalizeResult result = service.finalizeCancel(intended);

        // 다음 recovery 시도 여지를 남기는 STILL_UNRESOLVED를 기대한다.
        assertThat(result.resultType()).isEqualTo(RecoveryFinalizeResultType.STILL_UNRESOLVED);
        assertThat(result.dbStatus()).isEqualTo("UNKNOWN_TIMEOUT");
    }

    @Test
    @DisplayName("cancel update miss 후 row가 없으면 TARGET_NOT_FOUND를 반환한다")
    void cancel_missing_row_after_miss_returns_TARGET_NOT_FOUND() {
        // finalizeCancel(): update도 miss이고 CURRENT_TRX_NO 재조회도 실패하면 recovery 대상 없음으로 처리한다.
        CancelResultUpdateParam intended = CancelResultUpdateParam.cancelled(
                "R6P6-CAN-NOT-FOUND",
                "R6P6-CAN-ORIGINAL",
                1,
                "VAN-CAN-007",
                "CANCEL-APPROVAL-004"
        );
        when(cancelRepository.updateRecoverableToFinal(intended)).thenReturn(Optional.empty());
        when(cancelRepository.findByPosTrx(intended.posTrx())).thenReturn(Optional.empty());

        // result: update miss 후 CURRENT_TRX_NO reread 결과도 empty면 finalizeCancel()이 처리할 대상이 없다.
        RecoveryFinalizeResult result = service.finalizeCancel(intended);

        // missing target은 TARGET_NOT_FOUND로 표현하고 dbStatus는 null이어야 한다.
        assertThat(result.resultType()).isEqualTo(RecoveryFinalizeResultType.TARGET_NOT_FOUND);
        assertThat(result.dbStatus()).isNull();
    }

    @Test
    @DisplayName("cancel invalid intended target은 repository 호출 전에 reject한다")
    void cancel_invalid_target_is_rejected_before_repository_call() {
        // finalizeCancel(): CANCELLED/CANCEL_DECLINED가 아닌 intended target은 정상 결과로 분류하지 않는다.
        CancelResultUpdateParam intended = CancelResultUpdateParam.unknownTimeout(
                "R6P6-CAN-INVALID",
                "R6P6-CAN-ORIGINAL",
                1
        );

        // result: UNKNOWN_TIMEOUT은 cancel recovery의 intended terminal이 아니므로 즉시 예외를 기대한다.
        assertThatThrownBy(() -> service.finalizeCancel(intended))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Cancel recovery target must be CANCELLED or CANCEL_DECLINED");
        // reject 케이스에서 repository가 호출되면 잘못된 target으로 DB write를 시도할 수 있으므로 호출 자체가 없어야 한다.
        verifyNoInteractions(cancelRepository);
    }

    @Test
    @DisplayName("cancel reread row의 original identity가 다르면 정상 race 결과로 분류하지 않는다")
    void cancel_original_identity_mismatch_is_rejected() {
        // finalizeCancel(): CURRENT_TRX_NO 재조회 row가 다른 원거래를 가리키면 idempotent/conflict 경합이 아니다.
        CancelResultUpdateParam intended = CancelResultUpdateParam.cancelled(
                "R6P6-CAN-MISMATCH",
                "R6P6-CAN-ORIGINAL",
                1,
                "VAN-CAN-008",
                "CANCEL-APPROVAL-005"
        );
        when(cancelRepository.updateRecoverableToFinal(intended)).thenReturn(Optional.empty());
        when(cancelRepository.findByPosTrx(intended.posTrx()))
                .thenReturn(Optional.of(new PaymentCancel(
                        intended.posTrx(),
                        "R6P6-CAN-OTHER-ORIGINAL",
                        2,
                        CancelStatus.CANCELLED,
                        "VAN-CAN-OTHER",
                        "CANCEL-APPROVAL-OTHER",
                        null
                )));

        // result: CURRENT_TRX_NO는 같지만 원거래 identity가 다르므로 정상적인 idempotent/conflict 결과가 아니다.
        assertThatThrownBy(() -> service.finalizeCancel(intended))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("RECOVERY_CANCEL_TARGET_IDENTITY_MISMATCH");
    }

    @Test
    @DisplayName("reversal PENDING row를 REVERSED로 확정하면 APPLIED를 반환한다")
    void reversal_PENDING_to_REVERSED_returns_APPLIED() {
        // finalizeReversal(): 망취소 recovery 대상이 PENDING이면 REVERSED terminal 확정이 가능하다.
        ReversalResultUpdateParam intended = ReversalResultUpdateParam.reversed(
                "R6P6-REV-APPLIED-REVERSED",
                "R6P6-REV-ORIGINAL",
                1,
                "VAN-REV-001",
                "REVERSAL-APPROVAL-001"
        );
        when(reversalRepository.updateRecoverableToFinal(intended))
                .thenReturn(Optional.of(reversal(intended, ReversalStatus.REVERSED)));

        // result: repository가 updated row를 반환했으므로 finalizeReversal()은 이번 recovery가 확정했다고 봐야 한다.
        RecoveryFinalizeResult result = service.finalizeReversal(intended);

        // conditional update 성공 케이스이므로 APPLIED와 REVERSED dbStatus를 기대한다.
        assertThat(result.resultType()).isEqualTo(RecoveryFinalizeResultType.APPLIED);
        assertThat(result.dbStatus()).isEqualTo("REVERSED");
    }

    @Test
    @DisplayName("reversal PENDING row를 REVERSAL_DECLINED로 확정하면 APPLIED를 반환한다")
    void reversal_PENDING_to_REVERSAL_DECLINED_returns_APPLIED() {
        // finalizeReversal(): PENDING 망취소 row는 VAN 결과에 따라 REVERSAL_DECLINED로도 확정할 수 있다.
        ReversalResultUpdateParam intended = ReversalResultUpdateParam.declined(
                "R6P6-REV-APPLIED-DECLINED",
                "R6P6-REV-ORIGINAL",
                1,
                "VAN-REV-002",
                "05"
        );
        when(reversalRepository.updateRecoverableToFinal(intended))
                .thenReturn(Optional.of(reversal(intended, ReversalStatus.REVERSAL_DECLINED)));

        // result: repository가 REVERSAL_DECLINED updated row를 반환했으므로 확정 성공으로 처리해야 한다.
        RecoveryFinalizeResult result = service.finalizeReversal(intended);

        // PENDING에서 terminal로 수렴한 호출이므로 APPLIED와 REVERSAL_DECLINED dbStatus를 기대한다.
        assertThat(result.resultType()).isEqualTo(RecoveryFinalizeResultType.APPLIED);
        assertThat(result.dbStatus()).isEqualTo("REVERSAL_DECLINED");
    }

    @Test
    @DisplayName("reversal update miss 후 같은 terminal이면 ALREADY_CONSISTENT를 반환한다")
    void reversal_same_terminal_after_miss_returns_ALREADY_CONSISTENT() {
        // finalizeReversal(): update miss 후 같은 terminal이면 반복 호출 또는 선행 recovery 결과로 본다.
        ReversalResultUpdateParam intended = ReversalResultUpdateParam.reversed(
                "R6P6-REV-CONSISTENT",
                "R6P6-REV-ORIGINAL",
                1,
                "VAN-REV-003",
                "REVERSAL-APPROVAL-002"
        );
        when(reversalRepository.updateRecoverableToFinal(intended)).thenReturn(Optional.empty());
        when(reversalRepository.findByReversalPosTrx(intended.reversalPosTrx()))
                .thenReturn(Optional.of(reversal(intended, ReversalStatus.REVERSED)));

        // result: update miss 후 reread한 reversal row가 intended와 같은 REVERSED라야 한다.
        RecoveryFinalizeResult result = service.finalizeReversal(intended);

        // 반복 finalization 또는 선행 확정으로 보고 ALREADY_CONSISTENT를 기대한다.
        assertThat(result.resultType()).isEqualTo(RecoveryFinalizeResultType.ALREADY_CONSISTENT);
        assertThat(result.dbStatus()).isEqualTo("REVERSED");
    }

    @Test
    @DisplayName("reversal update miss 후 다른 terminal이면 TERMINAL_CONFLICT를 반환한다")
    void reversal_different_terminal_after_miss_returns_TERMINAL_CONFLICT() {
        // finalizeReversal(): 이미 REVERSED인 row를 REVERSAL_DECLINED로 덮지 않고 conflict로 판정한다.
        ReversalResultUpdateParam intended = ReversalResultUpdateParam.declined(
                "R6P6-REV-CONFLICT",
                "R6P6-REV-ORIGINAL",
                1,
                "VAN-REV-004",
                "05"
        );
        when(reversalRepository.updateRecoverableToFinal(intended)).thenReturn(Optional.empty());
        when(reversalRepository.findByReversalPosTrx(intended.reversalPosTrx()))
                .thenReturn(Optional.of(reversal(intended, ReversalStatus.REVERSED)));

        // result: update miss 후 reread한 DB 상태가 intended REVERSAL_DECLINED와 다른 REVERSED라야 한다.
        RecoveryFinalizeResult result = service.finalizeReversal(intended);

        // 이미 REVERSED인 terminal을 덮지 말고 TERMINAL_CONFLICT로 알려야 한다.
        assertThat(result.resultType()).isEqualTo(RecoveryFinalizeResultType.TERMINAL_CONFLICT);
        assertThat(result.dbStatus()).isEqualTo("REVERSED");
    }

    @Test
    @DisplayName("reversal update miss 후 PENDING이면 STILL_UNRESOLVED를 반환한다")
    void reversal_pending_after_miss_returns_STILL_UNRESOLVED() {
        // finalizeReversal(): update miss 후 PENDING이 남아 있으면 망취소 terminal 확정이 아직 안 된 상태다.
        ReversalResultUpdateParam intended = ReversalResultUpdateParam.reversed(
                "R6P6-REV-STILL-PENDING",
                "R6P6-REV-ORIGINAL",
                1,
                "VAN-REV-005",
                "REVERSAL-APPROVAL-003"
        );
        when(reversalRepository.updateRecoverableToFinal(intended)).thenReturn(Optional.empty());
        when(reversalRepository.findByReversalPosTrx(intended.reversalPosTrx()))
                .thenReturn(Optional.of(reversal(intended, ReversalStatus.PENDING)));

        // result: update miss 후 reread해도 PENDING이면 망취소 결과가 아직 확정되지 않은 것이다.
        RecoveryFinalizeResult result = service.finalizeReversal(intended);

        // terminal도 target missing도 아니므로 STILL_UNRESOLVED를 기대한다.
        assertThat(result.resultType()).isEqualTo(RecoveryFinalizeResultType.STILL_UNRESOLVED);
        assertThat(result.dbStatus()).isEqualTo("PENDING");
    }

    @Test
    @DisplayName("reversal update miss 후 row가 없으면 TARGET_NOT_FOUND를 반환한다")
    void reversal_missing_row_after_miss_returns_TARGET_NOT_FOUND() {
        // finalizeReversal(): update도 miss이고 CURRENT_TRX_NO 재조회도 실패하면 recovery 대상 없음으로 처리한다.
        ReversalResultUpdateParam intended = ReversalResultUpdateParam.reversed(
                "R6P6-REV-NOT-FOUND",
                "R6P6-REV-ORIGINAL",
                1,
                "VAN-REV-006",
                "REVERSAL-APPROVAL-004"
        );
        when(reversalRepository.updateRecoverableToFinal(intended)).thenReturn(Optional.empty());
        when(reversalRepository.findByReversalPosTrx(intended.reversalPosTrx()))
                .thenReturn(Optional.empty());

        // result: update miss 후 CURRENT_TRX_NO reread 결과도 empty면 finalizeReversal()이 처리할 대상이 없다.
        RecoveryFinalizeResult result = service.finalizeReversal(intended);

        // missing target은 TARGET_NOT_FOUND로 표현하고 dbStatus는 null이어야 한다.
        assertThat(result.resultType()).isEqualTo(RecoveryFinalizeResultType.TARGET_NOT_FOUND);
        assertThat(result.dbStatus()).isNull();
    }

    @Test
    @DisplayName("reversal invalid intended target은 repository 호출 전에 reject한다")
    void reversal_invalid_target_is_rejected_before_repository_call() {
        // finalizeReversal(): REVERSED/REVERSAL_DECLINED가 아닌 intended target은 DB write 전에 거부한다.
        ReversalResultUpdateParam intended = new ReversalResultUpdateParam(
                "R6P6-REV-INVALID",
                "R6P6-REV-ORIGINAL",
                1,
                ReversalStatus.PENDING,
                null,
                null,
                null
        );

        // result: PENDING은 reversal recovery의 intended terminal이 아니므로 즉시 예외를 기대한다.
        assertThatThrownBy(() -> service.finalizeReversal(intended))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Reversal recovery target must be REVERSED or REVERSAL_DECLINED");
        // reject 케이스에서 repository가 호출되면 잘못된 target으로 DB write를 시도할 수 있으므로 호출 자체가 없어야 한다.
        verifyNoInteractions(reversalRepository);
    }

    @Test
    @DisplayName("reversal reread row의 original identity가 다르면 정상 race 결과로 분류하지 않는다")
    void reversal_original_identity_mismatch_is_rejected() {
        // finalizeReversal(): reread row의 원거래 식별자가 다르면 정상적인 recovery 경합 결과가 아니다.
        ReversalResultUpdateParam intended = ReversalResultUpdateParam.reversed(
                "R6P6-REV-MISMATCH",
                "R6P6-REV-ORIGINAL",
                1,
                "VAN-REV-007",
                "REVERSAL-APPROVAL-005"
        );
        when(reversalRepository.updateRecoverableToFinal(intended)).thenReturn(Optional.empty());
        when(reversalRepository.findByReversalPosTrx(intended.reversalPosTrx()))
                .thenReturn(Optional.of(new PaymentReversal(
                        intended.reversalPosTrx(),
                        "R6P6-REV-OTHER-ORIGINAL",
                        2,
                        1000,
                        ReversalStatus.REVERSED,
                        "VAN-REV-OTHER",
                        "REVERSAL-APPROVAL-OTHER",
                        null
                )));

        // result: CURRENT_TRX_NO는 같지만 원거래 identity가 다르므로 정상적인 idempotent/conflict 결과가 아니다.
        assertThatThrownBy(() -> service.finalizeReversal(intended))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("RECOVERY_CANCEL_TARGET_IDENTITY_MISMATCH");
    }

    private PaymentAttemptUpdatedRow updatedAttempt(
            AttemptResultUpdateParam intended,
            PaymentFinalStatus dbStatus
    ) {
        return new PaymentAttemptUpdatedRow(
                intended.posTrx(),
                intended.attemptSeq(),
                dbStatus,
                intended.approvalNo(),
                intended.declineCode(),
                "411111",
                "1111",
                "VISA",
                intended.vanTrxId()
        );
    }

    private PaymentAttempt attempt(PaymentFinalStatus status) {
        String finalStatus = status == PaymentFinalStatus.PROCESSING ? null : status.name();
        return new PaymentAttempt(
                finalStatus,
                status == PaymentFinalStatus.APPROVED ? "APPROVAL-EXISTING" : null,
                status == PaymentFinalStatus.DECLINED ? "05" : null,
                "411111",
                "1111",
                "VISA",
                "fingerprint",
                1,
                1000,
                "VAN-EXISTING"
        );
    }

    private PaymentCancel cancel(CancelResultUpdateParam intended, CancelStatus status) {
        return new PaymentCancel(
                intended.posTrx(),
                intended.originalPosTrx(),
                intended.originalAttemptSeq(),
                status,
                intended.vanCancelTrxId(),
                intended.cancelApprovalNo(),
                intended.declineCode()
        );
    }

    private PaymentReversal reversal(ReversalResultUpdateParam intended, ReversalStatus status) {
        return new PaymentReversal(
                intended.reversalPosTrx(),
                intended.originalPosTrx(),
                intended.originalAttemptSeq(),
                1000,
                status,
                intended.vanReversalTrxId(),
                intended.reversalApprovalNo(),
                intended.declineCode()
        );
    }
}
