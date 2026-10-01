package com.chaeyeongmin.payment_sim.payment.application.approval.transaction;

import com.chaeyeongmin.payment_sim.common.api.ResultCode;
import com.chaeyeongmin.payment_sim.common.exception.BusinessException;
import com.chaeyeongmin.payment_sim.infra.repository.PaymentAttemptRepository;
import com.chaeyeongmin.payment_sim.infra.repository.PaymentExternalInfoRepository;
import com.chaeyeongmin.payment_sim.infra.repository.dto.AttemptInsertParam;
import com.chaeyeongmin.payment_sim.infra.repository.dto.PaymentExternalInfoInsertParam;
import com.chaeyeongmin.payment_sim.payment.api.approval.ApproveRequest;
import com.chaeyeongmin.payment_sim.payment.api.approval.ApproveResponse;
import com.chaeyeongmin.payment_sim.payment.api.common.CardInput;
import com.chaeyeongmin.payment_sim.payment.application.approval.support.ApprovalEventRecorder;
import com.chaeyeongmin.payment_sim.payment.application.approval.support.ApprovalResponseFactory;
import com.chaeyeongmin.payment_sim.payment.application.approval.transaction.model.PaymentApprovalPrepareResult;
import com.chaeyeongmin.payment_sim.payment.application.card.service.BinCatalogService;
import com.chaeyeongmin.payment_sim.payment.application.card.support.CardSummaryFactory;
import com.chaeyeongmin.payment_sim.payment.domain.approval.PaymentAttempt;
import com.chaeyeongmin.payment_sim.payment.domain.approval.PaymentFinalStatus;
import com.chaeyeongmin.payment_sim.payment.domain.card.CardFingerprintPolicy;
import com.chaeyeongmin.payment_sim.payment.domain.card.CardIdentity;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Objects;
import java.util.Optional;

/**
 * 승인 TX1 준비 구간만 담당한다.
 *
 * <p>
 * 업무 경계:
 * - prepare(): posTrx 직렬화 lock, 기존 attempt 멱등 재응답 판단, 신규 PROCESSING attempt 생성
 * - cleanupRequestNotSent(): VAN 요청 미전송이 확정된 PROCESSING attempt 정리
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentApprovalPrepareTransactionService {

    private final BinCatalogService binCatalogService;
    private final PaymentAttemptRepository repository;
    private final PaymentExternalInfoRepository infoRepository;
    private final CardFingerprintPolicy cardFingerprintPolicy;
    private final ApprovalEventRecorder eventRecorder;
    private final ApprovalResponseFactory responseFactory;

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

                    eventRecorder.recordApprovalReused(trx, latest, status);

                    // DB 재응답.
                    // - 저장된 attempt row를 기준으로 삼으므로, 응답도 DB 컬럼에서 조립한다.
                    // - 처리중(PROCESSING)도 "아직 확정되지 않은 DB 상태"를 응답 DTO로 표현한 것이다.
                    // - cardBrand까지 같이 내려 응답 카드 요약이 승인/조회/재응답 경로에서 동일하게 보이게 한다.
                    ApproveResponse approveResponse = responseFactory.fromStatus(
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
                eventRecorder.recordApprovalConflict(trx, latest, status);
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

        eventRecorder.recordApprovalAttemptCreated(trx, attemptSeq);

        return PaymentApprovalPrepareResult.created(trx, attemptSeq, cardIdentity);
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
