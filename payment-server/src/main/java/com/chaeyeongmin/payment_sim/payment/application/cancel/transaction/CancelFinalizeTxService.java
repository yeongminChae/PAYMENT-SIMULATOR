package com.chaeyeongmin.payment_sim.payment.application.cancel.transaction;

import com.chaeyeongmin.payment_sim.payment.api.cancel.CancelResponse;
import com.chaeyeongmin.payment_sim.payment.application.cancel.support.CancelEventRecorder;
import com.chaeyeongmin.payment_sim.payment.application.cancel.support.CancelResponseFactory;
import com.chaeyeongmin.payment_sim.payment.application.common.VanDeclineCodeMapper;
import com.chaeyeongmin.payment_sim.payment.application.cancel.transaction.model.CancelPrepareResult;
import com.chaeyeongmin.payment_sim.common.api.ResultCode;
import com.chaeyeongmin.payment_sim.common.exception.BusinessException;
import com.chaeyeongmin.payment_sim.payment.domain.cancel.PaymentCancel;
import com.chaeyeongmin.payment_sim.payment.domain.cancel.CancelStatus;
import com.chaeyeongmin.payment_sim.payment.domain.event.PaymentEventType;
import com.chaeyeongmin.payment_sim.infra.repository.PaymentCancelRepository;
import com.chaeyeongmin.payment_sim.infra.repository.dto.CancelResultUpdateParam;
import com.chaeyeongmin.payment_sim.van.client.dto.VanCancelResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * 취소 TX2 확정 구간만 담당한다.
 *
 * <p>
 * VAN cancel 응답 확정과 VAN timeout UNKNOWN_TIMEOUT 확정을 담당한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CancelFinalizeTxService {

    private final PaymentCancelRepository cancelRepository;
    private final CancelResponseFactory factory;
    private final CancelEventRecorder recorder;
    /**
     * VAN 취소 응답을 DB의 PENDING cancel row에 최종 상태로 반영하고 API 응답을 만든다.
     *
     * <p>
     * update는 PENDING row에만 성공해야 한다. 0 rows가 반환되면 이미 VAN은 호출된 뒤이므로,
     * 원거래 기준 재조회로 현재 DB 상태를 확인해 응답 의미를 보정한다.
     */
    @Transactional
    public CancelResponse applyVanResult(
            CancelPrepareResult prepared,
            VanCancelResponse vanCancelResponse
    ) {
        // TX2 진입 방어.
        // - completed prepare 결과는 이미 응답이 확정된 경로라 VAN을 호출하면 안 된다.
        // - prepared/vanCancelResponse 누락은 서비스 조립 오류이므로 내부 오류로 즉시 중단한다.
        if (prepared == null || vanCancelResponse == null || prepared.isCompleted())
            throw new BusinessException(
                    ResultCode.INTERNAL_ERROR,
                    "CANCEL_FINALIZE_INVALID_PREPARE_RESULT"
            );

        String posTrx = prepared.posTrx();
        String originalPosTrx = prepared.originalPosTrx();
        int originalAttemptSeq = prepared.originalAttemptSeq();

        CancelStatus vanFinalStatus = vanCancelResponse.cancelStatus();
        String responseDeclineCode = VanDeclineCodeMapper.toCode(vanCancelResponse.declineCode());

        switch (vanFinalStatus) {
            // PENDING -> VAN도 아직 취소 결과를 확정하지 못한 상태.
            // - 이미 DB에는 PENDING row가 있으므로 추가 update 없이 retryLater 응답한다.
            case PENDING -> {
                return CancelResponse.retryLater(
                        posTrx,
                        originalPosTrx,
                        originalAttemptSeq
                );
            }

            // CANCELLED -> 취소 성공 확정.
            // - PAYMENT_CANCEL row를 CANCELLED로 바꾸고 VAN 취소 거래번호와 cancelApprovalNo를 저장한다.
            // - 응답은 update RETURNING row 기준으로 조립해 DB 저장값과 응답값을 맞춘다.
            case CANCELLED -> {
                CancelResultUpdateParam updateParam = CancelResultUpdateParam.cancelled(
                        posTrx,
                        originalPosTrx,
                        originalAttemptSeq,
                        vanCancelResponse.vanTrxId(),
                        vanCancelResponse.cancelApprovalNo()
                );
                Optional<PaymentCancel> updatedCancelOpt =
                        cancelRepository.updateCancelResult(updateParam);

                if (updatedCancelOpt.isPresent()) {
                    PaymentCancel updatedCancel = updatedCancelOpt.get();

                    recorder.recordCancelEvent(
                            PaymentEventType.CANCEL_FINALIZED,
                            posTrx,
                            originalPosTrx,
                            originalAttemptSeq,
                            ResultCode.OK.name(),
                            updatedCancel.cancelStatus().name(),
                            vanCancelResponse.vanTrxId(),
                            updatedCancel.cancelApprovalNo(),
                            updatedCancel.declineCode(),
                            "cancel finalized"
                    );

                    return CancelResponse.cancelled(
                            updatedCancel.posTrx(),
                            updatedCancel.originalPosTrx(),
                            updatedCancel.originalAttemptSeq(),
                            updatedCancel.cancelApprovalNo()
                    );
                }

            }

            // CANCEL_DECLINED -> 취소 거절 확정.
            // - PAYMENT_CANCEL row를 CANCEL_DECLINED로 바꾸고 VAN 취소 거래번호와 declineCode를 저장한다.
            // - 이 상태도 최종 상태이므로 이후 같은 원거래 취소 요청은 DB 재응답으로 처리한다.
            case CANCEL_DECLINED -> {
                CancelResultUpdateParam updateParam = CancelResultUpdateParam.declined(
                        posTrx,
                        originalPosTrx,
                        originalAttemptSeq,
                        vanCancelResponse.vanTrxId(),
                        responseDeclineCode
                );
                Optional<PaymentCancel> updatedCancelOpt =
                        cancelRepository.updateCancelResult(updateParam);

                if (updatedCancelOpt.isPresent()) {
                    PaymentCancel updatedCancel = updatedCancelOpt.get();

                    recorder.recordCancelEvent(
                            PaymentEventType.CANCEL_FINALIZED,
                            posTrx,
                            originalPosTrx,
                            originalAttemptSeq,
                            ResultCode.CANCEL_DECLINED.name(),
                            updatedCancel.cancelStatus().name(),
                            vanCancelResponse.vanTrxId(),
                            updatedCancel.cancelApprovalNo(),
                            updatedCancel.declineCode(),
                            "cancel finalized"
                    );

                    return CancelResponse.declined(
                            updatedCancel.posTrx(),
                            updatedCancel.originalPosTrx(),
                            updatedCancel.originalAttemptSeq(),
                            updatedCancel.declineCode()
                    );
                }

            }

        }

        // C7 update 0 rows:
        // - updateCancelResult는 PENDING row를 최종 상태로 바꾸는 단계다.
        // - 여기까지 왔다는 것은 이미 VAN cancel을 1회 호출했다는 뜻이다.
        // - update 결과가 empty라고 해서 cancel row 자체가 없다는 의미는 아니다.
        //   예: CURRENT_TRX_NO/상태 조건이 맞지 않거나, 다른 흐름이 먼저 row를 확정했을 수 있다.
        // - 그래서 즉시 retryLater로 끝내지 않고 original 기준으로 재조회해 현재 DB 상태를 응답에 반영한다.
        log.error("[cancel][C7-0rows] update cancel result failed. posTrx={}, originalPosTrx={}, originalAttemptSeq={}, intendedStatus={}",
                posTrx, originalPosTrx, originalAttemptSeq, vanFinalStatus);
        return recoverUpdateMiss(
                posTrx,
                originalPosTrx,
                originalAttemptSeq
        );

    }

    /**
     * C7 update empty 복구 처리.
     *
     * <p>
     * 이 메서드는 C5 insert miss 복구와 일부러 분리한다.
     * C5는 VAN cancel 호출 권한을 얻지 못한 중복/경합 요청이므로
     * 기존 row가 CANCELLED면 ALREADY_CANCELLED로 재응답한다.
     *
     * <p>
     * 반면 C7은 이미 이 요청이 VAN cancel을 호출한 뒤 update 결과만 empty인 상황이다.
     * 따라서 original 기준 재조회 결과가 CANCELLED이면 "이미 취소됨"이 아니라
     * "취소 성공 상태가 DB에서 확인됨"으로 보고 CANCELLED 응답을 내려준다.
     *
     * <p>
     * 복구 기준:
     * - PENDING         : DB 최종 확정이 아직 보이지 않으므로 RETRY_LATER
     * - CANCELLED       : C7 요청의 취소 성공 상태로 보고 CANCELLED
     * - CANCEL_DECLINED : C7 요청의 취소 거절 상태로 보고 CANCEL_DECLINED
     * - row 없음        : 정합성 이상 가능성이 있으므로 error log 후 RETRY_LATER
     */
    private CancelResponse recoverUpdateMiss(
            String posTrx,
            String originalPosTrx,
            int originalAttemptSeq
    ) {
        Optional<PaymentCancel> recoveredCancelByOriginalOpt =
                findCancelByOriginal(
                        "C7-update-empty",
                        posTrx,
                        originalPosTrx,
                        originalAttemptSeq
                );

        if (recoveredCancelByOriginalOpt.isEmpty()) {
            log.error("[cancel][C7-recovery][CRITICAL_CANCEL_ROW_NOT_FOUND] update result missed and cancel row not found. posTrx={}, originalPosTrx={}, originalAttemptSeq={}",
                    posTrx, originalPosTrx, originalAttemptSeq);

            return CancelResponse.retryLater(
                    posTrx,
                    originalPosTrx,
                    originalAttemptSeq
            );
        }

        PaymentCancel recoveredCancelByOriginal = recoveredCancelByOriginalOpt.get();
        log.info("[cancel][C7-recovery] recovered cancel row after update miss. posTrx={}, originalPosTrx={}, originalAttemptSeq={}, recoveredStatus={}",
                posTrx,
                originalPosTrx,
                originalAttemptSeq,
                recoveredCancelByOriginal.cancelStatus()
        );

        return factory.fromFinalizedCurrent(recoveredCancelByOriginal);
    }

    /**
     * 원거래 식별자 기준으로 PAYMENT_CANCEL row를 조회한다.
     */
    private Optional<PaymentCancel> findCancelByOriginal(
            String phase,
            String posTrx,
            String originalPosTrx,
            int originalAttemptSeq
    ) {
        Optional<PaymentCancel> cancelByOriginalOpt =
                cancelRepository.findByOriginalPosTrxAndOriginalAttemptSeq(
                        originalPosTrx,
                        originalAttemptSeq
                );

        cancelByOriginalOpt.ifPresent(cancel ->
                log.info("[cancel][{}] cancel row found. posTrx={}, originalPosTrx={}, originalAttemptSeq={}, cancelStatus={}",
                        phase,
                        posTrx,
                        originalPosTrx,
                        originalAttemptSeq,
                        cancel.cancelStatus()
                )
        );

        return cancelByOriginalOpt;
    }

    /**
     * VAN 취소 호출이 timeout 된 요청을 UNKNOWN_TIMEOUT으로 확정 저장한다.
     *
     * <p>
     * timeout은 VAN이 취소를 처리했는지 알 수 없는 상태다. 따라서 CANCELLED/CANCEL_DECLINED를
     * 추측하지 않고 UNKNOWN_TIMEOUT으로 남기며, 후속 요청은 기존 row를 보고 VAN 재호출 없이 retryLater로 응답한다.
     */
    @Transactional
    public CancelResponse markUnknownTimeout(
            CancelPrepareResult prepared
    ) {
        String posTrx = prepared.posTrx();
        String originalPosTrx = prepared.originalPosTrx();
        int originalAttemptSeq = prepared.originalAttemptSeq();

        CancelResultUpdateParam updateParam =
                CancelResultUpdateParam.unknownTimeout(posTrx, originalPosTrx, originalAttemptSeq);

        // PENDING row만 UNKNOWN_TIMEOUT으로 바꾼다.
        // - update가 실패하면 다른 흐름이 먼저 상태를 바꿨을 수 있으므로 C7 복구 로직과 동일하게 재조회한다.
        Optional<PaymentCancel> updated = cancelRepository.updateCancelResult(updateParam);

        return updated.isPresent()
            ? CancelResponse.retryLater(posTrx, originalPosTrx, originalAttemptSeq)
            : recoverUpdateMiss(posTrx, originalPosTrx, originalAttemptSeq)
        ;

    }

}
