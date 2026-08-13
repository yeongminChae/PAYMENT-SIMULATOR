package com.chaeyeongmin.van_sim.control.scenario.approval.model;

/**
 * 승인 시뮬레이션에서 VAN 통신 계층이 보여줄 전송 동작을 표현한다.
 */
public enum TransportBehavior {
    NORMAL,
    DROP_RESPONSE,
    DELAY,
    DISCONNECT
}
