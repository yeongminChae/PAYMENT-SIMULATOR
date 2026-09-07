package com.chaeyeongmin.payment_sim.van.client.assembler;

import com.chaeyeongmin.payment_sim.domain.model.PaymentAttempt;
import com.chaeyeongmin.payment_sim.van.client.dto.VanReversalRequest;
import org.springframework.stereotype.Component;

@Component
public class VanReversalAssembler {

    public VanReversalRequest assemble(
            String reversalPosTrx,
            String originalPosTrx,
            int originalAttemptSeq,
            PaymentAttempt originalAttempt
    ) {
        return VanReversalRequest.builder()
                .reversalPosTrx(reversalPosTrx)
                .originalPosTrx(originalPosTrx)
                .originalAttemptSeq(originalAttemptSeq)
                .amount(originalAttempt.amount())
                .build();
    }
}
