package com.chaeyeongmin.payment_sim.api.payment.service;

import com.chaeyeongmin.payment_sim.api.payment.service.transaction.model.RecoveryFinalizeResult;
import com.chaeyeongmin.payment_sim.infra.repository.dto.AttemptResultUpdateParam;
import com.chaeyeongmin.payment_sim.infra.repository.dto.CancelResultUpdateParam;
import com.chaeyeongmin.payment_sim.infra.repository.dto.ReversalResultUpdateParam;

public interface RecoveryFinalizationService {

    RecoveryFinalizeResult finalizeApproval(AttemptResultUpdateParam intended);

    RecoveryFinalizeResult finalizeCancel(CancelResultUpdateParam intended);

    RecoveryFinalizeResult finalizeReversal(ReversalResultUpdateParam intended);
}
