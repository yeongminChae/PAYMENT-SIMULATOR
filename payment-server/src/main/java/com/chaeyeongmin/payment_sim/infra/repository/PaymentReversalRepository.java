package com.chaeyeongmin.payment_sim.infra.repository;

import com.chaeyeongmin.payment_sim.domain.model.PaymentReversal;
import com.chaeyeongmin.payment_sim.infra.repository.dto.ReversalInsertParam;
import com.chaeyeongmin.payment_sim.infra.repository.dto.ReversalResultUpdateParam;

import java.util.Optional;

/**
 * PAYMENT_REVERSAL 영속성 포트.
 *
 * <p>
 * reversal 흐름은 먼저 PENDING row를 만들고, VAN 응답을 받은 뒤 그 row를 최종 상태로 바꾼다.
 * 이 포트는 그 과정에서 필요한 조회, PENDING 생성, 최종 상태 update, request-not-sent 정리를 담당한다.
 *
 * <p>
 * 주요 key:
 * - reversalPosTrx: 이번 reversal 거래번호. 같은 거래번호 재요청/재사용 여부를 판단한다.
 * - originalPosTrx + originalAttemptSeq: reversal 대상 원승인 attempt. 원승인 1건당 reversal 1건 정책을 판단한다.
 */
public interface PaymentReversalRepository {

    /**
     * 현재 reversal 거래번호 기준으로 PAYMENT_REVERSAL row를 조회한다.
     *
     * <p>
     * 같은 reversalPosTrx 재요청이면 기존 row 상태를 재응답하고,
     * 같은 reversalPosTrx가 다른 원거래에 쓰였으면 거래번호 재사용 conflict로 판단하는 데 사용한다.
     */
    Optional<PaymentReversal> findByReversalPosTrx(String reversalPosTrx);

    /**
     * 원승인 attempt 기준으로 PAYMENT_REVERSAL row를 조회한다.
     *
     * <p>
     * 현재 요청의 reversalPosTrx가 달라도 같은 원승인에 이미 reversal row가 있으면
     * VAN reversal을 다시 호출하지 않고 기존 row 상태를 응답해야 한다.
     */
    Optional<PaymentReversal> findByOriginalPosTrxAndOriginalAttemptSeq(
            String originalPosTrx,
            int originalAttemptSeq
    );

    /**
     * VAN reversal 호출 전에 PENDING reversal row를 생성한다.
     *
     * <p>
     * Optional.of(row)는 PENDING row 생성 성공을 의미하며, 이 요청만 VAN reversal 호출로 진행한다.
     * Optional.empty()나 unique 충돌 예외는 다른 요청이 먼저 row를 만들었을 수 있다는 신호이므로
     * transaction service가 재조회로 복구한다.
     */
    Optional<PaymentReversal> insertPendingReversal(ReversalInsertParam param);

    /**
     * VAN reversal 응답을 PENDING row의 최종 상태로 반영한다.
     *
     * <p>
     * 아직 PENDING인 row만 REVERSED 또는 REVERSAL_DECLINED로 바꾼다.
     * Optional.empty()는 이미 확정됐거나 대상 row/상태 조건이 맞지 않는 경우이므로
     * transaction service가 현재 DB 상태를 재조회해 응답을 결정한다.
     */
    Optional<PaymentReversal> updateReversalResult(ReversalResultUpdateParam param);

    /**
     * VAN에 요청이 전송되지 않은 경우에만 PENDING row를 삭제한다.
     *
     * <p>
     * timeout처럼 VAN 전달 여부가 불명확한 경우에는 삭제하면 중복 reversal 위험이 있으므로 사용하면 안 된다.
     * WHERE 조건은 현재 reversal 거래번호, 원거래 식별자, PENDING 상태로 좁힌다.
     */
    int deletePendingReversal(String reversalPosTrx, String originalPosTrx, int originalAttemptSeq);
}
