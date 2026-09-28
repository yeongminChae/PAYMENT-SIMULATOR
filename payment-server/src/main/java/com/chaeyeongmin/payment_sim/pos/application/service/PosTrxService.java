package com.chaeyeongmin.payment_sim.pos.application.service;

import com.chaeyeongmin.payment_sim.pos.api.dto.PosTrxEotResponse;
import com.chaeyeongmin.payment_sim.pos.api.dto.PosTrxIssueRequest;
import com.chaeyeongmin.payment_sim.pos.api.dto.PosTrxIssueResponse;

public interface PosTrxService {
    PosTrxIssueResponse issue(PosTrxIssueRequest request);

    PosTrxEotResponse eot(PosTrxIssueRequest request);
}
