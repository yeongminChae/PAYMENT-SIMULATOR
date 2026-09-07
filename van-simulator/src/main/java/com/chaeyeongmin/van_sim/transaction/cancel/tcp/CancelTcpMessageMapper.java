package com.chaeyeongmin.van_sim.transaction.cancel.tcp;

import com.chaeyeongmin.van_sim.protocol.cancel.CancelRequestMessage;
import com.chaeyeongmin.van_sim.protocol.cancel.CancelResponseMessage;
import com.chaeyeongmin.van_sim.transaction.cancel.service.command.CancelCommand;
import com.chaeyeongmin.van_sim.transaction.cancel.service.result.CancelResult;
import org.springframework.stereotype.Component;

/**
 * Cancel TCP 전문과 Cancel 서비스 모델 사이의 변환을 담당한다.
 *
 * <p>
 * 이 mapper는 protocol DTO와 service command/result의 모양만 맞춘다.
 * JSON 처리, protocol validation, DB 조회, lock 획득은 담당하지 않는다.
 *
 * <p>
 * Cancel TCP 경계에서 유지해야 하는 correlation은 두 종류다.
 * - requestId: TCP 요청/응답을 매칭하는 transport correlation
 * - cancelPosTrx: 취소 업무의 현거래번호를 나타내는 business correlation
 */
@Component
public class CancelTcpMessageMapper {

    /**
     * 취소 요청 전문을 취소 서비스 처리 명령으로 변환한다.
     *
     * <p>
     * protocolVersion, messageType, requestId는 TCP protocol 해석에만 필요한 값이므로
     * 서비스 command로 넘기지 않는다. CancelService는 원승인 식별자와 취소 금액만으로
     * 원장 조회, row lock, 중복 취소 판단을 수행한다.
     */
    public CancelCommand toCommand(CancelRequestMessage request) {
        return new CancelCommand(
                // 이번 취소 요청의 업무 거래번호.
                request.cancelPosTrx(),
                // 취소 대상 원승인을 찾는 key.
                request.originalPosTrx(),
                request.originalAttemptSeq(),
                // 원승인 row를 찾은 뒤 요청 payload와 VAN 원장이 같은지 검증하는 값들.
                request.originalVanTrxId(),
                request.originalApprovalNo(),
                request.amount()
        );
    }

    /**
     * 취소 서비스 처리 결과를 취소 TCP 응답 전문으로 변환한다.
     *
     * <p>
     * requestId는 transport correlation이므로 요청에서 가져오고,
     * cancelPosTrx는 business correlation이므로 서비스 결과에서 가져온다.
     *
     * <p>
     * 예를 들어 C002가 이미 C001로 취소된 원승인을 다시 취소하면,
     * resultCode는 ALREADY_CANCELLED가 되고 vanCancelTrxId는 기존 C001 원장의 값이지만,
     * requestId는 이번 TCP 요청의 값, cancelPosTrx는 C002로 유지된다.
     */
    public CancelResponseMessage toResponse(
            CancelRequestMessage request,
            CancelResult result
    ) {
        return new CancelResponseMessage(
                // protocolVersion은 요청에서 받은 값을 echo한다. handler validation 이후에는 "1"이다.
                request.protocolVersion(),
                // 응답 전문 유형은 서버가 고정한다.
                "CANCEL_RESPONSE",
                // TCP client가 자신이 보낸 요청의 응답인지 확인하는 correlation 값이다.
                request.requestId(),
                // CancelService가 현재 요청 관점으로 조립한 취소 거래번호를 사용한다.
                result.cancelPosTrx(),
                result.originalPosTrx(),
                result.originalAttemptSeq(),
                // 아래 값들은 실제 취소 처리 결과다. SUCCESS/ALREADY_CANCELLED 모두 service result를 그대로 전달한다.
                result.vanCancelTrxId(),
                result.cancelStatus(),
                result.resultCode(),
                result.cancelApprovalNo(),
                result.declineCode()
        );
    }
}
