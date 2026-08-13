package com.chaeyeongmin.van_sim.transaction.approval.service.command;

import lombok.Builder;

/**
 * 승인 서비스가 처리할 승인 요청 데이터를 담는 애플리케이션 계층 명령 객체다.
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
