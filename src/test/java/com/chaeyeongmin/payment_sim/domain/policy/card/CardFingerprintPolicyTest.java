package com.chaeyeongmin.payment_sim.domain.policy.card;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 카드 fingerprint가 PAN을 직접 노출하지 않으면서 동일 카드를 안정적으로 식별하는지 검증한다.
 * Spring 설정과 분리된 순수 단위 테스트를 위해 테스트 전용 고정 키를 직접 주입한다.
 */
class CardFingerprintPolicyTest {

    private static final String SECRET_KEY = "card-fingerprint-test-secret-key";
    private static final String CARD_NO = "4242424242424242";

    private CardFingerprintPolicy policy;

    @BeforeEach
    void setUp() {
        policy = new CardFingerprintPolicy(SECRET_KEY);
    }

    @Test
    @DisplayName("같은 카드번호는 같은 fingerprint를 생성한다")
    void sameCardNo_shouldGenerateSameFingerprint() {
        // when
        String firstFingerprint = policy.generate(CARD_NO);
        String secondFingerprint = policy.generate(CARD_NO);

        // then
        assertThat(firstFingerprint).isEqualTo(secondFingerprint);
    }

    @Test
    @DisplayName("다른 카드번호는 다른 fingerprint를 생성한다")
    void differentCardNo_shouldGenerateDifferentFingerprint() {
        // given
        String differentCardNo = "4111111111111111";

        // when
        String fingerprint = policy.generate(CARD_NO);
        String differentFingerprint = policy.generate(differentCardNo);

        // then
        assertThat(fingerprint).isNotEqualTo(differentFingerprint);
    }

    @Test
    @DisplayName("fingerprint는 카드번호 원문을 포함하지 않는다")
    void fingerprint_shouldNotContainRawCardNo() {
        // when
        String fingerprint = policy.generate(CARD_NO);

        // then
        assertThat(fingerprint).doesNotContain(CARD_NO);
    }
}
