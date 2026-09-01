package com.chaeyeongmin.van_sim.ledger.cancel.status;

public enum CancelResultCode {
    SUCCESS,
    ALREADY_CANCELLED,
    ORIGINAL_NOT_FOUND,
    ORIGINAL_NOT_APPROVED,
    ORIGINAL_MISMATCH
}
