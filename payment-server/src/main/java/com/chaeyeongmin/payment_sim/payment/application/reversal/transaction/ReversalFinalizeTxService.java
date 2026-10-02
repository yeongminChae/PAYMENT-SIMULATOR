package com.chaeyeongmin.payment_sim.payment.application.reversal.transaction;

import com.chaeyeongmin.payment_sim.common.api.ResultCode;
import com.chaeyeongmin.payment_sim.common.exception.BusinessException;
import com.chaeyeongmin.payment_sim.infra.repository.PaymentReversalRepository;
import com.chaeyeongmin.payment_sim.infra.repository.dto.ReversalResultUpdateParam;
import com.chaeyeongmin.payment_sim.payment.api.reversal.ReversalResponse;
import com.chaeyeongmin.payment_sim.payment.application.reversal.support.ReversalResponseFactory;
import com.chaeyeongmin.payment_sim.payment.application.reversal.transaction.model.ReversalPrepareResult;
import com.chaeyeongmin.payment_sim.payment.domain.reversal.PaymentReversal;
import com.chaeyeongmin.payment_sim.van.client.dto.VanReversalResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * VAN reversal 응답을 DB 최종 상태로 확정하는 트랜잭션을 담당한다.
 */
@Service
@RequiredArgsConstructor
public class ReversalFinalizeTxService {

    private final PaymentReversalRepository reversalRepository;
    private final ReversalResponseFactory responseFactory;

    /**
     * VAN reversal 응답을 DB의 PENDING reversal row에 최종 상태로 반영하고 API 응답을 만든다.
     *
     * <p>
     * update는 PENDING row에만 성공해야 한다. 0 rows가 반환되면 이미 VAN은 호출된 뒤이므로,
     * 현재 reversal 거래번호 기준 재조회로 실제 DB 상태를 확인해 응답 의미를 보정한다.
     */
    @Transactional
    public ReversalResponse applyVanResult(
            ReversalPrepareResult prepared,
            VanReversalResponse vanResponse
    ) {
        // TX2 진입 방어.
        // - completed prepare 결과는 이미 응답이 확정된 경로라 VAN을 호출하면 안 된다.
        // - prepared/vanResponse 누락은 서비스 조립 오류이므로 내부 오류로 즉시 중단한다.
        if (prepared == null || prepared.isCompleted() || vanResponse == null) {
            throw new BusinessException(ResultCode.INTERNAL_ERROR, "REVERSAL_FINALIZE_INVALID_PREPARE_RESULT");
        }

        String reversalPosTrx = prepared.reversalPosTrx();
        String originalPosTrx = prepared.originalPosTrx();
        int originalAttemptSeq = prepared.originalAttemptSeq();

        // R7-1: VAN reversal 응답을 PAYMENT_REVERSAL update 파라미터로 변환한다.
        // - REVERSED는 reversal 승인번호를 저장하고, REVERSAL_DECLINED는 declineCode를 저장한다.
        // - 응답은 VAN 원문이 아니라 update RETURNING으로 받은 DB 저장값 기준으로 조립한다.
        ReversalResultUpdateParam updateParam = switch (vanResponse.reversalStatus()) {
            case REVERSED -> ReversalResultUpdateParam.reversed(
                    reversalPosTrx,
                    originalPosTrx,
                    originalAttemptSeq,
                    vanResponse.vanReversalTrxId(),
                    vanResponse.reversalApprovalNo()
            );
            case REVERSAL_DECLINED -> ReversalResultUpdateParam.declined(
                    reversalPosTrx,
                    originalPosTrx,
                    originalAttemptSeq,
                    vanResponse.vanReversalTrxId(),
                    code(vanResponse.declineCode())
            );
        };

        // R7-2: PENDING -> 최종 reversal 상태 조건부 update.
        // - updateReversalResult는 아직 PENDING인 row만 최종 상태로 바꾸는 멱등성 보호 장치다.
        // - update miss가 나면 다른 흐름이 먼저 상태를 바꿨거나 row 조건이 기대와 달라졌을 수 있다.
        Optional<PaymentReversal> updated = reversalRepository.updateReversalResult(updateParam);
        return updated.isPresent()
                ? responseFactory.fromFinalizedCurrent(updated.get())
                : recoverUpdateMiss(reversalPosTrx, originalPosTrx, originalAttemptSeq);
    }

    /**
     * R7 update empty 복구 처리.
     *
     * <p>
     * 이미 VAN reversal 응답을 받은 뒤 update 결과만 empty인 상황이다. 따라서 즉시 retryLater로 끝내지 않고
     * 현재 reversal 거래번호 기준으로 재조회해 실제 저장된 상태를 응답 소스로 사용한다.
     */
    private ReversalResponse recoverUpdateMiss(
            String reversalPosTrx,
            String originalPosTrx,
            int originalAttemptSeq
    ) {
        // R7 update 0 rows 후 재조회.
        // - 다른 요청이 먼저 확정했거나 PENDING 조건이 더 이상 맞지 않을 수 있다.
        // - 외부 VAN 응답보다 DB에 실제 저장된 값을 우선한다.
        Optional<PaymentReversal> reread = reversalRepository.findByReversalPosTrx(reversalPosTrx);
        if (reread.isPresent()) return responseFactory.fromFinalizedCurrent(reread.get());

        // row 자체가 사라진 경우.
        // - VAN 응답은 받았지만 DB에 확정 저장된 상태를 확인할 수 없으므로 결과를 단정하지 않는다.
        return ReversalResponse.retryLater(reversalPosTrx, originalPosTrx, originalAttemptSeq);
    }

    private String code(com.chaeyeongmin.payment_sim.van.client.dto.enums.VanDeclineCode declineCode) {
        return declineCode == null ? null : declineCode.name();
    }
}
