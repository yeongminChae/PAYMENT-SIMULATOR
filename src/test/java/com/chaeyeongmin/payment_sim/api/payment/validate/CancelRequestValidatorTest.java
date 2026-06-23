package com.chaeyeongmin.payment_sim.api.payment.validate;

import com.chaeyeongmin.payment_sim.api.payment.dto.request.CancelRequest;
import com.chaeyeongmin.payment_sim.common.api.ResultCode;
import com.chaeyeongmin.payment_sim.common.exception.BusinessException;
import com.chaeyeongmin.payment_sim.common.policy.CardValidationPolicy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 취소 요청 DTO의 cardNo 형식 검증만 다룬다.
 * 원승인 카드와 취소 카드의 fingerprint 일치 여부는 CardFingerprintPolicyTest와 취소 서비스 테스트에서 검증한다.
 */
class CancelRequestValidatorTest {

    private static final String VALID_POS_TRX = "2376-20260519-9991-2001";
    private static final String VALID_ORIGINAL_POS_TRX = "2376-20260519-9991-1001";
    private static final int VALID_ORIGINAL_ATTEMPT_SEQ = 1;
    private static final String VALID_CARD_NO = "4111111111111111";
    private static final String INVALID_CARD_MESSAGE = "INVALID_CARD";

    private CancelRequestValidator validator;

    @BeforeEach
    void setUp() {
        CardValidationPolicy cardValidationPolicy = new CardValidationPolicy();
        validator = new CancelRequestValidator(cardValidationPolicy);
    }

    @Test
    @DisplayName("[UT-2.1-CANCEL-CARD-001-6] 정상 cardNo이면 예외가 발생하지 않는다")
    void validCardNo_shouldPass() {
        // given
        CancelRequest request = requestWithCardNo(VALID_CARD_NO);

        // when
        Executable actual = () -> validator.validate(request);

        // then
        assertDoesNotThrow(actual);
    }

    @Test
    @DisplayName("[UT-2.1-CANCEL-CARD-001-1] cardNo가 null이면 INVALID_CARD 예외가 발생한다")
    void nullCardNo_shouldThrowInvalidCard() {
        // given
        CancelRequest request = requestWithCardNo(null);

        // when
        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> validator.validate(request)
        );

        // then
        assertInvalidCard(exception);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   "})
    @DisplayName("[UT-2.1-CANCEL-CARD-001-2] cardNo가 blank이면 INVALID_CARD 예외가 발생한다")
    void blankCardNo_shouldThrowInvalidCard(String cardNo) {
        // given
        CancelRequest request = requestWithCardNo(cardNo);

        // when
        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> validator.validate(request)
        );

        // then
        assertInvalidCard(exception);
    }

    @ParameterizedTest
    @ValueSource(strings = {"411111111111111", "41111111111111112"})
    @DisplayName("[UT-2.1-CANCEL-CARD-001-3] cardNo가 16자리가 아니면 INVALID_CARD 예외가 발생한다")
    void invalidLengthCardNo_shouldThrowInvalidCard(String cardNo) {
        // given
        CancelRequest request = requestWithCardNo(cardNo);

        // when
        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> validator.validate(request)
        );

        // then
        assertInvalidCard(exception);
    }

    @Test
    @DisplayName("[UT-2.1-CANCEL-CARD-001-4] cardNo에 숫자가 아닌 문자가 있으면 INVALID_CARD 예외가 발생한다")
    void nonNumericCardNo_shouldThrowInvalidCard() {
        // given
        CancelRequest request = requestWithCardNo("411111111111111A");

        // when
        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> validator.validate(request)
        );

        // then
        assertInvalidCard(exception);
    }

    @Test
    @DisplayName("[UT-2.1-CANCEL-CARD-001-8] 전각 숫자 cardNo이면 INVALID_CARD 예외가 발생한다")
    void unicodeDigitCardNo_shouldThrowInvalidCard() {
        // given
        CancelRequest request = requestWithCardNo("４１１１１１１１１１１１１１１１");

        // when
        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> validator.validate(request)
        );

        // then
        assertInvalidCard(exception);
    }

    @Test
    @DisplayName("[UT-2.1-CANCEL-CARD-001-5] cardNo가 16자리 숫자여도 Luhn 검증에 실패하면 INVALID_CARD 예외가 발생한다")
    void luhnFailCardNo_shouldThrowInvalidCard() {
        // given
        CancelRequest request = requestWithCardNo("4111111111111112");

        // when
        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> validator.validate(request)
        );

        // then
        assertInvalidCard(exception);
    }

    @Test
    @DisplayName("[UT-2.1-CANCEL-CARD-001-7] 실패 메시지에 카드번호 원문이 포함되지 않는다")
    void invalidCardNoMessage_shouldNotContainRawCardNo() {
        // given
        // 실패한 카드번호가 예외 메시지에 그대로 노출되면 PAN 유출 위험이 있다.
        String invalidCardNo = "4111111111111112";
        CancelRequest request = requestWithCardNo(invalidCardNo);

        // when
        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> validator.validate(request)
        );

        // then
        assertInvalidCard(exception);
        assertThat(exception.getMessage()).doesNotContain(invalidCardNo);
    }

    private CancelRequest requestWithCardNo(String cardNo) {
        // 이 테스트의 관심사는 cardNo 검증이므로 나머지 필드는 정상값으로 고정한다.
        return new CancelRequest(
                VALID_POS_TRX,
                VALID_ORIGINAL_POS_TRX,
                VALID_ORIGINAL_ATTEMPT_SEQ,
                cardNo
        );
    }

    private void assertInvalidCard(BusinessException exception) {
        assertThat(exception.getResultCode()).isEqualTo(ResultCode.INVALID);
        assertThat(exception.getMessage()).isEqualTo(INVALID_CARD_MESSAGE);
    }

}
