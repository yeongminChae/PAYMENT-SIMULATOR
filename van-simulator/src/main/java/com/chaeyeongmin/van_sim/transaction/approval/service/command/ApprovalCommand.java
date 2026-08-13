package com.chaeyeongmin.van_sim.transaction.approval.service.command;

/**
 * 승인 서비스가 처리할 승인 요청 데이터를 담는 애플리케이션 계층 명령 객체다.
 */
public record ApprovalCommand(
        String posTrx,
        int attemptSeq,
        int amount,
        String cardBin,
        String cardLast4
) {
}
