package com.chaeyeongmin.payment_sim.api.payment.service.support;

import com.chaeyeongmin.payment_sim.api.payment.dto.card.CardSummary;

public final class CardSummaryFactory {

    private CardSummaryFactory() {
    }

    public static CardSummary fromStoredCard(String cardBin, String cardLast4) {
        return new CardSummary(cardBin, cardLast4, null);
    }

}
