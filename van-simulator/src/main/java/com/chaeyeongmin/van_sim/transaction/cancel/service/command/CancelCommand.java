package com.chaeyeongmin.van_sim.transaction.cancel.service.command;

public record CancelCommand(
        String cancelPosTrx,
        String originalPosTrx,
        int originalAttemptSeq,
        String originalVanTrxId,
        String originalApprovalNo,
        int amount
) {
}
