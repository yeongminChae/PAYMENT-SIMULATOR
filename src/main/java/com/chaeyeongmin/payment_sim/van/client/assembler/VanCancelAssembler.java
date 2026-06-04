package com.chaeyeongmin.payment_sim.van.client.assembler;

import com.chaeyeongmin.payment_sim.api.payment.dto.request.CancelRequest;
import com.chaeyeongmin.payment_sim.domain.model.PaymentAttempt;
import com.chaeyeongmin.payment_sim.van.client.dto.VanCancelRequest;
import com.chaeyeongmin.payment_sim.van.client.policy.VanTraceIdPolicy;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class VanCancelAssembler {
    private final VanTraceIdPolicy vanTraceIdPolicy;

    public VanCancelRequest getVanCancelRequest(
            CancelRequest req,
            PaymentAttempt originalAttempt
    ) {
        String posTrx = req.posTrx();
        String originalPosTrx = req.originalPosTrx();
        int originalAttemptSeq = req.originalAttemptSeq();

        String vanTrxId = vanTraceIdPolicy.resolveVanTrxId(
                originalPosTrx,
                originalAttemptSeq,
                originalAttempt.vanTrxId()
        );

        return VanCancelRequest.builder()
                .posTrx(posTrx)
                .originalPosTrx(originalPosTrx)
                .originalAttemptSeq(originalAttemptSeq)
                .amount(originalAttempt.amount())
                .approvalNo(originalAttempt.approvalNo())
                .vanTrxId(vanTrxId)
                .cardLast4(originalAttempt.cardLast4())
                .build();
    }

}
