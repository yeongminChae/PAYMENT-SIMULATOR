package com.chaeyeongmin.van_sim.transaction.approval.service.command;

public record ApprovalCommand(
        String posTrx,
        int attemptSeq,
        int amount,
        String cardBin,
        String cardLast4
) {
}
