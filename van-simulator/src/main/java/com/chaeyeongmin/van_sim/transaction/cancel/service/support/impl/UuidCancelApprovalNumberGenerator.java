package com.chaeyeongmin.van_sim.transaction.cancel.service.support.impl;

import com.chaeyeongmin.van_sim.transaction.cancel.service.support.CancelApprovalNumberGenerator;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * UUID 값을 기반으로 VAN 취소 승인번호를 생성하는 기본 구현체다.
 */
@Component
public class UuidCancelApprovalNumberGenerator implements CancelApprovalNumberGenerator {

    private static final int APPROVAL_NO_LENGTH = 12;

    @Override
    public String generate() {
        return UUID.randomUUID()
                .toString()
                .replace("-", "")
                .substring(0, APPROVAL_NO_LENGTH)
                .toUpperCase();
    }
}
