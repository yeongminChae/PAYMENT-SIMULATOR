package com.chaeyeongmin.van_sim.transaction.approval.tcp;

import com.chaeyeongmin.van_sim.protocol.approval.ApprovalRequestMessage;
import com.chaeyeongmin.van_sim.protocol.approval.ApprovalResponseMessage;
import com.chaeyeongmin.van_sim.protocol.approval.ApprovalResponseStatus;
import com.chaeyeongmin.van_sim.transaction.approval.service.command.ApprovalCommand;
import com.chaeyeongmin.van_sim.transaction.approval.service.result.ApprovalResult;
import org.springframework.stereotype.Component;

/**
 * 승인 TCP 전문과 승인 서비스 모델 사이의 변환을 담당한다.
 * <p>
 * TCP 계층의 원문 필드(PAN, requestId, 응답 전문 상태)를 서비스 계층이 쓰는
 * {@link ApprovalCommand}와 프로토콜 응답 DTO로 분리해 매핑한다.
 */
@Component
public class ApprovalTcpMessageMapper {

    /**
     * 승인 요청 전문을 승인 서비스 처리 명령으로 변환한다.
     * <p>
     * PAN 전체 번호는 서비스로 넘기지 않고 카드 BIN과 마지막 4자리만 추출한다.
     */
    public ApprovalCommand toCommand(ApprovalRequestMessage request) {
        String pan = request.pan();

        return ApprovalCommand.of(
                request.posTrx(),
                request.attemptSeq(),
                request.amount(),
                pan.substring(0, 8),
                pan.substring(pan.length() - 4)
        );
    }

    /**
     * 승인 서비스 처리 결과를 승인 TCP 응답 전문으로 변환한다.
     * <p>
     * 원장 상태({@link com.chaeyeongmin.van_sim.ledger.approval.status.VanApprovalStatus})를
     * 응답 프로토콜 상태({@link ApprovalResponseStatus})로 바꾸고 요청 식별자를 유지한다.
     */
    public ApprovalResponseMessage toResponse(
            ApprovalRequestMessage request,
            ApprovalResult result
    ) {
        ApprovalResponseStatus status =  switch (result.status()) {
            case APPROVED -> ApprovalResponseStatus.APPROVED;
            case DECLINED -> ApprovalResponseStatus.DECLINED;
            case UNKNOWN -> ApprovalResponseStatus.UNKNOWN;
        };

        return ApprovalResponseMessage.of(
                request.requestId(),
                request.posTrx(),
                request.attemptSeq(),
                result.vanTrxId(),
                status,
                result.approvalNo(),
                result.declineCode()
        );
    }

}
