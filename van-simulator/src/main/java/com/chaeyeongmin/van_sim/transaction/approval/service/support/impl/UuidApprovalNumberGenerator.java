package com.chaeyeongmin.van_sim.transaction.approval.service.support.impl;

import com.chaeyeongmin.van_sim.transaction.approval.service.support.ApprovalNumberGenerator;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * UUID 값을 기반으로 VAN 승인번호를 생성하는 기본 구현체다.
 * <p>
 * 승인번호는 하이픈을 제거한 UUID 앞 12자리를 대문자로 사용한다.
 */
@Component
public class UuidApprovalNumberGenerator implements ApprovalNumberGenerator {

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
