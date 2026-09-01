package com.chaeyeongmin.van_sim.transaction.cancel.service.result;

import com.chaeyeongmin.van_sim.ledger.cancel.status.CancelResultCode;
import com.chaeyeongmin.van_sim.ledger.cancel.status.VanCancelStatus;

import java.time.LocalDateTime;

public record CancelResult(
        String vanCancelTrxId,
        String cancelPosTrx,
        String originalPosTrx,
        int originalAttemptSeq,
        VanCancelStatus cancelStatus,
        CancelResultCode resultCode,
        String cancelApprovalNo,
        String declineCode,
        LocalDateTime processedAt
) {
}
