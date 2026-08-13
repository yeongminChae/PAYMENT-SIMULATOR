package com.chaeyeongmin.van_sim.transaction.approval.service.support.impl;

import com.chaeyeongmin.van_sim.transaction.approval.service.support.VanTransactionIdGenerator;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * UUID 값을 기반으로 VAN 내부 거래번호를 생성하는 기본 구현체다.
 * <p>
 * 생성된 식별자는 VAN 거래임을 구분할 수 있도록 {@code VAN-} 접두사를 붙인다.
 */
@Component
public class UuidVanTransactionIdGenerator implements VanTransactionIdGenerator {

    @Override
    public String generate() {
        return "VAN-" + UUID.randomUUID();
    }

}
