package com.chaeyeongmin.payment_sim.van.client.dto;

import lombok.Builder;

@Builder
public record VanInquiryRequest(
        VanInquiryTargetType targetType,
        String targetTrxNo,
        Integer targetAttemptSeq,
        String vanTrxId,
        String cardLast4
) {

    public String posTrx() {
        return targetTrxNo;
    }

    public int attemptSeq() {
        return targetAttemptSeq == null ? 0 : targetAttemptSeq;
    }
}
