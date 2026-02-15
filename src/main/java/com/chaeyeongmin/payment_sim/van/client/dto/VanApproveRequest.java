package com.chaeyeongmin.payment_sim.van.client.dto;

import com.chaeyeongmin.payment_sim.api.payment.dto.ApproveRequest;
import com.chaeyeongmin.payment_sim.api.payment.dto.card.CardInput;
import lombok.Builder;

@Builder
public record VanApproveRequest(
        String posTrx,
        int attemptSeq,
        int amount,
        String pan,
        String expiryYyMm,
        String cardBin,
        String cardLast4
) {

}