package com.chaeyeongmin.payment_sim.domain.policy;

import com.chaeyeongmin.payment_sim.api.payment.dto.enums.PaymentFinalStatus;
import com.chaeyeongmin.payment_sim.domain.model.CardNumber;
import com.chaeyeongmin.payment_sim.domain.model.PaymentAttempt;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 원승인 attempt에 저장된 최소 카드정보와 취소 요청 카드의 일치 여부만 검증한다.
 */
class CancelCardMatchPolicyTest {

    private static final String CARD_NO = "4242424242424242";
    private static final String CARD_BIN8 = "42424242";
    private static final String CARD_LAST4 = "4242";

    private CancelCardMatchPolicy policy;

    @BeforeEach
    void setUp() {
        policy = new CancelCardMatchPolicy();
    }

    @Test
    @DisplayName("원승인 BIN과 마지막 4자리가 취소 카드와 같으면 일치한다")
    void matches_sameBinAndLast4_shouldReturnTrue() {
        // given
        PaymentAttempt originalAttempt = originalAttempt(CARD_BIN8, CARD_LAST4);
        CardNumber cancelCard = cancelCard(CARD_NO);

        // when
        boolean matches = policy.matchesOriginalCard(originalAttempt, cancelCard);

        // then
        assertTrue(matches);
    }

    @Test
    @DisplayName("원승인 BIN이 다르면 일치하지 않는다")
    void matches_differentBin_shouldReturnFalse() {
        // given
        PaymentAttempt originalAttempt = originalAttempt("41111111", CARD_LAST4);
        CardNumber cancelCard = cancelCard(CARD_NO);

        // when
        boolean matches = policy.matchesOriginalCard(originalAttempt, cancelCard);

        // then
        assertFalse(matches);
    }

    @Test
    @DisplayName("원승인 마지막 4자리가 다르면 일치하지 않는다")
    void matches_differentLast4_shouldReturnFalse() {
        // given
        PaymentAttempt originalAttempt = originalAttempt(CARD_BIN8, "1111");
        CardNumber cancelCard = cancelCard(CARD_NO);

        // when
        boolean matches = policy.matchesOriginalCard(originalAttempt, cancelCard);

        // then
        assertFalse(matches);
    }

    @Test
    @DisplayName("원승인 정보가 없으면 일치하지 않는다")
    void matches_nullOriginalAttempt_shouldReturnFalse() {
        // given
        PaymentAttempt originalAttempt = null;
        CardNumber cancelCard = cancelCard(CARD_NO);

        // when
        boolean matches = policy.matchesOriginalCard(originalAttempt, cancelCard);

        // then
        assertFalse(matches);
    }

    @Test
    @DisplayName("취소 카드 정보가 없으면 일치하지 않는다")
    void matches_nullCancelCard_shouldReturnFalse() {
        // given
        PaymentAttempt originalAttempt = originalAttempt(CARD_BIN8, CARD_LAST4);
        CardNumber cancelCard = null;

        // when
        boolean matches = policy.matchesOriginalCard(originalAttempt, cancelCard);

        // then
        assertFalse(matches);
    }

    private PaymentAttempt originalAttempt(String cardBin, String cardLast4) {
        // 카드 일치 정책은 cardBin/cardLast4만 보므로 나머지 승인 필드는 정상 fixture로 고정한다.
        return new PaymentAttempt(
                PaymentFinalStatus.APPROVED.name(),
                "A207076083",
                null,
                cardBin,
                cardLast4,
                "test-card-fingerprint",
                1,
                10000,
                "2376-20260519-9991-1001-01"
        );
    }

    private CardNumber cancelCard(String cardNo) {
        // validator를 통과한 취소 요청 카드번호를 정책 입력값으로 변환한 상태를 표현한다.
        return new CardNumber(cardNo);
    }
}
