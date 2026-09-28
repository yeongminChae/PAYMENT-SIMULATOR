package com.chaeyeongmin.payment_sim.api.payment.service;

import com.chaeyeongmin.payment_sim.api.payment.service.transaction.model.RecoveryFinalizeResult;
import com.chaeyeongmin.payment_sim.domain.model.RecoveryTask;
import com.chaeyeongmin.payment_sim.infra.repository.dto.AttemptResultUpdateParam;
import com.chaeyeongmin.payment_sim.infra.repository.dto.CancelResultUpdateParam;
import com.chaeyeongmin.payment_sim.infra.repository.dto.ReversalResultUpdateParam;

/**
 * VAN 조회로 확인한 최종 결과를 결제 원장에 반영한다.
 *
 * <p>반영하기 전에 Recovery Task의 작업 소유권을 다시 확인한다.
 * 현재 Worker가 소유권을 잃었다면 원장은 변경하지 않는다.
 */
public interface RecoveryFinalizationService {

    /** 승인 복구 결과를 PAYMENT_ATTEMPT에 반영한다. */
    RecoveryFinalizeResult finalizeApproval(
            RecoveryTask task,
            AttemptResultUpdateParam intended
    );

    /** 취소 복구 결과를 PAYMENT_CANCEL에 반영한다. */
    RecoveryFinalizeResult finalizeCancel(
            RecoveryTask task,
            CancelResultUpdateParam intended
    );

    /** 망취소 복구 결과를 PAYMENT_REVERSAL에 반영한다. */
    RecoveryFinalizeResult finalizeReversal(
            RecoveryTask task,
            ReversalResultUpdateParam intended
    );

}
