package com.chaeyeongmin.payment_sim.payment.application.approval.transaction;

import com.chaeyeongmin.payment_sim.infra.repository.PaymentAttemptRepository;
import com.chaeyeongmin.payment_sim.infra.repository.dto.AttemptResultUpdateParam;
import com.chaeyeongmin.payment_sim.infra.repository.dto.PaymentAttemptUpdatedRow;
import com.chaeyeongmin.payment_sim.payment.api.approval.ApproveResponse;
import com.chaeyeongmin.payment_sim.payment.application.approval.support.ApprovalEventRecorder;
import com.chaeyeongmin.payment_sim.payment.application.approval.support.ApprovalResponseFactory;
import com.chaeyeongmin.payment_sim.payment.application.approval.support.AttemptResultUpdateParamFactory;
import com.chaeyeongmin.payment_sim.payment.application.approval.transaction.model.PaymentApprovalPrepareResult;
import com.chaeyeongmin.payment_sim.payment.application.card.support.CardSummaryFactory;
import com.chaeyeongmin.payment_sim.payment.domain.approval.PaymentAttempt;
import com.chaeyeongmin.payment_sim.payment.domain.approval.PaymentFinalStatus;
import com.chaeyeongmin.payment_sim.payment.domain.card.CardIdentity;
import com.chaeyeongmin.payment_sim.van.client.dto.VanApproveResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * 승인 TX2 확정 구간만 담당한다.
 *
 * <p>
 * 업무 경계:
 * - finalizeApproval(): VAN 결과 조건부 확정, update miss 후 DB 재조회/방어 응답
 * - finalizeUnknownTimeout(): VAN timeout 관측 결과를 UNKNOWN_TIMEOUT으로 조건부 확정
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentApprovalFinalizeTransactionService {

    private final PaymentAttemptRepository repository;
    private final ApprovalEventRecorder eventRecorder;
    private final ApprovalResponseFactory responseFactory;

    /**
     * VAN 응답을 정상적으로 받은 뒤 해당 결과를 PAYMENT_ATTEMPT에 최종 반영한다.
     *
     * <p>핵심 원칙:
     * - VAN 응답은 외부 시스템의 결과이고, 최종 응답의 정본은 Payment DB다.
     * - FINAL_STATUS IS NULL 조건부 UPDATE로 최초 확정 요청만 상태를 변경한다.
     * - UPDATE 0건이면 동시 요청이 먼저 확정했을 수 있으므로 DB를 재조회한다.
     * - DB에 이미 확정 결과가 있다면 VAN 응답보다 DB 값을 우선한다.
     */
    @Transactional
    public ApproveResponse finalizeApproval(
            PaymentApprovalPrepareResult prepared,
            VanApproveResponse vanResponse
    ) {
        String trx = prepared.posTrx();
        int attemptSeq = prepared.attemptSeq();
        CardIdentity cardIdentity = prepared.cardIdentity();

        // A7: 실제로 수신한 VAN 결과를 Payment DB 저장 형식으로 변환한다.
        AttemptResultUpdateParam updateParam =
                AttemptResultUpdateParamFactory.fromVanApprove(vanResponse, trx, attemptSeq);

        // FINAL_STATUS IS NULL인 PROCESSING attempt만 최초 1회 확정한다.
        // 성공하면 DB에 실제 저장된 값을 RETURNING으로 받는다.
        Optional<PaymentAttemptUpdatedRow> attemptUpdatedRowOpt = repository.updateAttemptResult(updateParam);

        // A8: 이번 호출이 최초 확정 저장에 성공한 경우.
        if (attemptUpdatedRowOpt.isPresent()) {
            PaymentAttemptUpdatedRow row = attemptUpdatedRowOpt.get();

            log.info("[approve][FINALIZE] finalized. posTrx={}, attemptSeq={}, finalStatus={}, vanTrxId={}", trx, attemptSeq, row.finalStatus(), row.vanTrxId());

            eventRecorder.recordApprovalFinalized(trx, attemptSeq, row);

            // VAN 응답 원문이 아니라 실제 DB 저장값으로 응답한다.
            return responseFactory.fromStatus(
                    row.finalStatus(),
                    trx,
                    attemptSeq,
                    row.approvalNo(),
                    row.declineCode(),
                    CardSummaryFactory.fromStoredCard(
                            row.cardBin(),
                            row.cardLast4(),
                            row.cardBrand()
                    )
            );
        }

        // UPDATE 0건:
        // - 다른 요청이 같은 attempt를 먼저 확정했거나
        // - 비정상적으로 update가 반영되지 않았을 수 있다.
        // 현재 DB 상태를 다시 읽어 판단한다.
        Optional<PaymentAttempt> latestAttemptFromDb = repository.findByPosTrxAndAttemptSeq(trx, attemptSeq);

        if (latestAttemptFromDb.isPresent()) {
            PaymentAttempt row = latestAttemptFromDb.get();

            // A9: 이미 다른 요청이 확정한 결과가 있는 경우.
            if (row.finalStatus() != null) {
                PaymentFinalStatus dbStatus = PaymentFinalStatus.valueOf(row.finalStatus());
                PaymentFinalStatus vanStatus = updateParam.finalStatus();

                // VAN에서 받은 결과와 DB에 이미 확정된 결과가 다르면
                // 정합성 확인이 필요한 상태다.
                // 외부 응답보다 DB 정본을 우선하여 반환한다.
                if (dbStatus != vanStatus) {
                    log.error("[approve][FINALIZE][MISMATCH] db finalStatus != van finalStatus. "
                        + "posTrx={}, attemptSeq={}, dbStatus={}, vanStatus={}, vanTrxId={}", trx, attemptSeq, dbStatus, vanStatus, vanResponse.vanTrxId());
                }

                return responseFactory.fromStatus(
                        dbStatus,
                        trx,
                        attemptSeq,
                        row.approvalNo(),
                        row.declineCode(),
                        CardSummaryFactory.fromStoredCard(
                                row.cardBin(),
                                row.cardLast4(),
                                row.cardBrand()
                        )
                );
            }

            // A10: VAN 응답까지 받았는데 DB는 여전히 PROCESSING이다.
            // 확정 결과를 임의로 단정하지 않고 재시도를 유도한다.
            log.error("[approve][FINALIZE][UPDATE_MISS_PROCESSING] attempt still processing after VAN response. "
                            + "posTrx={}, attemptSeq={}, vanStatus={}, vanTrxId={}", trx, attemptSeq, vanResponse.finalStatus(), vanResponse.vanTrxId());

            return ApproveResponse.retryLater(
                    trx,
                    attemptSeq,
                    CardSummaryFactory.fromStoredCard(
                            cardIdentity.cardBin(),
                            cardIdentity.cardLast4(),
                            cardIdentity.brand()
                    )
            );
        }

        // A3에서 생성한 attempt 자체를 찾지 못했다.
        // VAN 응답은 받았더라도 Payment DB에 확정 사실을 남기지 못했으므로
        // 승인/거절을 임의로 반환하지 않고 UNKNOWN_TIMEOUT 계열로 방어한다.
        log.error("[approve][FINALIZE][ATTEMPT_NOT_FOUND] attempt row not found after VAN response. "
                        + "posTrx={}, attemptSeq={}, vanStatus={}, vanTrxId={}", trx, attemptSeq, vanResponse.finalStatus(), vanResponse.vanTrxId());

        eventRecorder.recordApprovalUnknownAfterFinalizeUpdateMiss(trx, attemptSeq, vanResponse);

        return ApproveResponse.unknownTimeout(
                trx,
                attemptSeq,
                "UNKNOWN_AFTER_UPDATE_MISS",
                CardSummaryFactory.fromStoredCard(
                        cardIdentity.cardBin(),
                        cardIdentity.cardLast4(),
                        cardIdentity.brand()
                )
        );
    }

    /**
     * VAN 요청은 전송했지만 제한 시간 안에 응답을 받지 못한 경우,
     * PROCESSING attempt를 UNKNOWN_TIMEOUT으로 확정한다.
     *
     * <p>이 경로에서는 VanApproveResponse가 존재하지 않는다.
     * 따라서 Payment는 VAN의 실제 승인 여부, 승인번호, VAN 거래번호를 알 수 없다.
     *
     * <p>핵심 원칙:
     * - UNKNOWN_TIMEOUT은 VAN이 보내준 결과가 아니라 Payment가 관측한 통신 결과다.
     * - approvalNo와 vanTrxId는 알 수 없으므로 저장하지 않는다.
     * - FINAL_STATUS IS NULL 조건부 UPDATE로 최초 1회만 UNKNOWN_TIMEOUT을 저장한다.
     * - UPDATE 0건이면 DB를 재조회하고, 이미 확정된 상태가 있다면 DB 값을 우선한다.
     */
    @Transactional
    public ApproveResponse finalizeUnknownTimeout(
            PaymentApprovalPrepareResult prepared
    ) {
        String trx = prepared.posTrx();
        int attemptSeq = prepared.attemptSeq();
        CardIdentity cardIdentity = prepared.cardIdentity();

        // 응답을 받지 못했다는 사실을 Payment DB 저장 형식으로 변환한다.
        // 저장 목표:
        // - finalStatus = UNKNOWN_TIMEOUT
        // - declineCode = TIMEOUT
        // - approvalNo = null
        // - vanTrxId = null
        AttemptResultUpdateParam updateParam =
                AttemptResultUpdateParamFactory.fromApprovalTimeout(trx, attemptSeq);

        // PROCESSING 상태인 attempt만 UNKNOWN_TIMEOUT으로 최초 1회 확정한다.
        Optional<PaymentAttemptUpdatedRow> attemptUpdatedRowOpt = repository.updateAttemptResult(updateParam);

        // TIMEOUT-1: 이번 호출이 UNKNOWN_TIMEOUT 저장에 성공한 경우.
        if (attemptUpdatedRowOpt.isPresent()) {
            PaymentAttemptUpdatedRow row = attemptUpdatedRowOpt.get();

            log.info("[approve][TIMEOUT] finalized as UNKNOWN_TIMEOUT. posTrx={}, attemptSeq={}", trx, attemptSeq);

            eventRecorder.recordApprovalTimeoutFinalized(trx, attemptSeq, row);

            // 실제 DB에 저장된 TIMEOUT 결과와 카드정보를 기준으로 응답한다.
            return ApproveResponse.unknownTimeout(
                    trx,
                    attemptSeq,
                    row.declineCode(),
                    CardSummaryFactory.fromStoredCard(
                            row.cardBin(),
                            row.cardLast4(),
                            row.cardBrand()
                    )
            );
        }

        // UPDATE 0건:
        // 다른 요청이 먼저 상태를 확정했거나,
        // 비정상적으로 UNKNOWN_TIMEOUT update가 반영되지 않았을 수 있다.
        // 현재 DB 상태를 다시 읽어 판단한다.
        Optional<PaymentAttempt> latestAttemptFromDb = repository.findByPosTrxAndAttemptSeq(trx, attemptSeq);

        if (latestAttemptFromDb.isPresent()) {
            PaymentAttempt row = latestAttemptFromDb.get();

            // TIMEOUT-2: 이미 다른 처리에 의해 최종 상태가 확정된 경우.
            if (row.finalStatus() != null) {
                PaymentFinalStatus dbStatus = PaymentFinalStatus.valueOf(row.finalStatus());
                PaymentFinalStatus targetStatus = updateParam.finalStatus();

                // UNKNOWN_TIMEOUT 저장 경쟁에서는
                // DB가 APPROVED/DECLINED 등 더 구체적인 상태로 이미 확정됐을 수 있다.
                // 이 경우 오류로 덮어쓰지 않고 DB 정본을 그대로 사용한다.
                if (dbStatus != targetStatus) {
                    log.info("[approve][TIMEOUT][ALREADY_FINALIZED] timeout finalization lost race. "
                            + "posTrx={}, attemptSeq={}, dbStatus={}, targetStatus={}", trx, attemptSeq, dbStatus, targetStatus);
                }

                return responseFactory.fromStatus(
                        dbStatus,
                        trx,
                        attemptSeq,
                        row.approvalNo(),
                        row.declineCode(),
                        CardSummaryFactory.fromStoredCard(
                                row.cardBin(),
                                row.cardLast4(),
                                row.cardBrand()
                        )
                );
            }

            // TIMEOUT-3:
            // UNKNOWN_TIMEOUT 저장을 시도했지만 DB가 여전히 PROCESSING이다.
            // DB에 확정되지 않은 상태이므로 UNKNOWN_TIMEOUT을 임의로 반환하지 않고
            // 현재 정본에 맞춰 재시도를 유도한다.
            log.error("[approve][TIMEOUT][UPDATE_MISS_PROCESSING] attempt still processing after timeout finalization update miss. "
                            + "posTrx={}, attemptSeq={}, targetStatus={}", trx, attemptSeq, PaymentFinalStatus.UNKNOWN_TIMEOUT);

            return ApproveResponse.retryLater(
                    trx,
                    attemptSeq,
                    CardSummaryFactory.fromStoredCard(
                            row.cardBin(),
                            row.cardLast4(),
                            row.cardBrand()
                    )
            );
        }

        // TIMEOUT-4:
        // TX1에서 생성한 attempt 자체를 찾지 못했다.
        // 정상 흐름에서는 발생하기 어려운 정합성 이상이다.
        // 확정 상태를 추측하지 않고 UNKNOWN_TIMEOUT 계열의 방어 응답을 반환한다.
        log.error("[approve][TIMEOUT][ATTEMPT_NOT_FOUND] attempt row not found after response timeout. "
                        + "posTrx={}, attemptSeq={}", trx, attemptSeq);

        eventRecorder.recordApprovalUnknownAfterTimeoutUpdateMiss(trx, attemptSeq);

        return ApproveResponse.unknownTimeout(
                trx,
                attemptSeq,
                "UNKNOWN_AFTER_UPDATE_MISS",
                CardSummaryFactory.fromStoredCard(
                        cardIdentity.cardBin(),
                        cardIdentity.cardLast4(),
                        cardIdentity.brand()
                )
        );
    }

}
