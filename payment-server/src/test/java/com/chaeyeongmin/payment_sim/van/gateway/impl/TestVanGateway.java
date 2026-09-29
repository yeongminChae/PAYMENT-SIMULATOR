package com.chaeyeongmin.payment_sim.van.gateway.impl;

import com.chaeyeongmin.payment_sim.payment.domain.approval.PaymentFinalStatus;
import com.chaeyeongmin.payment_sim.payment.domain.cancel.CancelStatus;
import com.chaeyeongmin.payment_sim.van.client.dto.VanApproveRequest;
import com.chaeyeongmin.payment_sim.van.client.dto.VanApproveResponse;
import com.chaeyeongmin.payment_sim.van.client.dto.VanCancelRequest;
import com.chaeyeongmin.payment_sim.van.client.dto.VanCancelResponse;
import com.chaeyeongmin.payment_sim.van.client.dto.VanInquiryRequest;
import com.chaeyeongmin.payment_sim.van.client.dto.VanInquiryResponse;
import com.chaeyeongmin.payment_sim.van.client.dto.VanInquiryResultCode;
import com.chaeyeongmin.payment_sim.van.client.dto.VanInquiryTargetType;
import com.chaeyeongmin.payment_sim.van.client.dto.VanReversalRequest;
import com.chaeyeongmin.payment_sim.van.client.dto.VanReversalResponse;
import com.chaeyeongmin.payment_sim.van.client.dto.enums.VanDeclineCode;
import com.chaeyeongmin.payment_sim.van.client.tcp.protocol.inquiry.VanInquiryStatus;
import com.chaeyeongmin.payment_sim.van.gateway.VanGateway;

import java.time.LocalDateTime;

public class TestVanGateway implements VanGateway {

    @Override
    public VanApproveResponse approve(VanApproveRequest request) {
        String posTrx = request.posTrx();
        int attemptSeq = request.attemptSeq();
        String vanTrxId = makeVanTrxId(posTrx, attemptSeq);
        LocalDateTime now = LocalDateTime.now();

        if ("7777".equals(request.cardLast4())) {
            return approveResponse(
                    request,
                    PaymentFinalStatus.UNKNOWN_TIMEOUT,
                    null,
                    VanDeclineCode.TIMEOUT,
                    vanTrxId,
                    now
            );
        }

        if (isApprovedRule(request.cardLast4())) {
            return approveResponse(
                    request,
                    PaymentFinalStatus.APPROVED,
                    makeApprovalNo(),
                    null,
                    vanTrxId,
                    now
            );
        }

        return approveResponse(
                request,
                PaymentFinalStatus.DECLINED,
                null,
                VanDeclineCode.DO_NOT_HONOR,
                vanTrxId,
                now
        );
    }

    @Override
    public VanInquiryResponse inquiry(VanInquiryRequest request) {
        String posTrx = request.posTrx();
        int attemptSeq = request.attemptSeq();
        String vanTrxId = makeVanTrxId(posTrx, attemptSeq);
        String cardLast4 = request.cardLast4();
        LocalDateTime now = LocalDateTime.now();

        if ("7777".equals(cardLast4)) {
            return inquiryResponse(
                    posTrx,
                    attemptSeq,
                    VanInquiryStatus.UNKNOWN,
                    null,
                    VanDeclineCode.TIMEOUT,
                    vanTrxId,
                    now
            );
        }

        if (isApprovedRule(cardLast4)) {
            return inquiryResponse(
                    posTrx,
                    attemptSeq,
                    VanInquiryStatus.APPROVED,
                    makeApprovalNo(),
                    null,
                    vanTrxId,
                    now
            );
        }

        return inquiryResponse(
                posTrx,
                attemptSeq,
                VanInquiryStatus.DECLINED,
                null,
                VanDeclineCode.DO_NOT_HONOR,
                vanTrxId,
                now
        );
    }

    @Override
    public VanCancelResponse cancel(VanCancelRequest request) {
        String vanTrxId = request.vanTrxId();
        if (vanTrxId == null || vanTrxId.isBlank()) {
            vanTrxId = makeVanTrxId(request.originalPosTrx(), request.originalAttemptSeq());
        }

        LocalDateTime now = LocalDateTime.now();

        if (request.originalAttemptSeq() % 7 == 0 || "0000".equals(request.cardLast4())) {
            return cancelResponse(
                    request,
                    CancelStatus.PENDING,
                    null,
                    VanDeclineCode.TIMEOUT,
                    vanTrxId,
                    now
            );
        }

        if (isApprovedRule(request.cardLast4())) {
            return cancelResponse(
                    request,
                    CancelStatus.CANCELLED,
                    makeApprovalNo(),
                    null,
                    vanTrxId,
                    now
            );
        }

        return cancelResponse(
                request,
                CancelStatus.CANCEL_DECLINED,
                null,
                VanDeclineCode.DO_NOT_HONOR,
                vanTrxId,
                now
        );
    }

    @Override
    public VanReversalResponse reversal(VanReversalRequest request) {
        throw new UnsupportedOperationException("reversal is supported only in TCP VAN mode");
    }

    private VanApproveResponse approveResponse(
            VanApproveRequest request,
            PaymentFinalStatus finalStatus,
            String approvalNo,
            VanDeclineCode declineCode,
            String vanTrxId,
            LocalDateTime respondedAt
    ) {
        return VanApproveResponse.builder()
                .posTrx(request.posTrx())
                .attemptSeq(request.attemptSeq())
                .finalStatus(finalStatus)
                .approvalNo(approvalNo)
                .declineCode(declineCode)
                .vanTrxId(vanTrxId)
                .respondedAt(respondedAt)
                .build();
    }

    private VanInquiryResponse inquiryResponse(
            String posTrx,
            int attemptSeq,
            VanInquiryStatus status,
            String approvalNo,
            VanDeclineCode declineCode,
            String vanTrxId,
            LocalDateTime respondedAt
    ) {
        return VanInquiryResponse.builder()
                .targetType(VanInquiryTargetType.APPROVAL)
                .targetTrxNo(posTrx)
                .targetAttemptSeq(attemptSeq)
                .resultCode(VanInquiryResultCode.SUCCESS)
                .status(status)
                .vanTrxId(vanTrxId)
                .approvalNo(approvalNo)
                .declineCode(declineCode)
                .respondedAt(respondedAt)
                .build();
    }

    private VanCancelResponse cancelResponse(
            VanCancelRequest request,
            CancelStatus status,
            String cancelApprovalNo,
            VanDeclineCode declineCode,
            String vanTrxId,
            LocalDateTime respondedAt
    ) {
        return VanCancelResponse.builder()
                .posTrx(request.posTrx())
                .originalPosTrx(request.originalPosTrx())
                .originalAttemptSeq(request.originalAttemptSeq())
                .cancelStatus(status)
                .cancelApprovalNo(cancelApprovalNo)
                .declineCode(declineCode)
                .vanTrxId(vanTrxId)
                .respondedAt(respondedAt)
                .build();
    }

    private static String makeVanTrxId(String posTrx, int attemptSeq) {
        return posTrx + "-" + String.format("%02d", attemptSeq);
    }

    private static String makeApprovalNo() {
        long t = System.nanoTime() % 1_000_000_000L;

        return String.format("A%09d", t);
    }

    private static boolean isApprovedRule(String last4) {
        char lastDigit = last4.charAt(last4.length() - 1);

        return ((lastDigit - '0') % 2) == 0;
    }

}
