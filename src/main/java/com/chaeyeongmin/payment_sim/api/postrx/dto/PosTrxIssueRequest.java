package com.chaeyeongmin.payment_sim.api.postrx.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/**
 * 포스TR 발급 요청 DTO.
 * issue/eot 모두 같은 채번 키를 사용한다.
 */
public record PosTrxIssueRequest(
        @NotBlank(message = "storeCd는 필수입니다.")
        @Pattern(regexp = "^\\d{4}$", message = "storeCd는 4자리 숫자여야 합니다.")
        String storeCd,

        @NotBlank(message = "bizDate는 필수입니다.")
        @Pattern(regexp = "^\\d{8}$", message = "bizDate는 yyyymmdd 8자리여야 합니다.")
        String bizDate,

        @NotBlank(message = "posNo는 필수입니다.")
        @Pattern(regexp = "^\\d{4}$", message = "posNo는 4자리 숫자여야 합니다.")
        String posNo
) {
}
