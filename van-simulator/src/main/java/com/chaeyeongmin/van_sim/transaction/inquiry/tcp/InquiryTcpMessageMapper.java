package com.chaeyeongmin.van_sim.transaction.inquiry.tcp;

import com.chaeyeongmin.van_sim.protocol.inquiry.InquiryRequestMessage;
import com.chaeyeongmin.van_sim.protocol.inquiry.InquiryResultCode;
import com.chaeyeongmin.van_sim.protocol.inquiry.InquiryResponseMessage;
import com.chaeyeongmin.van_sim.protocol.inquiry.InquiryResponseStatus;
import com.chaeyeongmin.van_sim.protocol.inquiry.InquiryTargetType;
import com.chaeyeongmin.van_sim.transaction.inquiry.service.result.CancelInquiryResult;
import com.chaeyeongmin.van_sim.transaction.inquiry.service.result.ApprovalInquiryResult;
import com.chaeyeongmin.van_sim.transaction.inquiry.service.result.ReversalInquiryResult;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * Inquiry TCP 전문과 Inquiry 서비스 결과 사이의 변환을 담당한다.
 * <p>
 * TCP handler가 JSON 처리와 validation을 맡고,
 * InquiryService가 DB 조회를 맡는다면,
 * 이 mapper는 그 둘 사이에서 protocol DTO와 service result의 모양을 맞추는 얇은 변환 계층이다.
 * 여기서 DB 조회, 시나리오 판단, transport 동작 같은 부수 효과를 만들지 않는다.
 */
@Component
public class InquiryTcpMessageMapper {

    /**
     * Inquiry 서비스 결과를 TCP 응답 전문으로 변환한다.
     * <p>
     * requestId, targetTrxNo, targetAttemptSeq는 요청에서 온 correlation 값이므로 request에서 가져오고,
     * vanTrxId, approvalNo, declineCode, status는 VAN 원장 조회 결과에서 가져온다.
     * 이렇게 분리해야 Payment가 "내가 보낸 조회 요청에 대한 응답인지" 검증하면서도
     * VAN 원장의 실제 승인 결과를 받을 수 있다.
     */
    public InquiryResponseMessage toApprovalResponse(
            InquiryRequestMessage request,
            ApprovalInquiryResult approvalResult
    ) {
        InquiryResponseStatus status = switch (approvalResult.status()) {
            case APPROVED -> InquiryResponseStatus.APPROVED;
            case DECLINED -> InquiryResponseStatus.DECLINED;
            case UNKNOWN -> InquiryResponseStatus.UNKNOWN;
        };

        return InquiryResponseMessage.of(
                request.requestId(),
                InquiryTargetType.APPROVAL,
                request.targetTrxNo(),
                request.targetAttemptSeq(),
                InquiryResultCode.SUCCESS,
                approvalResult.vanTrxId(),
                status,
                approvalResult.approvalNo(),
                null,
                null,
                approvalResult.declineCode()
        );
    }

    /** 취소 원장 상태와 승인 번호 또는 거절 코드를 CANCEL 조회 응답 필드에 배치한다. */
    public InquiryResponseMessage toCancelResponse(
            InquiryRequestMessage request,
            CancelInquiryResult cancelResult
    ) {
        InquiryResponseStatus status = switch (cancelResult.status()) {
            case CANCELLED -> InquiryResponseStatus.CANCELLED;
            case CANCEL_DECLINED -> InquiryResponseStatus.CANCEL_DECLINED;
        };

        return InquiryResponseMessage.of(
                request.requestId(),
                InquiryTargetType.CANCEL,
                request.targetTrxNo(),
                null,
                InquiryResultCode.SUCCESS,
                cancelResult.vanCancelTrxId(),
                status,
                null,
                cancelResult.cancelApprovalNo(),
                null,
                cancelResult.declineCode()
        );
    }

    /** 망취소 원장 상태와 승인 번호 또는 거절 코드를 REVERSAL 조회 응답 필드에 배치한다. */
    public InquiryResponseMessage toReversalResponse(
            InquiryRequestMessage request,
            ReversalInquiryResult reversalResult
    ) {
        InquiryResponseStatus status = switch (reversalResult.status()) {
            case REVERSED -> InquiryResponseStatus.REVERSED;
            case REVERSAL_DECLINED -> InquiryResponseStatus.REVERSAL_DECLINED;
        };

        return InquiryResponseMessage.of(
                request.requestId(),
                InquiryTargetType.REVERSAL,
                request.targetTrxNo(),
                null,
                InquiryResultCode.SUCCESS,
                reversalResult.vanReversalTrxId(),
                status,
                null,
                null,
                reversalResult.reversalApprovalNo(),
                reversalResult.declineCode()
        );
    }

    /** 요청 correlation 값은 유지하고 원장 결과 필드는 비운 NOT_FOUND 응답을 만든다. */
    public InquiryResponseMessage notFoundResponse(InquiryRequestMessage request) {
        return InquiryResponseMessage.of(
                request.requestId(),
                request.targetType(),
                request.targetTrxNo(),
                request.targetAttemptSeq(),
                InquiryResultCode.NOT_FOUND,
                null,
                null,
                null,
                null,
                null,
                null
        );
    }

}
