package com.chaeyeongmin.payment_sim.domain.policy.card;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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

    @Test
    @DisplayName("fingerprint는 64자리 hex 문자열이다")
    void fingerprint_shouldBe64HexCharacters() {
        String fingerprint = policy.generate(CARD_NO);

        assertThat(fingerprint).matches("^[0-9a-f]{64}$");
    }

    @Test
    @DisplayName("같은 카드에서 생성한 fingerprint는 일치한다")
    void sameCardFingerprint_shouldMatch() {
        String storedFingerprint = policy.generate(CARD_NO);
        String candidateFingerprint = policy.generate(CARD_NO);

        assertThat(policy.matchesFingerprint(candidateFingerprint, storedFingerprint)).isTrue();
    }

    @Test
    @DisplayName("다른 카드에서 생성한 fingerprint는 일치하지 않는다")
    void differentCardFingerprint_shouldNotMatch() {
        String storedFingerprint = policy.generate(CARD_NO);
        String candidateFingerprint = policy.generate("4111111111111111");

        assertThat(policy.matchesFingerprint(candidateFingerprint, storedFingerprint)).isFalse();
    }

    @Test
    @DisplayName("저장된 fingerprint가 없으면 일치하지 않는다")
    void missingStoredFingerprint_shouldNotMatch() {
        String candidateFingerprint = policy.generate(CARD_NO);

        assertThat(policy.matchesFingerprint(candidateFingerprint, null)).isFalse();
        assertThat(policy.matchesFingerprint(candidateFingerprint, " ")).isFalse();
    }

    @Test
    @DisplayName("secret key가 blank이면 생성자에서 거절한다")
    void blankSecretKey_shouldThrowIllegalArgumentException() {
        assertThatThrownBy(() -> new CardFingerprintPolicy(" "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("fingerprint secret must not be blank");
    }

    @Test
    @DisplayName("secret key가 32바이트 미만이면 생성자에서 거절한다")
    void shortSecretKey_shouldThrowIllegalArgumentException() {
        assertThatThrownBy(() -> new CardFingerprintPolicy("short-secret-key"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("fingerprint secret must be at least 32 bytes");
    }
}
