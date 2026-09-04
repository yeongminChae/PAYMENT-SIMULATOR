package com.chaeyeongmin.payment_sim.api.payment.service.transaction;

import com.chaeyeongmin.payment_sim.api.payment.dto.card.CardInput;
import com.chaeyeongmin.payment_sim.api.payment.dto.card.CardSummary;
import com.chaeyeongmin.payment_sim.api.payment.dto.request.ApproveRequest;
import com.chaeyeongmin.payment_sim.api.payment.dto.response.ApproveResponse;
import com.chaeyeongmin.payment_sim.api.payment.event.PaymentEventLogRecorder;
import com.chaeyeongmin.payment_sim.api.payment.service.BinCatalogService;
import com.chaeyeongmin.payment_sim.api.payment.service.support.AttemptResultUpdateParamFactory;
import com.chaeyeongmin.payment_sim.api.payment.service.support.CardSummaryFactory;
import com.chaeyeongmin.payment_sim.api.payment.service.support.PaymentResultCodeMapper;
import com.chaeyeongmin.payment_sim.api.payment.service.transaction.model.PaymentApprovalPrepareResult;
import com.chaeyeongmin.payment_sim.common.api.ResultCode;
import com.chaeyeongmin.payment_sim.common.exception.BusinessException;
import com.chaeyeongmin.payment_sim.domain.model.CardIdentity;
import com.chaeyeongmin.payment_sim.domain.model.PaymentAttempt;
import com.chaeyeongmin.payment_sim.domain.policy.PaymentEventType;
import com.chaeyeongmin.payment_sim.domain.policy.card.CardFingerprintPolicy;
import com.chaeyeongmin.payment_sim.domain.status.PaymentFinalStatus;
import com.chaeyeongmin.payment_sim.infra.repository.PaymentAttemptRepository;
import com.chaeyeongmin.payment_sim.infra.repository.PaymentExternalInfoRepository;
import com.chaeyeongmin.payment_sim.infra.repository.dto.*;
import com.chaeyeongmin.payment_sim.van.client.dto.VanApproveResponse;
import com.chaeyeongmin.payment_sim.van.client.dto.enums.VanDeclineCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Objects;
import java.util.Optional;

/**
 * 승인 처리 중 DB 트랜잭션이 필요한 구간만 담당한다.
 *
 * <p>
 * 분리 이유:
 * - 기존 승인 서비스가 A3/A4/A7 DB 작업과 A6 VAN 호출을 한 흐름에서 모두 처리하면
 *   외부 호출 시간이 트랜잭션 경계와 섞여 동시성 의도를 읽기 어렵다.
 * - 이 클래스는 TX1(승인 준비)과 TX2(승인 확정)를 각각 짧게 끝내고,
 *   실제 VAN 호출은 {@code PaymentApprovalServiceImpl}이 트랜잭션 밖에서 수행하게 한다.
 *
 * <p>
 * 업무 경계:
 * - prepare(): posTrx 직렬화 lock, 기존 attempt 멱등 재응답 판단, 신규 PROCESSING attempt 생성
 * - finalizeApproval(): VAN 결과 조건부 확정, update miss 후 DB 재조회/방어 응답
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentApprovalTransactionService {

    private final BinCatalogService binCatalogService;
    private final PaymentAttemptRepository repository;
    private final PaymentExternalInfoRepository infoRepository;
    private final CardFingerprintPolicy cardFingerprintPolicy;
    private final PaymentEventLogRecorder logRecorder;

    /**
     * TX1: VAN 호출 전에 DB 기준점을 만든다.
     *
     * <p>
     * 여기서 하는 일:
     * - 같은 posTrx의 동시 최초 승인 요청을 row lock으로 줄 세운다.
     * - 이미 확정/처리중인 동일 payload 요청은 DB 재응답으로 끝낸다.
     * - 신규 승인만 attemptSeq를 발급하고 PROCESSING attempt와 외부 식별 정보를 저장한다.
     * - created 결과를 반환한 요청만 VAN approve를 호출하고, existing 결과를 반환한 요청은 VAN을 호출하지 않는다.
     *
     * <p>
     * 여기서 하지 않는 일:
     * - 외부 VAN 호출. 이 트랜잭션이 커밋된 뒤 호출해야 lock 점유 시간이 길어지지 않는다.
     *
     * <p>
     * 결과 의미:
     * - created: 신규 PROCESSING attempt를 만든 대표 요청이다. 호출자는 이 posTrx/attemptSeq로 VAN을 호출한다.
     * - existing: 이미 DB에 응답할 수 있는 attempt가 있다. 호출자는 existingResponse를 그대로 반환한다.
     */
    @Transactional
    public PaymentApprovalPrepareResult prepare(ApproveRequest request) {
        String trx = request.getPosTrx();

        // A3-0: posTrx 단위 승인 처리 직렬화. (“직렬화” = “동시에 못 들어오게 줄 세움”.)
        // - 동시 최초 승인 요청들이 모두 findLatestByPosTrx()에서 empty를 보고
        //   각자 VAN을 호출하는 것을 막기 위해, 조회 전에 PAYMENT_ATTEMPT_SEQ row lock을 획득한다.
        // - 최초 row는 LAST_SEQ=0이며 실제 attemptSeq가 아니다.
        // - 실제 attemptSeq 증가는 신규 attempt 생성이 확정된 뒤 insertAttemptSeq()에서만 수행한다.
        repository.acquireApprovalSerializationLock(trx);

        // A4: posTrx 기준 최신 attempt 조회.
        // - 동일 posTrx로 승인 요청이 다시 들어온 경우, 먼저 DB에 이미 처리 흔적이 있는지 확인한다.
        // - 이 조회 결과가 있으면 "신규 승인"이 아니라 "재요청/중복요청/이전 거절 후 재시도" 중 하나다.
        Optional<PaymentAttempt> latestOpt = repository.findLatestByPosTrx(trx);

        if (latestOpt.isPresent()) {
            PaymentAttempt latest = latestOpt.get();
            PaymentFinalStatus status = latest.getFinalStatusEnum();

            // A4 분기 기준:
            // - APPROVED / UNKNOWN_TIMEOUT / PROCESSING: 이미 진행 중이거나 결론이 난 요청이므로 VAN 재호출 금지.
            // - DECLINED: 승인 거절은 같은 posTrx로 다시 시도할 수 있게 열어둔 MVP 정책.
            //   따라서 DECLINED일 때만 아래 A3 신규 attempt 발급 흐름으로 내려간다.
            if (status != PaymentFinalStatus.DECLINED) {
                // MVP2 승인 멱등성 기준:
                // - posTrx가 같아도 "같은 승인 요청"이라고 보려면 payload까지 같아야 한다.
                // - 신규 row는 cardFingerprint로 같은 카드를 판단한다.
                // - legacy row처럼 fingerprint가 없을 때만 amount/cardBin/cardLast4 비교로 fallback한다.
                // - payload가 같으면 DB 재응답, 다르면 같은 거래번호 재사용으로 보고 차단한다.
                if (isSameApprovalPayload(request, latest)) {
                    log.info("[approve][A4] reuse db result. posTrx={}, attemptSeq={}, status={}",
                            trx, latest.attemptSeq(), status);

                    recordApprovalReused(trx, latest, status);

                    // DB 재응답.
                    // - 저장된 attempt row를 기준으로 삼으므로, 응답도 DB 컬럼에서 조립한다.
                    // - 처리중(PROCESSING)도 "아직 확정되지 않은 DB 상태"를 응답 DTO로 표현한 것이다.
                    // - cardBrand까지 같이 내려 응답 카드 요약이 승인/조회/재응답 경로에서 동일하게 보이게 한다.
                    ApproveResponse approveResponse = getApproveResponse(
                            status,
                            trx,
                            latest.attemptSeq(),
                            latest.approvalNo(),
                            latest.declineCode(),
                            CardSummaryFactory.fromStoredCard(latest.cardBin(), latest.cardLast4(), latest.cardBrand())
                    );

                    return PaymentApprovalPrepareResult.fromExistingResponse(approveResponse);
                }

                log.warn("[approve][A4-conflict] posTrx already used with different payload. posTrx={}, attemptSeq={}, status={}",
                        trx, latest.attemptSeq(), status);

                // 같은 posTrx로 카드/금액을 바꿔 승인하면 멱등 재요청이 아니라 거래번호 재사용이다.
                // 외부 VAN 호출 전에 끊어야 중복 승인이나 서로 다른 승인 결과가 생기지 않는다.
                recordApprovalConflict(trx, latest, status);
                throw new BusinessException(ResultCode.CONFLICT, "POS_TRX_ALREADY_USED");

            }

        }

        // A3: attemptSeq 발급.
        // - attemptSeq는 클라이언트가 보내는 값이 아니라 서버가 posTrx별로 발급하는 승인 시도 번호다.
        // - 같은 posTrx에서 여러 번 시도될 수 있으므로, DB 레벨 시퀀스/업서트로 중복을 막는다.
        int attemptSeq = repository.insertAttemptSeq(trx);

        CardInput card = request.getCard();
        // BIN_CATALOG 기반 식별은 8자리 BIN만 사용한다.
        // active BIN이면 catalog 값을, 미등록/비활성이면 UNKNOWN 값을 저장한다.
        // 이 값은 PAYMENT_ATTEMPT.CARD_BRAND와 PAYMENT_EXTERNAL_INFO 상세 컬럼의 기준이 된다.
        CardIdentity cardIdentity = getCardIdentity(card.bin8(), card.last4());
        LocalDateTime createdAt = LocalDateTime.now();

        // A3-1: PAYMENT_ATTEMPT row 생성.
        // - 이 row는 VAN 호출 전 "처리중 상태"를 남기는 기준점이다.
        // - FINAL_STATUS를 null로 저장해서 PROCESSING을 표현한다.
        // - PAN 원문은 저장하지 않는다.
        // - 표시용 BIN/last4와 동일 카드 식별용 HMAC fingerprint만 저장한다.
        // - cardBrand는 승인 응답/조회 응답의 카드 요약을 DB 기준으로 재구성하기 위해 함께 저장한다.
        repository.insertAttempt(new AttemptInsertParam(
                trx,
                attemptSeq,
                request.getAmount(),
                cardIdentity.cardBin(),
                cardIdentity.cardLast4(),
                cardIdentity.brand(),
                cardFingerprintPolicy.generate(card.getPan()),
                createdAt
        ));

        // PAYMENT_EXTERNAL_INFO는 attempt와 1:1로 연결되는 카드/VAN/대외 식별 상세다.
        // PAYMENT_ATTEMPT.CARD_BIN과 같은 8자리 BIN을 저장하고, PAN 원문은 저장하지 않는다.
        infoRepository.insert(new PaymentExternalInfoInsertParam(
                trx,
                attemptSeq,
                cardIdentity.cardBin(),
                cardIdentity.cardLast4(),
                maskedCardNo(cardIdentity.cardBin(), cardIdentity.cardLast4()),
                cardIdentity.brand(),
                cardIdentity.issuer(),
                cardIdentity.country(),
                cardIdentity.vanProvider(),
                createdAt
        ));

        recordApprovalAttemptCreated(trx, attemptSeq);

        return PaymentApprovalPrepareResult.created(trx, attemptSeq, cardIdentity);
    }

    /**
     * PaymentFinalStatus를 승인 API 응답 DTO로 변환한다.
     * <p>
     * 이 함수의 역할:
     * - DB 재응답, VAN 처리 직후 응답, update miss 후 재조회 응답이 모두 같은 규칙을 쓰게 한다.
     * - 상태별 필수/선택 필드를 한 곳에서 맞춘다.
     * <p>
     * 분기 기준:
     * - APPROVED        : approvalNo를 포함한 승인 성공 응답
     * - DECLINED        : declineCode를 포함한 승인 거절 응답
     * - UNKNOWN_TIMEOUT : 확정 불가/타임아웃 응답
     * - PROCESSING      : 아직 확정 전이므로 retryLater 성격의 응답
     */
    private ApproveResponse getApproveResponse(
            PaymentFinalStatus status,
            String trx,
            int attemptSeq,
            String approvalNo,
            String declineCode,
            CardSummary cardSummary
    ) {
        return switch (status) {
            case APPROVED -> ApproveResponse.approved(trx, attemptSeq, approvalNo, cardSummary);
            case DECLINED -> ApproveResponse.declined(trx, attemptSeq, declineCode, cardSummary);
            case UNKNOWN_TIMEOUT -> ApproveResponse.unknownTimeout(trx, attemptSeq, declineCode, cardSummary);
            case PROCESSING -> ApproveResponse.retryLater(trx, attemptSeq, cardSummary);
        };
    }

    private void recordApprovalReused(
            String trx,
            PaymentAttempt latest,
            PaymentFinalStatus status
    ) {
        insertApproveEvent(
                PaymentEventType.APPROVE_REUSED,
                trx,
                latest.attemptSeq(),
                PaymentResultCodeMapper.codeName(status),
                status.name(),
                latest.vanTrxId(),
                latest.approvalNo(),
                latest.declineCode(),
                "approval result reused by same posTrx and same payload"
        );
    }

    private void recordApprovalConflict(
            String trx,
            PaymentAttempt latest,
            PaymentFinalStatus status
    ) {
        insertApproveEvent(
                PaymentEventType.APPROVE_CONFLICT,
                trx,
                latest.attemptSeq(),
                ResultCode.CONFLICT.name(),
                status.name(),
                latest.vanTrxId(),
                latest.approvalNo(),
                latest.declineCode(),
                "POS_TRX_ALREADY_USED"
        );
    }

    private void recordApprovalAttemptCreated(String trx, int attemptSeq) {
        insertApproveEvent(
                PaymentEventType.APPROVE_ATTEMPT_CREATED,
                trx,
                attemptSeq,
                null,
                PaymentFinalStatus.PROCESSING.name(),
                null,
                null,
                null,
                "approval attempt created"
        );
    }

    private void recordApprovalFinalized(
            String trx,
            int attemptSeq,
            PaymentAttemptUpdatedRow row
    ) {
        insertApproveEvent(
                PaymentEventType.APPROVE_FINALIZED,
                trx,
                attemptSeq,
                PaymentResultCodeMapper.codeName(row.finalStatus()),
                row.finalStatus().name(),
                row.vanTrxId(),
                row.approvalNo(),
                row.declineCode(),
                "approval finalized"
        );
    }

    private void recordApprovalUnknownAfterFinalizeUpdateMiss(
            String trx,
            int attemptSeq,
            VanApproveResponse vanResponse
    ) {
        insertApproveEvent(
                PaymentEventType.APPROVE_UNKNOWN_TIMEOUT,
                trx,
                attemptSeq,
                ResultCode.UNKNOWN_TIMEOUT.name(),
                PaymentFinalStatus.UNKNOWN_TIMEOUT.name(),
                vanResponse.vanTrxId(),
                null,
                "UNKNOWN_AFTER_UPDATE_MISS",
                "approval unknown after finalize update miss"
        );
    }

    private void recordApprovalTimeoutFinalized(
            String trx,
            int attemptSeq,
            PaymentAttemptUpdatedRow row
    ) {
        insertApproveEvent(
                PaymentEventType.APPROVE_UNKNOWN_TIMEOUT,
                trx,
                attemptSeq,
                ResultCode.UNKNOWN_TIMEOUT.name(),
                PaymentFinalStatus.UNKNOWN_TIMEOUT.name(),
                null,
                null,
                row.declineCode(),
                "VAN response timeout"
        );
    }

    private void recordApprovalUnknownAfterTimeoutUpdateMiss(String trx, int attemptSeq) {
        insertApproveEvent(
                PaymentEventType.APPROVE_UNKNOWN_TIMEOUT,
                trx,
                attemptSeq,
                ResultCode.UNKNOWN_TIMEOUT.name(),
                PaymentFinalStatus.UNKNOWN_TIMEOUT.name(),
                null,
                null,
                "UNKNOWN_AFTER_UPDATE_MISS",
                "approval unknown after timeout update miss"
        );
    }

    /**
     * 승인 이벤트 로그를 구조화 컬럼만으로 저장한다.
     *
     * <p>
     * PAN/CVC/전문 원문은 파라미터에 포함하지 않는다.
     * 승인 이벤트는 approval factory를 사용해 attemptSeq 계열 컬럼만 채우고,
     * 취소 이벤트(originalPosTrx/originalAttemptSeq)와 컬럼 사용 규칙을 섞지 않는다.
     */
    private void insertApproveEvent(
            PaymentEventType eventType,
            String posTrx,
            int attemptSeq,
            String resultCode,
            String statusSnapshot,
            String vanTrxId,
            String approvalNo,
            String declineCode,
            String note
    ) {
        PaymentEventLogInsertParam event = PaymentEventLogInsertParam.approval(
                eventType,
                posTrx,
                attemptSeq,
                resultCode,
                statusSnapshot,
                vanTrxId,
                approvalNo,
                declineCode,
                note
        );

        if (eventType == PaymentEventType.APPROVE_CONFLICT) {
            // 충돌 이벤트는 이 메서드가 BusinessException으로 rollback된 뒤 listener가 기록한다.
            logRecorder.recordAfterRollback(event);
            return;
        }

        logRecorder.record(event);
    }

    /**
     * 승인 멱등 재응답이 가능한 "동일 payload"인지 판단한다.
     *
     * <p>
     * 신규 attempt는 cardFingerprint로 동일 카드를 판단한다.
     * 기존 DB row에 cardFingerprint가 없는 legacy attempt만 cardBin/cardLast4로 fallback 비교한다.
     * 이 비교가 false면 APPROVED/PROCESSING/UNKNOWN_TIMEOUT 상태에서는 POS_TRX_ALREADY_USED로 차단한다.
     */
    private boolean isSameApprovalPayload(ApproveRequest request, PaymentAttempt latest) {
        CardInput reqCard = request.getCard();

        if (latest.amount() != request.getAmount()) {
            return false;
        }

        if (latest.cardFingerprint() == null || latest.cardFingerprint().isBlank()) {
            return Objects.equals(latest.cardBin(), reqCard.bin8())
                    && Objects.equals(latest.cardLast4(), reqCard.last4());
        }

        String requestFingerprint = cardFingerprintPolicy.generate(reqCard.getPan());
        return cardFingerprintPolicy.matchesFingerprint(requestFingerprint, latest.cardFingerprint());
    }

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

            recordApprovalFinalized(trx, attemptSeq, row);

            // VAN 응답 원문이 아니라 실제 DB 저장값으로 응답한다.
            return getApproveResponse(
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

                return getApproveResponse(
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

        recordApprovalUnknownAfterFinalizeUpdateMiss(trx, attemptSeq, vanResponse);

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

            recordApprovalTimeoutFinalized(trx, attemptSeq, row);

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

                return getApproveResponse(
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

        recordApprovalUnknownAfterTimeoutUpdateMiss(trx, attemptSeq);

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
     * connect-before-send가 검증된 경우 TX1에서 만든 PROCESSING 데이터를 재시도 가능하게 정리한다.
     *
     * <p>정확한 posTrx + attemptSeq row가 여전히 PROCESSING일 때만 잠금에 성공한다.
     * 잠금 이후 PAYMENT_EXTERNAL_INFO를 먼저 삭제하고 FK parent인 PAYMENT_ATTEMPT를 삭제한다.
     * 둘 중 하나라도 예상한 1건이 아니면 전체 cleanup TX를 rollback한다. 발급 이력인
     * PAYMENT_ATTEMPT_SEQ.LAST_SEQ는 되돌리지 않는다.
     */
    @Transactional
    public void cleanupRequestNotSent(PaymentApprovalPrepareResult prepared) {
        String trx = prepared.posTrx();
        int attemptSeq = prepared.attemptSeq();

        if (repository.lockProcessingAttemptForCleanup(trx, attemptSeq).isEmpty()) {
            log.info("[approve][REQUEST_NOT_SENT][SKIP] attempt is absent or no longer processing. "
                    + "posTrx={}, attemptSeq={}", trx, attemptSeq);
            return;
        }

        int externalInfoDeleted = infoRepository.deleteByPosTrxAndAttemptSeq(trx, attemptSeq);
        int attemptDeleted = repository.deleteProcessingAttempt(trx, attemptSeq);

        if (externalInfoDeleted != 1 || attemptDeleted != 1) {
            throw new IllegalStateException("REQUEST_NOT_SENT_CLEANUP_INCONSISTENT");
        }

        log.info("[approve][REQUEST_NOT_SENT] processing attempt cleaned. posTrx={}, attemptSeq={}",
                trx, attemptSeq);
    }

    private CardIdentity getCardIdentity(String cardBin, String cardLast4) {
        return binCatalogService.identify(cardBin, cardLast4);
    }

    /**
     * 저장 정책: 앞 8자리 BIN + 별표 6개 + 마지막 4자리.
     */
    private String maskedCardNo(String cardBin, String cardLast4) {
        return cardBin + "******" + cardLast4;
    }

}
