package com.chaeyeongmin.payment_sim.payment.application.card.support;

import com.chaeyeongmin.payment_sim.payment.api.common.CardSummary;

public final class CardSummaryFactory {

    private CardSummaryFactory() {
    }

    public static CardSummary fromStoredCard(String cardBin, String cardLast4) {
        return new CardSummary(cardBin, cardLast4, null);
    }

    public static CardSummary fromStoredCard(String cardBin, String cardLast4, String cardBrand) {
        return new CardSummary(cardBin, cardLast4, cardBrand);
    }

}
