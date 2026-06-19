package com.chaeyeongmin.payment_sim.domain.model;

class CardNumberTest {

    private static final String CARD_NO = "4242424242424242";
    private static final String CARD_BIN8 = "42424242";
    private static final String CARD_LAST4 = "4242";

    private CardNumber cardNumber() {
        return new CardNumber(CARD_NO);
    }
}
