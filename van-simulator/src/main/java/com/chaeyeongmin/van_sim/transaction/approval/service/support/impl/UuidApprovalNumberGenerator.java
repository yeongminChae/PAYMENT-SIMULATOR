package com.chaeyeongmin.van_sim.transaction.approval.service.support.impl;

import com.chaeyeongmin.van_sim.transaction.approval.service.support.ApprovalNumberGenerator;
import org.springframework.stereotype.Component;

import java.util.UUID;

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
