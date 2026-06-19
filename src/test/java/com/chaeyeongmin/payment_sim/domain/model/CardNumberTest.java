package com.chaeyeongmin.payment_sim.domain.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 취소 카드 비교 정책에서 사용할 PAN 최소 식별값 추출 규칙을 고정한다.
 */
class CardNumberTest {

    private static final String CARD_NO = "4242424242424242";
    private static final String CARD_BIN8 = "42424242";
    private static final String CARD_LAST4 = "4242";

    private CardNumber cardNumber() {
        return new CardNumber(CARD_NO);
    }

    @Test
    @DisplayName("카드번호 앞 8자리를 BIN으로 추출한다")
    void bin8_shouldReturnFirstEightDigits() {
        // given
        CardNumber cardNumber = cardNumber();

        // when
        String bin8 = cardNumber.bin8();

        // then
        assertEquals(CARD_BIN8, bin8);
    }

    @Test
    @DisplayName("카드번호 마지막 4자리를 추출한다")
    void last4_shouldReturnLastFourDigits() {
        // given
        CardNumber cardNumber = cardNumber();

        // when
        String last4 = cardNumber.last4();

        // then
        assertEquals(CARD_LAST4, last4);
    }
}
