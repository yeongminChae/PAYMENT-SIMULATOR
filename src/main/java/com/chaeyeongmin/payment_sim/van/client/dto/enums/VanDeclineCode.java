package com.chaeyeongmin.payment_sim.van.client.dto.enums;

import lombok.RequiredArgsConstructor;

/**
 * VAN 거절/실패 코드(시뮬레이터용)
 * - 실제 VAN 연동 시엔 VAN 스펙에 맞춰 확장/교체 가능
 */
@RequiredArgsConstructor
public enum VanDeclineCode {

    DO_NOT_HONOR("05"),
    TIMEOUT("TIMEOUT"),
    INVALID_REQUEST("INVALID_REQUEST");

    private final String code;

    public String code() {
        return code;
    }
}
