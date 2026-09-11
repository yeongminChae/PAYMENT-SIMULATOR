package com.chaeyeongmin.payment_sim.api.payment.service.recovery.worker;

import com.chaeyeongmin.payment_sim.api.payment.service.recovery.handler.RecoveryHandlerResult;

/**
 * RecoveryWorker의 한 번 실행 결과다.
 *
 * @param resultType Worker 관점의 처리 결과
 * @param taskId 처리한 Recovery Task ID; 실행 가능한 task가 없으면 null
 * @param handlerResult 거래별 Handler가 반환한 상세 결과; Handler를 호출하지 않았다면 null
 */
public record RecoveryWorkerResult(
        RecoveryWorkerResultType resultType,
        Long taskId,
        RecoveryHandlerResult handlerResult
) {
}
