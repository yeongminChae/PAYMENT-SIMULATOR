package com.chaeyeongmin.van_sim.control.scenario.approval.model;

/**
 * 승인 시뮬레이션에서 발급사가 반환한 업무 결과를 표현한다.
 */
public enum IssuerResult {
    APPROVED,
    DECLINED,
    ISSUER_TIMEOUT
}
