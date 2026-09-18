package com.chaeyeongmin.payment_sim.api.payment.service.recovery.discovery;

/**
 * 한 번의 후보 조회 및 등록 결과다.
 *
 * @param discoveredCount Repository에서 조회된 전체 후보 수
 * @param registeredCount 중복을 제외하고 실제로 새 Recovery Task가 등록된 수
 */
public record RecoveryDiscoveryResult(
        int discoveredCount,
        int registeredCount
) {
}
