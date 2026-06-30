package com.chaeyeongmin.payment_sim.api.payment.service.support;

import com.chaeyeongmin.payment_sim.api.payment.dto.enums.PaymentFinalStatus;
import com.chaeyeongmin.payment_sim.infra.repository.dto.AttemptResultUpdateParam;
import com.chaeyeongmin.payment_sim.van.client.dto.VanApproveResponse;
import com.chaeyeongmin.payment_sim.van.client.dto.VanInquiryResponse;

public final class AttemptResultUpdateParamFactory {

    private AttemptResultUpdateParamFactory() {
    }

    public static AttemptResultUpdateParam fromVanApprove(
            VanApproveResponse response,
            String posTrx,
            int attemptSeq
    ) {
        return fromVanResult(
                response.finalStatus(),
                response.approvalNo(),
                VanDeclineCodeMapper.toCode(response.declineCode()),
                response.vanTrxId(),
                posTrx,
                attemptSeq
        );
    }

    public static AttemptResultUpdateParam fromVanInquiry(
            VanInquiryResponse response,
            String posTrx,
            int attemptSeq
    ) {
        return fromVanResult(
                response.finalStatus(),
                response.approvalNo(),
                VanDeclineCodeMapper.toCode(response.declineCode()),
                response.vanTrxId(),
                posTrx,
                attemptSeq
        );
    }

    private static AttemptResultUpdateParam fromVanResult(
            PaymentFinalStatus finalStatus,
            String approvalNo,
            String declineCode,
            String vanTrxId,
            String posTrx,
            int attemptSeq
    ) {
        return switch (finalStatus) {
            case APPROVED -> AttemptResultUpdateParam.approved(
                    posTrx,
                    attemptSeq,
                    approvalNo,
                    vanTrxId
            );

            case DECLINED -> AttemptResultUpdateParam.declined(
                    posTrx,
                    attemptSeq,
                    declineCode,
                    vanTrxId
            );

            case UNKNOWN_TIMEOUT,
                 PROCESSING -> AttemptResultUpdateParam.unknownTimeout(
                    posTrx,
                    attemptSeq,
                    declineCode,
                    vanTrxId
            );
        };
    }

}
