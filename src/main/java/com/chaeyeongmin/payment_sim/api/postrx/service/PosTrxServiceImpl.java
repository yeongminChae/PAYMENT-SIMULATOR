package com.chaeyeongmin.payment_sim.api.postrx.service;

import com.chaeyeongmin.payment_sim.api.postrx.dto.PosTrxEotRequest;
import com.chaeyeongmin.payment_sim.api.postrx.dto.PosTrxEotResponse;
import com.chaeyeongmin.payment_sim.api.postrx.dto.PosTrxIssueRequest;
import com.chaeyeongmin.payment_sim.api.postrx.dto.PosTrxIssueResponse;
import org.springframework.stereotype.Service;

@Service
public class PosTrxServiceImpl implements PosTrxService {

    @Override
    public PosTrxIssueResponse issue(PosTrxIssueRequest request) {
        // TODO: POS_TRX_SEQUENCE 사용해서 pos_trx 발급
        return new PosTrxIssueResponse(null);
    }

    @Override
    public PosTrxEotResponse eot(PosTrxEotRequest request) {
        // TODO: 다음 pos_trx 발급(EOT)
        return new PosTrxEotResponse(request.getStore_cd(), request.getBiz_date(), request.getPos_no(), null);
    }
}