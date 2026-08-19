package com.chaeyeongmin.van_sim.transaction.approval.service.command;

import lombok.Builder;

/**
 * 승인 서비스가 처리할 승인 요청 데이터를 담는 애플리케이션 계층 명령 객체다.
 * <p>
 * TCP 승인 요청 전문에서 서비스 로직에 필요한 값만 추려낸 내부 입력 모델이다.
 * requestId, messageType, expiryYyMm처럼 응답 매핑이나 프로토콜 해석에만 필요한 필드는
 * 서비스가 몰라도 되므로 포함하지 않는다. PAN 전체 번호도 넘기지 않고, 승인 판단과 기록에
 * 필요한 BIN과 마지막 4자리만 전달한다.
 *
 * @param posTrx POS 거래 식별자
 * @param attemptSeq 같은 거래의 승인 시도 순번
 * @param amount 승인 요청 금액
 * @param cardBin 카드 번호 앞자리 BIN
 * @param cardLast4 카드 번호 마지막 4자리
 */
@Builder
public record ApprovalCommand(
        String posTrx,
        int attemptSeq,
        int amount,
        String cardBin,
        String cardLast4
) {

    public static ApprovalCommand of(
            String posTrx,
            int attemptSeq,
            int amount,
            String cardBin,
            String cardLast4
    ) {
        return ApprovalCommand.builder()
                .posTrx(posTrx)
                .attemptSeq(attemptSeq)
                .amount(amount)
                .cardBin(cardBin)
                .cardLast4(cardLast4)
                .build();
    }
}
