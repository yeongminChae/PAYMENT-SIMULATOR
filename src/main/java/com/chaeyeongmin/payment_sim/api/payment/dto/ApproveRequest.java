package com.chaeyeongmin.payment_sim.api.payment.dto;

import com.chaeyeongmin.payment_sim.api.payment.dto.card.CardInput;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * [DTO] 승인 요청(Approve) DTO
 * <p>
 * 목적:
 * - POS가 결제서버에 "승인"을 요청할 때 사용하는 입력 전문
 * <p>
 * 입력 규격(요약):
 * - posTrx : EOT에서 발급된 포스TR(거래번호). 예) 2301-20260121-9999-0022
 * - amount : 승인 금액(0 초과)
 * - card   : 검증용 카드 입력(민감정보 포함)
 * <p>
 * 보안/운영 주의:
 * - card.pan(카드번호)는 민감정보(PAN)로 저장/로그 출력 금지
 * - 본 DTO는 검증용 입력이며, 저장 시에는 BIN/last4 등 최소정보만 저장할 것
 * <p>
 * 비고:
 * - attemptSeq는 클라이언트가 보내지 않음(서버가 승인 시도 번호를 발급)
 */

/*
  // Jackson (@RequestBody / JSON 바인딩)
  // 요청 DTO는 setter 있는 게 제일 안 꼬임
*/
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class ApproveRequest {
    private String posTrx;
    private int amount;
    private CardInput card;
}