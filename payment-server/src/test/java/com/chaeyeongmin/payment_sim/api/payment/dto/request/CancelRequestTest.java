package com.chaeyeongmin.payment_sim.api.payment.dto.request;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CancelRequestTest {

    private static final String CARD_NO = "4242424242424242";

    @Test
    @DisplayName("문자열 출력 시 카드번호 원문을 숨기고 마지막 4자리만 노출한다")
    void toString_shouldMaskRawCardNoAndExposeOnlyLast4() {
        CancelRequest request = request(CARD_NO);

        String actual = request.toString();

        assertThat(actual)
                .doesNotContain(CARD_NO)
                .contains("cardNo=************4242");
    }

    @Test
    @DisplayName("cardNo가 null이거나 짧아도 문자열 출력 시 원문을 노출하지 않는다")
    void toString_nullOrShortCardNo_shouldMaskSafely() {
        assertThat(request(null).toString()).contains("cardNo=null");
        assertThat(request("123").toString())
                .doesNotContain("123")
                .contains("cardNo=****");
    }

    private CancelRequest request(String cardNo) {
        return new CancelRequest(
                "2376-20260519-9991-2001",
                "2376-20260519-9991-1001",
                1,
                cardNo
        );
    }
}
