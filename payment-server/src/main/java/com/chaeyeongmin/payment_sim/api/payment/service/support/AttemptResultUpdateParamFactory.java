package com.chaeyeongmin.payment_sim.api.payment.service.support;

import com.chaeyeongmin.payment_sim.domain.status.PaymentFinalStatus;
import com.chaeyeongmin.payment_sim.infra.repository.dto.AttemptResultUpdateParam;
import com.chaeyeongmin.payment_sim.van.client.dto.VanApproveResponse;
import com.chaeyeongmin.payment_sim.van.client.dto.VanInquiryResultCode;
import com.chaeyeongmin.payment_sim.van.client.dto.VanInquiryResponse;
import com.chaeyeongmin.payment_sim.van.client.dto.enums.VanDeclineCode;

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
        PaymentFinalStatus finalStatus = toPaymentFinalStatus(response);
        return fromVanResult(
                finalStatus,
                response.approvalNo(),
                VanDeclineCodeMapper.toCode(response.declineCode()),
                response.vanTrxId(),
                posTrx,
                attemptSeq
        );
    }

    private static PaymentFinalStatus toPaymentFinalStatus(VanInquiryResponse response) {
        if (response.resultCode() == VanInquiryResultCode.NOT_FOUND) {
            return PaymentFinalStatus.UNKNOWN_TIMEOUT;
        }

        if (response.status() == null) {
            throw new IllegalStateException("VAN inquiry SUCCESS response status is null");
        }

        return switch (response.status()) {
            case APPROVED -> PaymentFinalStatus.APPROVED;
            case DECLINED -> PaymentFinalStatus.DECLINED;
            case UNKNOWN -> PaymentFinalStatus.UNKNOWN_TIMEOUT;
            case CANCELLED,
                 CANCEL_DECLINED -> throw new IllegalStateException(
                    "Cancel inquiry status cannot be used in approval inquiry flow: " + response.status()
            );
        };
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

    public static AttemptResultUpdateParam fromApprovalTimeout(
            String posTrx,
            int attemptSeq
    ) {
        return AttemptResultUpdateParam.unknownTimeout(
                posTrx,
                attemptSeq,
                VanDeclineCode.TIMEOUT.code(),
                null
        );
    }

}
