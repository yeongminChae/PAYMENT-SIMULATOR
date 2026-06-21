package com.chaeyeongmin.payment_sim.domain.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
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

    @Test
    @DisplayName("문자열 출력 시 카드번호 원문을 숨기고 마지막 4자리만 노출한다")
    void toString_shouldMaskRawCardNoAndExposeOnlyLast4() {
        // given
        CardNumber cardNumber = cardNumber();

        // when
        String actual = cardNumber.toString();

        // then
        assertThat(actual)
                .doesNotContain(CARD_NO)
                .contains("************" + CARD_LAST4);
    }

    @Test
    @DisplayName("카드번호가 null이거나 짧아도 문자열 출력 시 원문을 노출하지 않는다")
    void toString_nullOrShortCardNo_shouldMaskSafely() {
        assertThat(new CardNumber(null).toString()).contains("value=null");
        assertThat(new CardNumber("123").toString())
                .doesNotContain("123")
                .contains("value=****");
    }
}
