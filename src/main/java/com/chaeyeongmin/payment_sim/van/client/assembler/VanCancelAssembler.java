package com.chaeyeongmin.payment_sim.van.client.assembler;

import com.chaeyeongmin.payment_sim.domain.model.PaymentAttempt;
import com.chaeyeongmin.payment_sim.van.client.dto.VanCancelRequest;
import com.chaeyeongmin.payment_sim.van.client.policy.VanTraceIdPolicy;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class VanCancelAssembler {
    private final VanTraceIdPolicy vanTraceIdPolicy;

    public VanCancelRequest assemble(
            String cancelPosTrx,
            String originalPosTrx,
            int originalAttemptSeq,
            PaymentAttempt originalAttempt
    ) {
        String vanTrxId = vanTraceIdPolicy.resolveVanTrxId(
                originalPosTrx,
                originalAttemptSeq,
                originalAttempt.vanTrxId()
        );

        return VanCancelRequest.builder()
                .posTrx(cancelPosTrx)
                .originalPosTrx(originalPosTrx)
                .originalAttemptSeq(originalAttemptSeq)
                .amount(originalAttempt.amount())
                .approvalNo(originalAttempt.approvalNo())
                .vanTrxId(vanTrxId)
                .cardLast4(originalAttempt.cardLast4())
                .build();
    }

}
