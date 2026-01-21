package com.chaeyeongmin.payment_sim.domain.policy;

public enum AttemptStatus {
    // NOTE: FINAL_STATUS는 NULL=처리중, 나머지는 문자열로 저장(문서 기준)
    APPROVED,
    DECLINED,
    UNKNOWN_TIMEOUT
}