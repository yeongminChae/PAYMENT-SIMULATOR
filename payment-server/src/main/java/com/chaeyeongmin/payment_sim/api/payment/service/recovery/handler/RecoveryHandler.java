package com.chaeyeongmin.payment_sim.api.payment.service.recovery.handler;

import com.chaeyeongmin.payment_sim.api.payment.service.RecoveryFinalizationService;
import com.chaeyeongmin.payment_sim.domain.model.RecoveryTask;
import com.chaeyeongmin.payment_sim.domain.policy.RecoveryTargetType;

/**
 * Recovery Worker와 거래 종류별 복구 로직 사이의 경계다.
 *
 * <p>Worker는 {@link #targetType()}으로 task를 처리할 구현체를 선택하고,
 * Handler는 대상 거래를 현재 DB 상태로 다시 확인한 뒤 필요할 때만 VAN에 최종 사실을 조회한다.
 * Handler는 복구 시도 결과만 반환하며 RETRY_WAIT, retryCount, backoff 같은
 * Recovery Task lifecycle 정책은 Worker 계층에서 결정한다.
 *
 * <p>{@link #handle(RecoveryTask)} 전체에 transaction을 열지 않는다.
 * VAN 외부 I/O 동안 DB transaction을 유지하지 않고, terminal 사실을 반영하는 짧은 transaction은
 * RecoveryFinalizationService가 담당한다.
 */
public interface RecoveryHandler {

    /**
     * 이 Handler가 처리할 수 있는 Recovery Task의 target 종류를 반환한다.
     */
    RecoveryTargetType targetType();

    /**
     * claim된 task가 가리키는 실제 거래를 재조회하고 이번 자동 복구 시도의 결과를 반환한다.
     *
     * <p>task가 가진 과거 상태를 신뢰하지 않고 DB를 다시 읽어, 이미 terminal이면 VAN 호출 없이
     * 종료한다. 아직 unresolved일 때만 VAN Inquiry를 수행하고, 확인된 terminal 사실의 DB 반영은
     * RecoveryFinalizationService에 위임한다.
     */
    RecoveryHandlerResult handle(RecoveryTask task);
}
