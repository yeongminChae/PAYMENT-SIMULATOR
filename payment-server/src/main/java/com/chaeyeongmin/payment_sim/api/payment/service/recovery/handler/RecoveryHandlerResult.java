package com.chaeyeongmin.payment_sim.api.payment.service.recovery.handler;

/**
 * 거래별 Handler가 한 번의 복구 시도에서 관측한 결과다.
 *
 * <p>{@code observedStatus}는 VAN에서 확인했거나 finalizer에 반영하려 한 상태이고,
 * {@code dbStatus}는 Handler의 선행 재조회 또는 finalizer의 conditional update 이후 확인한 DB 상태다.
 * 대상 부재처럼 상태를 확인할 수 없는 경우에는 해당 값이 null일 수 있다.
 *
 * <p>이 결과는 Recovery Task의 lifecycle 상태가 아니다. Worker가 이 값을 해석해 task를
 * RESOLVED로 끝낼지, 재시도할지, 수동 검토로 보낼지를 후속 단계에서 결정한다.
 */
public record RecoveryHandlerResult(
        RecoveryHandlerResultType resultType,
        String observedStatus,
        String dbStatus
) {
}
