package com.chaeyeongmin.van_sim.transaction.approval.service.support.impl;

import com.chaeyeongmin.van_sim.transaction.approval.service.support.VanTransactionIdGenerator;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
public class UuidVanTransactionIdGenerator implements VanTransactionIdGenerator {

    @Override
    public String generate() {
        return "VAN-" + UUID.randomUUID();
    }

}
