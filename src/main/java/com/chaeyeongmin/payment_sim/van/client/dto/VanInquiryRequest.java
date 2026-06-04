package com.chaeyeongmin.payment_sim.van.client.dto;

import lombok.Builder;

@Builder
public record VanInquiryRequest(
        String posTrx,
        int attemptSeq,
        String vanTrxId,
        String cardLast4
) {
}
