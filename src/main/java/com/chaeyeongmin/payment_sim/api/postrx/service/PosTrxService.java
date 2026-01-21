package com.chaeyeongmin.payment_sim.api.postrx.service;

import com.chaeyeongmin.payment_sim.api.postrx.dto.PosTrxEotRequest;
import com.chaeyeongmin.payment_sim.api.postrx.dto.PosTrxEotResponse;
import com.chaeyeongmin.payment_sim.api.postrx.dto.PosTrxIssueRequest;
import com.chaeyeongmin.payment_sim.api.postrx.dto.PosTrxIssueResponse;

public interface PosTrxService {
    PosTrxIssueResponse issue(PosTrxIssueRequest request);

    PosTrxEotResponse eot(PosTrxEotRequest request);
}