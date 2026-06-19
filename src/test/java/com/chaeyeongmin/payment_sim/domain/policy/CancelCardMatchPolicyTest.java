package com.chaeyeongmin.payment_sim.domain.policy;

import com.chaeyeongmin.payment_sim.api.payment.dto.enums.PaymentFinalStatus;
import com.chaeyeongmin.payment_sim.domain.model.CardNumber;
import com.chaeyeongmin.payment_sim.domain.model.PaymentAttempt;
import org.junit.jupiter.api.BeforeEach;

class CancelCardMatchPolicyTest {

    private static final String CARD_NO = "4242424242424242";
    private static final String CARD_BIN8 = "42424242";
    private static final String CARD_LAST4 = "4242";

    private CancelCardMatchPolicy policy;

    @BeforeEach
    void setUp() {
        policy = new CancelCardMatchPolicy();
    }

    private PaymentAttempt originalAttempt(String cardBin, String cardLast4) {
        return new PaymentAttempt(
                PaymentFinalStatus.APPROVED.name(),
                "A207076083",
                null,
                cardBin,
                cardLast4,
                1,
                10000,
                "2376-20260519-9991-1001-01"
        );
    }

    private CardNumber cancelCard(String cardNo) {
        return new CardNumber(cardNo);
    }
}
