package com.chaeyeongmin.van_sim.protocol.cancel;

/**
 * 결제 서버가 VAN 시뮬레이터로 보내는 취소 요청 전문 모델이다.
 *
 * <p>
 * TCP JSON payload의 필드 이름과 1:1로 맞는 프로토콜 계층 객체다.
 * 카드번호/PAN/expiry는 포함하지 않는다. Payment Server가 카드 일치 검증을 끝낸 뒤,
 * VAN에는 취소 대상 원승인 거래 식별 정보만 전달한다.
 *
 * @param protocolVersion Cancel TCP protocol 버전. 현재 handler는 "1"만 처리할 예정이다.
 * @param messageType 요청 전문 유형. dispatcher가 "CANCEL" 값을 보고 Cancel handler로 라우팅한다.
 * @param requestId Payment Server가 보낸 transport correlation ID. 응답에서 그대로 echo한다.
 * @param cancelPosTrx 이번 취소 요청의 POS 거래번호. 같은 값 재요청은 cancelPosTrx 멱등성 대상이다.
 * @param originalPosTrx 취소 대상 원승인의 POS 거래번호.
 * @param originalAttemptSeq 취소 대상 원승인의 승인 시도 순번.
 * @param originalVanTrxId Payment Server가 알고 있는 원승인 VAN 거래번호. VAN 원장 payload 검증에 사용한다.
 * @param originalApprovalNo Payment Server가 알고 있는 원승인 승인번호. VAN 원장 payload 검증에 사용한다.
 * @param amount 취소 요청 금액. 현재 VAN simulator 정책에서는 원승인 금액과 같아야 한다.
 */
public record CancelRequestMessage(
        // 프로토콜 호환성 확인용 값이다. 업무 command에는 전달하지 않는다.
        String protocolVersion,
        // dispatcher/handler가 처리할 전문 종류를 판단하는 transport 필드다.
        String messageType,
        // TCP 요청/응답을 매칭하기 위한 값으로, 업무 원장 key가 아니다.
        String requestId,
        // 현재 취소 거래번호. 원승인 거래번호와 분리해서 보관한다.
        String cancelPosTrx,
        // 아래 original* 필드는 취소 대상 승인 원장을 찾고 payload를 검증하는 데 사용한다.
        String originalPosTrx,
        int originalAttemptSeq,
        String originalVanTrxId,
        String originalApprovalNo,
        int amount
) {
}
