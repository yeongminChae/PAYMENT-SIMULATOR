package com.chaeyeongmin.van_sim.transaction.reversal;

import com.chaeyeongmin.van_sim.transaction.reversal.service.command.ReversalCommand;
import com.chaeyeongmin.van_sim.transaction.reversal.service.result.ReversalResult;

/**
 * VAN reversal 처리 use case.
 */
public interface ReversalService {

    /**
     * 원승인 row를 기준으로 reversal 가능 여부를 판단하고 van_reversal 원장을 만든다.
     */
    ReversalResult processReversal(ReversalCommand command);
}
