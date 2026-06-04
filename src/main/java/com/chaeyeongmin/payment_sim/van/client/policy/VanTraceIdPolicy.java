package com.chaeyeongmin.payment_sim.van.client.policy;

import org.springframework.stereotype.Component;

@Component
public class VanTraceIdPolicy {

    public String resolveVanTrxId(
            String posTrx,
            int attemptSeq,
            String storedVanTrxId
    ) {

        return storedVanTrxId != null && storedVanTrxId.isBlank() == false
                ? storedVanTrxId
                : "VAN-" + posTrx + "-" + String.format("%02d", attemptSeq)
        ;

    }

}
