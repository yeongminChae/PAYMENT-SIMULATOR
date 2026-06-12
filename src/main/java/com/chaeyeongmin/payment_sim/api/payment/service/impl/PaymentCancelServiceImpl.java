package com.chaeyeongmin.payment_sim.api.payment.service.impl;

import com.chaeyeongmin.payment_sim.api.payment.dto.enums.CancelResultStatus;
import com.chaeyeongmin.payment_sim.api.payment.dto.enums.PaymentFinalStatus;
import com.chaeyeongmin.payment_sim.api.payment.dto.request.CancelRequest;
import com.chaeyeongmin.payment_sim.api.payment.dto.response.CancelResponse;
import com.chaeyeongmin.payment_sim.api.payment.service.PaymentCancelService;
import com.chaeyeongmin.payment_sim.api.payment.validate.CancelRequestValidator;
import com.chaeyeongmin.payment_sim.common.api.ResultCode;
import com.chaeyeongmin.payment_sim.common.exception.BusinessException;
import com.chaeyeongmin.payment_sim.domain.model.PaymentAttempt;
import com.chaeyeongmin.payment_sim.domain.model.PaymentCancel;
import com.chaeyeongmin.payment_sim.domain.policy.CancelStatus;
import com.chaeyeongmin.payment_sim.domain.policy.PaymentEventType;
import com.chaeyeongmin.payment_sim.infra.repository.PaymentCancelRepository;
import com.chaeyeongmin.payment_sim.infra.repository.PaymentEventLogRepository;
import com.chaeyeongmin.payment_sim.infra.repository.dto.CancelInsertParam;
import com.chaeyeongmin.payment_sim.infra.repository.dto.CancelResultUpdateParam;
import com.chaeyeongmin.payment_sim.infra.repository.dto.PaymentEventLogInsertParam;
import com.chaeyeongmin.payment_sim.van.client.assembler.VanCancelAssembler;
import com.chaeyeongmin.payment_sim.van.client.dto.VanCancelRequest;
import com.chaeyeongmin.payment_sim.van.client.dto.VanCancelResponse;
import com.chaeyeongmin.payment_sim.van.client.dto.enums.VanDeclineCode;
import com.chaeyeongmin.payment_sim.van.gateway.VanGateway;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * [Service]
 * 결제 취소(Cancel) 유스케이스의 흐름을 제어한다.
 * <p>
 * 이 클래스에서 기억할 핵심:
 * - 취소는 "원승인 attempt"를 대상으로 하는 후속 거래다.
 * - MVP 정책은 전액취소 1회만 허용하므로, 원거래(originalPosTrx + originalAttemptSeq) 기준으로 cancel row를 1건만 만든다.
 * - 이미 cancel row가 있으면 VAN 취소를 다시 호출하지 않고 DB 상태를 기준으로 재응답한다.
 * - 신규 취소는 PENDING row를 먼저 만든 뒤 VAN을 호출한다. 그래야 VAN 호출 중 장애/타임아웃이 나도 후속 요청이 중복 취소를 막을 수 있다.
 * <p>
 * 상태 정책:
 * - PENDING         : 취소 요청은 접수됐지만 최종 결과 미확정
 * - CANCELLED       : 취소 성공 확정
 * - CANCEL_DECLINED : VAN 또는 정책상 취소 거절 확정
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentCancelServiceImpl implements PaymentCancelService {

    private final PaymentCancelRepository repository;
    private final VanGateway vanGateway;
    private final CancelRequestValidator validator;
    private final VanCancelAssembler vanCancelAssembler;
    private final PaymentEventLogRepository paymentEventLogRepository;

    @Transactional
    @Override
    public CancelResponse cancel(CancelRequest request) {
        // C1: 취소 요청은 Controller에서 수신/로깅한다.
        // - Service는 원거래 확인, 중복 취소 방지, VAN 취소 호출, DB 확정 저장을 담당한다.

        // C2: 취소 유효성 검증.
        // - posTrx는 이번 취소 거래번호(현거래번호)다.
        // - originalPosTrx + originalAttemptSeq는 취소할 원승인 attempt를 찾는 식별자다.
        // - 입력이 잘못되면 원거래 조회나 VAN 취소로 진행하면 안 된다.
        validator.validate(request);

        String posTrx = request.posTrx();
        String originalPosTrx = request.originalPosTrx();
        int originalAttemptSeq = request.originalAttemptSeq();

        // C4-1: cancel posTrx 사용 여부 확인.
        // - MVP2에서는 cancel posTrx를 1회용 취소 거래번호로 본다.
        // - 이미 사용된 cancel posTrx가 다시 들어오면 같은 original 여부와 관계없이 거래번호 중복으로 차단한다.
        // - 이 검사는 원거래 조회보다 먼저 수행한다.
        //   같은 cancel posTrx를 다른 original에 붙여 재사용하는 요청도 원거래 존재 여부와 무관하게 실패해야 하기 때문이다.
        Optional<PaymentCancel> existingCancelOpt = repository.findByPosTrx(posTrx);
        if (existingCancelOpt.isPresent()) {
            PaymentCancel existingCancel = existingCancelOpt.get();

            log.warn("[cancel][C4-1-conflict] cancel posTrx already used. posTrx={}, existingOriginalPosTrx={}, existingOriginalAttemptSeq={}, existingCancelStatus={}",
                    posTrx,
                    existingCancel.originalPosTrx(),
                    existingCancel.originalAttemptSeq(),
                    existingCancel.cancelStatus()
            );

            // 여기서 차단되면 이후 original 기준 재응답 정책으로 내려가지 않는다.
            // cancel posTrx 자체가 이미 사용된 거래번호이므로, 같은 original 재요청도 허용하지 않는다.
            insertCancelEvent(
                    PaymentEventType.CANCEL_CONFLICT,
                    posTrx,
                    originalPosTrx,
                    originalAttemptSeq,
                    ResultCode.CONFLICT.name(),
                    existingCancel.cancelStatus().name(),
                    null,
                    existingCancel.cancelApprovalNo(),
                    existingCancel.declineCode(),
                    "POS_TRX_ALREADY_USED"
            );
            throw new BusinessException(
                    ResultCode.CONFLICT,
                    "POS_TRX_ALREADY_USED"
            );
        }

        // C3: 취소 대상 원거래 attempt 조회.
        // - 취소는 독립 거래처럼 보이지만 실제로는 원승인 attempt에 종속된다.
        // - 원거래가 없으면 취소 가능 여부도 판단할 수 없으므로 NOT_FOUND로 종료한다.
        Optional<PaymentAttempt> originalAttemptOpt =
                repository.findOriginalAttempt(originalPosTrx, originalAttemptSeq);

        // C3-1: 원거래 없음.
        // - DB에 원승인 attempt가 없으므로 PAYMENT_CANCEL row를 만들지 않는다.
        // - 존재하지 않는 거래를 VAN에 취소 요청하지 않는다.
        if (originalAttemptOpt.isEmpty()) {
            log.info("[cancel][C3] original attempt not found. posTrx={}, originalPosTrx={}, originalAttemptSeq={}",
                    posTrx,
                    originalPosTrx,
                    originalAttemptSeq
            );

            throw new BusinessException(
                    ResultCode.NOT_FOUND,
                    "취소 대상 원거래가 존재하지 않습니다."
            );
        }

        // C3-2: 원거래 존재.
        // - 이제 원거래의 최종 승인 상태를 보고 취소 가능 여부를 판단한다.
        PaymentAttempt originalAttempt = originalAttemptOpt.get();

        log.info("[cancel][C3] original attempt found. posTrx={}, originalPosTrx={}, originalAttemptSeq={}, originalFinalStatus={}",
                posTrx,
                originalPosTrx,
                originalAttemptSeq,
                originalAttempt.finalStatus()
        );

        // C4-1: 원거래 상태 검증.
        // - 취소 가능한 원거래는 APPROVED뿐이다.
        // - DECLINED/UNKNOWN_TIMEOUT/PROCESSING은 실제 승인 확정이 아니므로 취소 대상이 아니다.
        PaymentFinalStatus originalStatus = originalAttempt.getFinalStatusEnum();

        if (originalStatus != PaymentFinalStatus.APPROVED) {
            log.info("[cancel][C4] cancel not allowed. posTrx={}, originalPosTrx={}, originalAttemptSeq={}, originalStatus={}",
                    posTrx,
                    originalPosTrx,
                    originalAttemptSeq,
                    originalStatus
            );

            // C4-1 종료 응답.
            // - 이 결과는 DB의 cancel 상태가 아니라 "응답 전용 취소 불가 상태"다.
            // - 취소 row를 만들지 않으므로 후속 중복 취소 방지 대상도 아니다.
            insertCancelEvent(
                    PaymentEventType.CANCEL_NOT_ALLOWED,
                    posTrx,
                    originalPosTrx,
                    originalAttemptSeq,
                    ResultCode.CANCEL_NOT_ALLOWED.name(),
                    originalStatus.name(),
                    null,
                    null,
                    "ORIGINAL_NOT_APPROVED",
                    "original attempt is not approved"
            );
            return CancelResponse.cancelNotAllowed(
                    posTrx,
                    originalPosTrx,
                    originalAttemptSeq,
                    "ORIGINAL_NOT_APPROVED"
            );
        }

        // C4-2: 기존 취소 row 확인.
        // - 원거래가 APPROVED여도 이미 취소 요청이 있었으면 VAN을 다시 호출하면 안 된다.
        // - 원거래 기준 unique 제약과 함께 "원승인 1건당 취소 1건" 정책을 보장한다.
        Optional<PaymentCancel> cancelOpt =
                findCancelByOriginal(
                        "C4-existing-cancel-check",
                        posTrx,
                        originalPosTrx,
                        originalAttemptSeq
                );

        if (cancelOpt.isPresent()) {
            PaymentCancel cancel = cancelOpt.get();

            log.info("[cancel][C4] existing cancel row found. posTrx={}, originalPosTrx={}, originalAttemptSeq={}, cancelStatus={}",
                    posTrx,
                    originalPosTrx,
                    originalAttemptSeq,
                    cancel.cancelStatus()
            );

            // C4-2-1: 기존 cancel row 재응답.
            // - 기존 row가 있으면 현재 요청의 posTrx가 달라도 원거래 기준 기존 취소 상태를 우선한다.
            // - 이 분기에서는 외부 VAN 취소를 절대 다시 호출하지 않는다.
            CancelResponse response = getCancelResponseFromExistingCancel(request, cancel);
            insertCancelEvent(
                    PaymentEventType.CANCEL_REUSED_BY_ORIGINAL,
                    posTrx,
                    originalPosTrx,
                    originalAttemptSeq,
                    resultCodeOf(response.cancelStatus()),
                    response.cancelStatus().name(),
                    null,
                    cancel.cancelApprovalNo(),
                    cancel.declineCode(),
                    "cancel result reused by original"
            );
            return response;
        }

        // C4-2-2: 기존 cancel row가 없는 경우.
        // - 원거래는 APPROVED이고, 기존 취소 row도 없으므로 신규 취소 진행 가능 상태다.
        // - 여기까지 통과하면 C5에서 먼저 PENDING row를 만든다.
        // - PENDING 선저장은 외부 VAN 호출 전에 "취소 시도 중"이라는 내부 락/흔적을 남기는 역할이다.
        CancelInsertParam insertParam = CancelInsertParam.pending(
                posTrx,
                originalPosTrx,
                originalAttemptSeq
        );

        // C5: PENDING cancel row 생성.
        // - insertParam은 PAYMENT_CANCEL insert 전용 명령 객체다.
        // - CURRENT_TRX_NO에는 이번 취소 거래번호를, ORIGINAL_*에는 취소 대상 원거래 식별자를 담는다.
        // - insert가 성공한 요청만 VAN cancel 호출 권한을 얻는다.
        // - unique 충돌은 동일 원거래 취소가 먼저 접수된 경합으로 보고 original 기준 재조회 복구로 넘긴다.
        Optional<PaymentCancel> insertedCancelOpt;
        try {
            insertedCancelOpt = repository.insertPendingCancel(insertParam);

        } catch (DataIntegrityViolationException e) {
            // SQLite/MyBatis 조합에서는 unique 충돌이 Optional.empty가 아니라
            // DataIntegrityViolationException 계열 예외로 올라올 수 있다.
            // 이 경로에서는 VAN cancel을 호출하지 않고, 이미 생성된 PAYMENT_CANCEL row를 재조회해 재응답한다.
            log.warn("[cancel][C5-conflict] pending cancel insert conflict. posTrx={}, originalPosTrx={}, originalAttemptSeq={}",
                    posTrx,
                    originalPosTrx,
                    originalAttemptSeq,
                    e
            );

            return handleInsertPendingMiss(
                    request,
                    posTrx,
                    originalPosTrx,
                    originalAttemptSeq
            );
        }

        if (insertedCancelOpt.isPresent()) {
            PaymentCancel insertedCancel = insertedCancelOpt.get();

            log.info("[cancel][C5] pending cancel row created. posTrx={}, originalPosTrx={}, originalAttemptSeq={}, cancelStatus={}",
                    posTrx,
                    originalPosTrx,
                    originalAttemptSeq,
                    insertedCancel.cancelStatus()
            );

            insertCancelEvent(
                    PaymentEventType.CANCEL_PENDING_CREATED,
                    posTrx,
                    originalPosTrx,
                    originalAttemptSeq,
                    null,
                    CancelStatus.PENDING.name(),
                    null,
                    null,
                    null,
                    "cancel pending created"
            );

            // C5 성공 후에만 VAN 취소를 호출한다.
            // - PENDING row가 없으면 후속 요청에서 중복 취소를 막을 근거가 약해진다.
            return resolveVanCancelResponse(request, originalAttempt);
        }

        // C5 insert 실패.
        // - 보통은 unique 제약 경합으로 다른 요청이 먼저 cancel row를 만든 경우를 의심한다.
        // - 그래서 곧바로 실패 응답을 내리지 않고 기존 row를 재조회해 재응답을 시도한다.
        return handleInsertPendingMiss(
                request,
                posTrx,
                originalPosTrx,
                originalAttemptSeq
        );

    }

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
    private CancelResponse getCancelResponseFromExistingCancel(
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
     * PENDING cancel row 생성 이후 VAN 취소를 호출하고, 결과를 DB에 확정 저장한다.
     * <p>
     * 이 함수가 처리하는 범위:
     * - VAN 취소 요청 DTO 조립
     * - VAN cancel 호출
     * - VAN 결과별 DB update
     * - update miss 시 보수적 retryLater 응답
     * <p>
     * 중요한 전제:
     * - 이 함수에 들어오기 전 이미 PENDING cancel row가 생성되어 있어야 한다.
     * - 그래야 VAN 호출 도중 장애가 나도 후속 요청이 기존 PENDING row를 보고 중복 호출을 피할 수 있다.
     */
    private CancelResponse resolveVanCancelResponse(
            CancelRequest request,
            PaymentAttempt originalAttempt
    ) {
        String posTrx = request.posTrx();
        String originalPosTrx = request.originalPosTrx();
        int originalAttemptSeq = request.originalAttemptSeq();

        // C6: VAN 취소 요청 DTO 구성.
        // - CancelRequest에는 현취소 거래번호와 원거래 식별자가 있다.
        // - originalAttempt에는 원승인 금액/승인번호/카드요약 등 VAN 취소에 필요한 원거래 정보가 있다.
        // - assembler는 두 객체를 합쳐 VAN 계약에 맞는 취소 전문을 만든다.
        VanCancelRequest vanCancelRequest = vanCancelAssembler.getVanCancelRequest(request, originalAttempt);

        insertCancelEvent(
                PaymentEventType.CANCEL_VAN_REQUESTED,
                posTrx,
                originalPosTrx,
                originalAttemptSeq,
                null,
                CancelStatus.PENDING.name(),
                null,
                null,
                null,
                "VAN cancel requested"
        );

        // C6-1: VAN 취소 호출.
        // - 신규 취소 흐름에서 실제 외부 취소 시도는 여기서 1번만 수행한다.
        VanCancelResponse vanCancelResponse = vanGateway.cancel(vanCancelRequest);
        CancelStatus vanFinalStatus = vanCancelResponse.cancelStatus();
        String responseDeclineCode = toDeclineCode(vanCancelResponse.declineCode());
        insertCancelEvent(
                PaymentEventType.CANCEL_VAN_RESULT_RECEIVED,
                posTrx,
                originalPosTrx,
                originalAttemptSeq,
                resultCodeOf(vanFinalStatus),
                vanFinalStatus.name(),
                vanCancelResponse.vanTrxId(),
                vanCancelResponse.cancelApprovalNo(),
                responseDeclineCode,
                "VAN cancel result received"
        );

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
            // - PAYMENT_CANCEL row를 CANCELLED로 바꾸고 cancelApprovalNo를 저장한다.
            // - 응답은 update RETURNING row 기준으로 조립해 DB 저장값과 응답값을 맞춘다.
            case CANCELLED -> {
                CancelResultUpdateParam updateParam = CancelResultUpdateParam.cancelled(
                        posTrx,
                        originalPosTrx,
                        originalAttemptSeq,
                        vanCancelResponse.cancelApprovalNo()
                );
                Optional<PaymentCancel> updateCancelResult =
                        repository.updateCancelResult(updateParam);

                if (updateCancelResult.isPresent()) {
                    PaymentCancel updatedCancel = updateCancelResult.get();

                    insertCancelEvent(
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
            // - PAYMENT_CANCEL row를 CANCEL_DECLINED로 바꾸고 declineCode를 저장한다.
            // - 이 상태도 최종 상태이므로 이후 같은 원거래 취소 요청은 DB 재응답으로 처리한다.
            case CANCEL_DECLINED -> {
                CancelResultUpdateParam updateParam = CancelResultUpdateParam.declined(
                        posTrx,
                        originalPosTrx,
                        originalAttemptSeq,
                        responseDeclineCode
                );
                Optional<PaymentCancel> updateCancelResult =
                        repository.updateCancelResult(updateParam);

                if (updateCancelResult.isPresent()) {
                    PaymentCancel updatedCancel = updateCancelResult.get();

                    insertCancelEvent(
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
        return recoverFromC7UpdateEmpty(
                request,
                posTrx,
                originalPosTrx,
                originalAttemptSeq
        );

    }

    private Optional<PaymentCancel> findCancelByOriginal(
            String phase,
            String posTrx,
            String originalPosTrx,
            int originalAttemptSeq
    ) {
        Optional<PaymentCancel> cancelOpt =
                repository.findByOriginalPosTrxAndOriginalAttemptSeq(
                        originalPosTrx,
                        originalAttemptSeq
                );

        cancelOpt.ifPresent(cancel ->
                log.info("[cancel][{}] cancel row found. posTrx={}, originalPosTrx={}, originalAttemptSeq={}, cancelStatus={}",
                        phase,
                        posTrx,
                        originalPosTrx,
                        originalAttemptSeq,
                        cancel.cancelStatus()
                )
        );

        return cancelOpt;
    }

    /**
     * PENDING cancel row insert가 실패했을 때의 경합/방어 처리.
     * <p>
     * 이 함수의 역할:
     * - insert 실패를 즉시 장애로 보지 않고, unique 제약 경합으로 기존 row가 생겼는지 재조회한다.
     * - 기존 row가 있으면 그 row의 상태를 기준으로 재응답한다.
     * - 기존 row도 없으면 정상 흐름이 아니므로 로그를 남기고 retryLater로 방어한다.
     */
    private CancelResponse handleInsertPendingMiss(
            CancelRequest request,
            String posTrx,
            String originalPosTrx,
            int originalAttemptSeq
    ) {
        Optional<PaymentCancel> reCancelOpt =
                findCancelByOriginal(
                        "C5-insert-miss",
                        posTrx,
                        originalPosTrx,
                        originalAttemptSeq
                );

        // UNIQUE 제약 경합으로 insert가 실패했으면 먼저 생성된 row를 응답 소스로 사용한다.
        // - 예: 같은 원거래 취소 요청 2개가 거의 동시에 들어온 경우.
        // - 한쪽 insert만 성공하고 다른 쪽은 여기로 내려온 뒤 기존 row를 재응답한다.
        if (reCancelOpt.isPresent())
            return getCancelResponseFromExistingCancel(request, reCancelOpt.get());

        // insert도 실패했고 재조회도 실패한 경우.
        // - 정상적인 unique 경합이라면 row가 보여야 하므로, 이 로그는 DB 반영/트랜잭션/매퍼 쪽 확인 신호다.
        log.error("[cancel][C5-insert-miss][CRITICAL_CANCEL_ROW_NOT_FOUND] pending insert failed but cancel row not found. posTrx={}, originalPosTrx={}, originalAttemptSeq={}",
                posTrx, originalPosTrx, originalAttemptSeq);

        return CancelResponse.retryLater(
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
    private CancelResponse recoverFromC7UpdateEmpty(
            CancelRequest request,
            String posTrx,
            String originalPosTrx,
            int originalAttemptSeq
    ) {
        Optional<PaymentCancel> reCancelOpt =
                findCancelByOriginal(
                        "C7-update-empty",
                        posTrx,
                        originalPosTrx,
                        originalAttemptSeq
                );

        if (reCancelOpt.isEmpty()) {
            log.error("[cancel][C7-recovery][CRITICAL_CANCEL_ROW_NOT_FOUND] update result missed and cancel row not found. posTrx={}, originalPosTrx={}, originalAttemptSeq={}",
                    posTrx, originalPosTrx, originalAttemptSeq);

            return CancelResponse.retryLater(
                    posTrx,
                    originalPosTrx,
                    originalAttemptSeq
            );
        }

        PaymentCancel cancel = reCancelOpt.get();
        log.info("[cancel][C7-recovery] recovered cancel row after update miss. posTrx={}, originalPosTrx={}, originalAttemptSeq={}, recoveredStatus={}",
                posTrx,
                originalPosTrx,
                originalAttemptSeq,
                cancel.cancelStatus()
        );

        return getCancelResponseFromC7RecoveredCancel(cancel);
    }

    /**
     * C7 update empty 이후 재조회된 PAYMENT_CANCEL row를 API 응답으로 변환한다.
     *
     * <p>
     * 기존 row 재응답({@link #getCancelResponseFromExistingCancel(CancelRequest, PaymentCancel)})과
     * 가장 중요한 차이는 CANCELLED 매핑이다.
     *
     * <p>
     * - 기존 row 재응답: 현재 요청은 VAN을 호출하지 않은 중복 요청이므로 CANCELLED -> ALREADY_CANCELLED
     * - C7 복구 응답 : 현재 요청은 VAN을 이미 호출했으므로 CANCELLED -> CANCELLED
     */
    private CancelResponse getCancelResponseFromC7RecoveredCancel(
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

    /**
     * VAN decline enum을 내부 저장/응답용 문자열 코드로 바꾼다.
     * <p>
     * 취소 성공이나 PENDING에서는 declineCode가 없을 수 있으므로 null-safe 변환이 필요하다.
     */
    private String toDeclineCode(VanDeclineCode declineCode) {
        if (declineCode == null) return null;
        return declineCode.code();
    }

    private String resultCodeOf(CancelResultStatus status) {
        return switch (status) {
            case CANCELLED -> ResultCode.OK.name();
            case ALREADY_CANCELLED -> ResultCode.ALREADY_CANCELLED.name();
            case CANCEL_DECLINED -> ResultCode.CANCEL_DECLINED.name();
            case CANCEL_NOT_ALLOWED -> ResultCode.CANCEL_NOT_ALLOWED.name();
            case RETRY_LATER -> ResultCode.RETRY_LATER.name();
        };
    }

    private String resultCodeOf(CancelStatus status) {
        return switch (status) {
            case CANCELLED -> ResultCode.OK.name();
            case CANCEL_DECLINED -> ResultCode.CANCEL_DECLINED.name();
            case PENDING -> ResultCode.RETRY_LATER.name();
        };
    }

    /**
     * 취소 이벤트 로그를 구조화 컬럼만으로 저장한다.
     *
     * <p>
     * CURRENT_TRX_NO에는 항상 이번 요청의 cancel posTrx를 저장하고,
     * 원승인 식별자는 ORIGINAL_* 컬럼에 분리해 저장한다.
     */
    private void insertCancelEvent(
            PaymentEventType eventType,
            String currentTrxNo,
            String originalPosTrx,
            int originalAttemptSeq,
            String resultCode,
            String statusSnapshot,
            String vanTrxId,
            String approvalNo,
            String declineCode,
            String note
    ) {
        paymentEventLogRepository.insert(new PaymentEventLogInsertParam(
                eventType,
                null,
                null,
                currentTrxNo,
                originalPosTrx,
                originalAttemptSeq,
                resultCode,
                statusSnapshot,
                vanTrxId,
                approvalNo,
                declineCode,
                null,
                note
        ));
    }

}
