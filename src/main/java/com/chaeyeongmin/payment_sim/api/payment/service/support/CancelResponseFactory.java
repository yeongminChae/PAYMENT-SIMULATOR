package com.chaeyeongmin.payment_sim.api.payment.service.support;

import com.chaeyeongmin.payment_sim.api.payment.dto.request.CancelRequest;
import com.chaeyeongmin.payment_sim.api.payment.dto.response.CancelResponse;
import com.chaeyeongmin.payment_sim.domain.model.PaymentCancel;
import com.chaeyeongmin.payment_sim.domain.policy.CancelStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class CancelResponseFactory {
    /**
     * 기존 PAYMENT_CANCEL row를 기준으로 취소 응답을 만든다.
     * <p>
     * 이 함수의 역할:
     * - 이미 취소 row가 있는 경우, 그 row의 cancelStatus를 기준으로 보고 상태별 재응답한다.
     * - C4 기존 row 조회 경로와 C5 insert miss 후 재조회 경로가 같은 응답 규칙을 쓰게 한다.
     * <p>
     * 분기 기준:
     * - PENDING         : 이전 취소 요청이 아직 미확정이므로 retryLater
     * - CANCELLED       : 이미 취소 완료. 응답은 alreadyCancelled 의미로 조립
     * - CANCEL_DECLINED : 이전 취소 요청이 거절 확정. declineCode와 함께 재응답
     */
    public CancelResponse fromExistingCancel(
            CancelRequest request,
            PaymentCancel cancel
    ) {
        CancelStatus cancelStatus = cancel.cancelStatus();
        String posTrx = request.posTrx();
        String originalPosTrx = request.originalPosTrx();
        int originalAttemptSeq = request.originalAttemptSeq();

        return switch (cancelStatus) {
            // PENDING -> 취소 처리 중/미확정.
            // - VAN을 다시 호출하지 않고 클라이언트가 나중에 재시도/조회하도록 유도한다.
            case PENDING -> CancelResponse.retryLater(
                    posTrx,
                    originalPosTrx,
                    originalAttemptSeq
            );

            // CANCELLED -> 이미 취소 완료.
            // - 기존 cancelApprovalNo를 그대로 돌려줘 동일 원거래 재취소가 같은 결과를 보게 한다.
            case CANCELLED -> CancelResponse.alreadyCancelled(
                    posTrx,
                    originalPosTrx,
                    originalAttemptSeq,
                    cancel.cancelApprovalNo()
            );

            // CANCEL_DECLINED -> 이전 취소 요청이 VAN에서 거절된 상태.
            // - 거절 사유를 보존해 같은 원거래 재요청에도 같은 의미를 돌려준다.
            case CANCEL_DECLINED -> CancelResponse.declined(
                    posTrx,
                    originalPosTrx,
                    originalAttemptSeq,
                    cancel.declineCode()
            );

        };

    }

    /**
     * C7 update empty 이후 재조회된 PAYMENT_CANCEL row를 API 응답으로 변환한다.
     *
     * <p>
     * 기존 row 재응답({@link #fromExistingCancel(CancelRequest, PaymentCancel)})과
     * 가장 중요한 차이는 CANCELLED 매핑이다.
     *
     * <p>
     * - 기존 row 재응답: 현재 요청은 VAN을 호출하지 않은 중복 요청이므로 CANCELLED -> ALREADY_CANCELLED
     * - C7 복구 응답 : 현재 요청은 VAN을 이미 호출했으므로 CANCELLED -> CANCELLED
     */
    public CancelResponse fromC7RecoveredCancel(
            PaymentCancel cancel
    ) {
        return switch (cancel.cancelStatus()) {
            case PENDING -> CancelResponse.retryLater(
                    cancel.posTrx(),
                    cancel.originalPosTrx(),
                    cancel.originalAttemptSeq()
            );

            case CANCELLED -> CancelResponse.cancelled(
                    cancel.posTrx(),
                    cancel.originalPosTrx(),
                    cancel.originalAttemptSeq(),
                    cancel.cancelApprovalNo()
            );

            case CANCEL_DECLINED -> CancelResponse.declined(
                    cancel.posTrx(),
                    cancel.originalPosTrx(),
                    cancel.originalAttemptSeq(),
                    cancel.declineCode()
            );

        };

    }

}
