package com.chaeyeongmin.payment_sim.api.payment.validate;

import com.chaeyeongmin.payment_sim.api.payment.dto.request.ApproveRequest;
import com.chaeyeongmin.payment_sim.api.payment.validate.enums.ApproveValidationError;
import com.chaeyeongmin.payment_sim.common.policy.CardValidationPolicy;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class ApproveRequestValidator {

    private final CardValidationPolicy cardValidationPolicy;

    public void validate(ApproveRequest request) {

        // 승인 요청 검증의 단일 진입점.
        // - 실패 시: ApproveValidationError.code()를 메시지로 예외를 던져 상위(서비스/컨트롤러)에서 공통 처리하게 한다.
        // - 성공 시: 아무것도 하지 않고 통과한다(예외 없음).
        ApproveValidationError error = validateAndGetError(request);

        if (error != null) {
            // TODO: 추후 BusinessException + result_code 표준으로 교체 예정
            throw new IllegalArgumentException(error.code());
        }
    }

    /**
     * 승인 요청에서 "가장 기본적인 유효성"을 검사하고,
     * 실패 원인을 ApproveValidationError로 반환한다.
     * <p>
     * 반환 규칙:
     * - null  : OK (검증 통과)
     * - error : 해당 error.code()를 예외 메시지로 사용할 수 있음
     * <p>
     * 주의:
     * - 여기서는 "첫 번째로 발견된 오류"만 반환한다. (에러 누적/리스트 반환 안 함)
     */
    private ApproveValidationError validateAndGetError(ApproveRequest request) {

        // request 자체가 없으면 이후 로직을 진행할 수 없음
        if (request == null) return ApproveValidationError.INVALID_REQUEST;

        // 거래번호(포스TR) 필수/공백 방지
        if (isNotNullOrBlank(request.getPosTrx()) == false)
            return ApproveValidationError.INVALID_POS_TRX;

        // 승인 금액은 0 초과만 허용
        if (request.getAmount() <= 0)
            return ApproveValidationError.INVALID_AMOUNT;

        // 카드 입력은 별도 함수에서 상세 검증(형식/만료/Luhn)
        if (cardValidationPolicy.isValidCard(request.getCard()) == false)
            return ApproveValidationError.INVALID_CARD;

        return null; // OK
    }

    /**
     * 문자열이 null이 아니고, 공백만으로 이루어지지 않았는지 검사한다.
     * (검증 로직에서 반복되는 null/blank 체크를 단순화하기 위한 유틸)
     */
    private boolean isNotNullOrBlank(String str) {
        return str != null && str.isBlank() == false;
    }

}