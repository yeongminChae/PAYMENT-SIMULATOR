package com.chaeyeongmin.van_sim.transaction.reversal.tcp;

import com.chaeyeongmin.van_sim.protocol.reversal.ReversalRequestMessage;
import com.chaeyeongmin.van_sim.protocol.reversal.ReversalResponseMessage;
import com.chaeyeongmin.van_sim.transaction.reversal.service.command.ReversalCommand;
import com.chaeyeongmin.van_sim.transaction.reversal.service.result.ReversalResult;
import org.springframework.stereotype.Component;

/**
 * Reversal TCP 전문과 Reversal 서비스 모델 사이의 변환을 담당한다.
 */
@Component
public class ReversalTcpMessageMapper {

    public ReversalCommand toCommand(ReversalRequestMessage request) {
        return new ReversalCommand(
                request.reversalPosTrx(),
                request.originalPosTrx(),
                request.originalAttemptSeq(),
                request.amount()
        );
    }

    public ReversalResponseMessage toResponse(
            ReversalRequestMessage request,
            ReversalResult result
    ) {
        return new ReversalResponseMessage(
                request.protocolVersion(),
                "REVERSAL_RESPONSE",
                request.requestId(),
                result.reversalPosTrx(),
                result.originalPosTrx(),
                result.originalAttemptSeq(),
                result.vanReversalTrxId(),
                result.reversalStatus(),
                result.resultCode(),
                result.reversalApprovalNo(),
                result.declineCode()
        );
    }
}
