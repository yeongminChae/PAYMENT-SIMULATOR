package com.chaeyeongmin.payment_sim.api.payment.dto.card;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * [DTO] 카드 입력(CardInput)
 * <p>
 * 목적:
 * - 승인 요청에서 카드 유효성 검증 및 VAN 시뮬레이터 룰 판단을 위해 사용
 * <p>
 * 필드:
 * - pan        : 13~19자리 숫자 + Luhn 체크 대상 (민감정보)
 * - expiryYyMm : YYMM (예: 2601)
 * <p>
 * 보안:
 * - pan은 절대 로그/DB에 원문 저장 금지
 */

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class CardInput {

    @NotBlank(message = "pan은 필수입니다.")
    @Pattern(regexp = "^\\d{13,19}$", message = "pan은 13~19자리 숫자여야 합니다.")
    private String pan;

    @NotBlank(message = "expiryYyMm은 필수입니다.")
    @Pattern(regexp = "^\\d{4}$", message = "expiryYyMm은 YYMM 4자리 숫자여야 합니다.")
    private String expiryYyMm;
    
}