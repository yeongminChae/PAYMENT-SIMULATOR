package com.chaeyeongmin.payment_sim.api.payment.service.impl;

import com.chaeyeongmin.payment_sim.api.payment.dto.ApproveRequest;
import com.chaeyeongmin.payment_sim.api.payment.dto.card.CardInput;
import com.chaeyeongmin.payment_sim.api.payment.service.PaymentApprovalService;
import com.chaeyeongmin.payment_sim.api.payment.validate.ApproveRequestValidator;
import com.chaeyeongmin.payment_sim.api.payment.validate.ApproveValidationError;
import com.chaeyeongmin.payment_sim.infra.repository.PaymentAttemptRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

class PaymentApprovalServiceImplValidateTest {

    // UT-도메인-기능-일련번호 (Approve Validation)
    private static final String UT_ID_A_VAL_001 = "UT-PAYMENT-APPROVE-VAL-001"; // PAN 형식
    private static final String UT_ID_A_VAL_002 = "UT-PAYMENT-APPROVE-VAL-002"; // Luhn 실패
    private static final String UT_ID_A_VAL_003 = "UT-PAYMENT-APPROVE-VAL-003"; // 성공(통과)

    private PaymentApprovalService service;
    private PaymentAttemptRepository repo;
    private ApproveRequestValidator validator;

    // 기본 정상 요청 (필드 세팅은 각 테스트에서 수정해서 사용)
    private ApproveRequest baseReq;

    @BeforeEach
    void setUp() {
        repo = mock(PaymentAttemptRepository.class);
        validator = new ApproveRequestValidator();
        service = new PaymentApprovalServiceImpl(repo, validator);

        baseReq = new ApproveRequest(
                "2376-20260118-9991-0023",
                10000,
                new CardInput("4111111111111111", "2812") // 정상 Luhn 예시
        );
    }

    /**
     * [UT_ID] UT-PAYMENT-APPROVE-VAL-001
     * <p>
     * [시나리오]
     * - Given: PAN이 길이 조건은 만족하지만, 숫자로만 구성되어야 한다는 조건을 만족하지 않는다.
     * - When: validator.validate() 호출
     * - Then: INVALID 예외
     * - And  : validate 단계에서 차단되므로 Repo 호출이 없어야 한다
     */
    @Test
    @DisplayName("[" + UT_ID_A_VAL_001 + "] BIN 형식 실패 → INVALID")
    void approve_validate_panNotNumeric_shouldThrowInvalid() {
        // given
        baseReq.setCard(new CardInput("abcdef1111111111", "2812"));

        // when
        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> validator.validate(baseReq)
        );

        // then
        assertEquals(ApproveValidationError.INVALID_CARD.code(), exception.getMessage());
        verifyNoInteractions(repo);
    }

    /**
     * [UT_ID] UT-PAYMENT-APPROVE-VAL-002
     * <p>
     * [시나리오]
     * - Given: PAN은 숫자/길이 조건은 만족하지만 Luhn 검증에 실패한다
     * - When : approve() 호출
     * - Then : 서비스는 INVALID 성격의 예외를 던진다
     * - And  : validate 단계에서 차단되므로 Repo 호출이 없어야 한다
     */
    @Test
    @DisplayName("[" + UT_ID_A_VAL_002 + "] PAN Luhn 실패 → INVALID")
    void approve_validate_panLuhnFail_shouldThrowInvalid() {
        // given
        // "4111111111111112" (예: Luhn 실패하는 16자리 숫자)
        baseReq.setCard(new CardInput("4111111111111112", "2812"));

        // when
        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> validator.validate(baseReq)
        );

        // then
        assertEquals(ApproveValidationError.INVALID_CARD.code(), exception.getMessage());
        verifyNoInteractions(repo);
    }

    /**
     * [UT_ID] UT-PAYMENT-APPROVE-VAL-003
     * <p>
     * [시나리오]
     * - Given: BIN(6자리) + Luhn 모두 통과하는 정상 PAN이다
     * - When : approve() 호출
     * - Then : validate 단계는 예외 없이 통과한다
     * - And  : 현재 approve()가 attempt 발급까지 수행한다면 repo가 호출될 수 있다
     * (검증만 보고 싶으면 "validate만 분리한 메소드"를 테스트 대상으로 바꾸는 게 가장 깔끔)
     */
    @Test
    @DisplayName("[" + UT_ID_A_VAL_003 + "] 정상 PAN → validate 통과")
    void approve_validate_success_shouldPass() {
        // given
        // - baseReq 그대로 사용 (정상 PAN)

        // when
        service.approve(baseReq);

        // then
        assertDoesNotThrow(() -> validator.validate(baseReq));
        // 현재는 validate만 분리했으므로 verifyNoInteractions 으로 검증
        verifyNoInteractions(repo);

        /*
            TODO
            - approve가 attempt 발급까지 하면:
            verify(repo, times(1))
         */

    }

}