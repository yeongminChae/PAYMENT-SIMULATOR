package com.chaeyeongmin.van_sim.protocol.cancel;

import com.chaeyeongmin.van_sim.ledger.cancel.status.CancelResultCode;
import com.chaeyeongmin.van_sim.ledger.cancel.status.VanCancelStatus;

/**
 * VAN 시뮬레이터가 결제 서버로 반환하는 취소 응답 전문 모델이다.
 *
 * <p>
 * requestId는 TCP 요청 correlation을 위해 요청 값을 그대로 유지한다.
 * cancelPosTrx는 현재 요청의 업무 correlation 값이며, ALREADY_CANCELLED 응답에서도
 * 기존 원장 owner가 아니라 현재 요청의 cancelPosTrx가 내려갈 수 있다.
 *
 * @param protocolVersion 응답 protocol 버전. 요청 version을 echo한다.
 * @param messageType 응답 전문 유형. 서버가 "CANCEL_RESPONSE"로 고정 생성한다.
 * @param requestId 요청/응답 transport correlation ID.
 * @param cancelPosTrx 현재 요청 기준 취소 POS 거래번호.
 * @param originalPosTrx 취소 대상 원승인 POS 거래번호.
 * @param originalAttemptSeq 취소 대상 원승인 attemptSeq.
 * @param vanCancelTrxId VAN 내부 취소 거래 추적키. ALREADY_CANCELLED에서는 기존 취소 원장의 값이다.
 * @param cancelStatus VAN 취소 원장의 상태. ALREADY_CANCELLED 응답도 원장 상태는 CANCELLED다.
 * @param resultCode 현재 요청 관점의 업무 결과 코드.
 * @param cancelApprovalNo 정상 취소 성공 시 VAN이 발급한 취소 승인번호.
 * @param declineCode 취소 거절 시 사유 코드.
 */
public record CancelResponseMessage(
        // 요청 protocolVersion을 그대로 돌려준다.
        String protocolVersion,
        // 요청 messageType(CANCEL)과 달리 응답은 항상 CANCEL_RESPONSE다.
        String messageType,
        // transport correlation은 요청에서 온 값을 유지한다.
        String requestId,
        // business correlation은 CancelResult 기준 값을 사용한다.
        String cancelPosTrx,
        String originalPosTrx,
        int originalAttemptSeq,
        // 실제 VAN 취소 원장의 거래번호다. 재취소 응답에서는 기존 owner의 값일 수 있다.
        String vanCancelTrxId,
        VanCancelStatus cancelStatus,
        CancelResultCode resultCode,
        String cancelApprovalNo,
        String declineCode
) {
}
