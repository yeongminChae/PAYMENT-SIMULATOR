package com.chaeyeongmin.payment_sim.api.payment.service.impl;

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
import com.chaeyeongmin.payment_sim.infra.repository.PaymentCancelRepository;
import com.chaeyeongmin.payment_sim.infra.repository.dto.CancelInsertParam;
import com.chaeyeongmin.payment_sim.van.client.assembler.VanCancelAssembler;
import com.chaeyeongmin.payment_sim.van.client.dto.VanCancelRequest;
import com.chaeyeongmin.payment_sim.van.client.dto.VanCancelResponse;
import com.chaeyeongmin.payment_sim.van.client.dto.enums.VanDeclineCode;
import com.chaeyeongmin.payment_sim.van.gateway.VanGateway;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentCancelServiceImpl implements PaymentCancelService {

    private final PaymentCancelRepository repository;
    private final VanGateway vanGateway;
    private final CancelRequestValidator validator;
    private final VanCancelAssembler vanCancelAssembler;

    @Override
    public CancelResponse cancel(CancelRequest request) {
        // C1: 취소 요청은 Controller에서 수신/로깅

        // C2: 취소 유효성 검증
        validator.validate(request);

        // C3: 취소 대상 원거래 attempt 조회
        String posTrx = request.posTrx();
        String originalPosTrx = request.originalPosTrx();
        int originalAttemptSeq = request.originalAttemptSeq();

        Optional<PaymentAttempt> originalAttemptOpt =
                repository.findOriginalAttempt(originalPosTrx, originalAttemptSeq);

        // C3-1: 원거래 없음
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

        // C3-2: 원거래 존재
        PaymentAttempt originalAttempt = originalAttemptOpt.get();

        log.info("[cancel][C3] original attempt found. posTrx={}, originalPosTrx={}, originalAttemptSeq={}, originalFinalStatus={}",
                posTrx,
                originalPosTrx,
                originalAttemptSeq,
                originalAttempt.finalStatus()
        );

        // C4-1: 원거래 상태 검증
        PaymentFinalStatus originalStatus = originalAttempt.getFinalStatusEnum();

        // 원거래 상태가 APPROVED일 경우에만 취소 가능하게끔
        if (originalStatus != PaymentFinalStatus.APPROVED) {
            log.info("[cancel][C4] cancel not allowed. posTrx={}, originalPosTrx={}, originalAttemptSeq={}, originalStatus={}",
                    request.posTrx(),
                    request.originalPosTrx(),
                    request.originalAttemptSeq(),
                    originalStatus
            );

            return CancelResponse.cancelNotAllowed(
                    request.posTrx(),
                    request.originalPosTrx(),
                    request.originalAttemptSeq(),
                    "ORIGINAL_NOT_APPROVED"
            );
        }

        // C4-2: 기존 취소 row 확인
        Optional<PaymentCancel> cancelOpt =
                repository.findByOriginalPosTrxAndOriginalAttemptSeq(
                        originalPosTrx,
                        originalAttemptSeq
                );

        // C4-2: 기존 취소 row 확인
        // - 원거래(originalPosTrx + originalAttemptSeq)에 대해 이미 생성된 cancel row가 있는지 조회한다.
        // - MVP 전액취소 정책에서는 원거래 1건당 취소 row 1건만 허용한다.
        // - 기존 cancel row가 있으면 VAN 취소를 다시 호출하지 않고 DB 상태 기준으로 재응답한다.
        if (cancelOpt.isPresent()) {
            PaymentCancel cancel = cancelOpt.get();

            log.info("[cancel][C4] existing cancel row found. posTrx={}, originalPosTrx={}, originalAttemptSeq={}, cancelStatus={}",
                    request.posTrx(),
                    request.originalPosTrx(),
                    request.originalAttemptSeq(),
                    cancel.cancelStatus()
            );

            // C4-2-1: 기존 cancel row가 있는 경우
            // - cancelStatus를 확인해서 상태별 응답을 만든다.
            return getCancelResponseFromExistingCancel(request, cancel);
        }

        // C4-2-2: 기존 cancel row가 없는 경우
        // - 원거래는 APPROVED이고, 기존 취소 row도 없으므로 신규 취소 진행 가능 상태다.
        // - 여기까지 통과하면 다음 단계인 C5(PENDING cancel row 생성)로 넘어간다.
        CancelInsertParam insertParam = CancelInsertParam.pending(
                posTrx,
                originalPosTrx,
                originalAttemptSeq
        );

        Optional<PaymentCancel> insertedCancelOpt = repository.insertPendingCancel(insertParam);

        if (insertedCancelOpt.isPresent()) {
            PaymentCancel insertedCancel = insertedCancelOpt.get();

            log.info("[cancel][C5] pending cancel row created. posTrx={}, originalPosTrx={}, originalAttemptSeq={}, cancelStatus={}",
                    posTrx,
                    originalPosTrx,
                    originalAttemptSeq,
                    insertedCancel.cancelStatus()
            );

            return resolveVanCancelResponse(request, originalAttempt);
        }

        // TODO: C5 insert 실패 시 기존 cancel row 재조회 후 상태별 응답 필요
        // - PENDING -> RETRY_LATER
        // - CANCELLED -> ALREADY_CANCELLED
        // - CANCEL_DECLINED -> DECLINED

        return CancelResponse.retryLater(
                posTrx,
                originalPosTrx,
                originalAttemptSeq
        );

    }

    // C4-2-1: 기존 cancel row가 있는 경우
    // - cancelStatus를 확인해서 상태별 응답을 만든다.
    // - 이 분기에서는 C5 cancel row 생성도 하지 않고, C6 VAN 취소 호출도 하지 않는다.
    private CancelResponse getCancelResponseFromExistingCancel(
            CancelRequest request,
            PaymentCancel cancel
    ) {
        CancelStatus cancelStatus = cancel.getCancelStatusEnum();
        String posTrx = request.posTrx();
        String originalPosTrx = request.originalPosTrx();
        int originalAttemptSeq = request.originalAttemptSeq();

        return switch (cancelStatus) {
            // - PENDING -> 취소 처리 중/미확정이므로 RETRY_LATER
            case PENDING -> CancelResponse.retryLater(
                    posTrx,
                    originalPosTrx,
                    originalAttemptSeq
            );

            // - CANCELLED -> 이미 취소 완료된 거래이므로 ALREADY_CANCELLED
            case CANCELLED -> CancelResponse.alreadyCancelled(
                    posTrx,
                    originalPosTrx,
                    originalAttemptSeq,
                    cancel.cancelApprovalNo()
            );

            // - CANCEL_DECLINED -> 이전 취소 요청이 VAN에서 거절된 상태이므로 DECLINED 재응답
            case CANCEL_DECLINED -> CancelResponse.declined(
                    posTrx,
                    originalPosTrx,
                    originalAttemptSeq,
                    cancel.declineCode()
            );

        };

    }

    private CancelResponse resolveVanCancelResponse(
            CancelRequest request,
            PaymentAttempt originalAttempt
    ) {
        // C5 성공: PENDING row 생성 완료
        String posTrx = request.posTrx();
        String originalPosTrx = request.originalPosTrx();
        int originalAttemptSeq = request.originalAttemptSeq();

        // C6~C8은 후속 구현 예정이므로 현재는 retryLater 응답
        VanCancelRequest vanCancelRequest = vanCancelAssembler.getVanCancelRequest(request, originalAttempt);

        VanCancelResponse vanCancelResponse = vanGateway.cancel(vanCancelRequest);
        CancelStatus vanFinalStatus = vanCancelResponse.cancelStatus();
        String responseDeclineCode = toDeclineCode(vanCancelResponse.declineCode());

        // TODO: C7 구현 필요
        // - CANCELLED 응답이면 PAYMENT_CANCEL 상태를 CANCELLED로 update 후 DB row 기준 응답
        // - CANCEL_DECLINED 응답이면 PAYMENT_CANCEL 상태를 CANCEL_DECLINED로 update 후 DB row 기준 응답
        // - 현재는 C6 VAN 호출 연결 확인용 임시 응답
        switch (vanFinalStatus) {
            // PENDING -> DB 저장 없이 메서드 즉시 리턴
            case PENDING -> {
                return CancelResponse.retryLater(
                        posTrx,
                        originalPosTrx,
                        originalAttemptSeq
                );
            }

            // CANCELLED -> DB 업데이트 후 메서드 리턴
            case CANCELLED -> {
                // DB 업데이트 처리 로직

                return CancelResponse.cancelled(
                        posTrx,
                        originalPosTrx,
                        originalAttemptSeq,
                        vanCancelResponse.cancelApprovalNo()
                );
            }

            // - CANCEL_DECLINED -> 이전 취소 요청이 VAN에서 거절된 상태이므로 DECLINED 재응답
            case CANCEL_DECLINED -> {
                // DB 업데이트 처리 로직

                return CancelResponse.declined(
                        posTrx,
                        originalPosTrx,
                        originalAttemptSeq,
                        responseDeclineCode
                );
            }

        };

        return CancelResponse.retryLater(
                posTrx,
                originalPosTrx,
                originalAttemptSeq
        );

    }

    private String toDeclineCode(VanDeclineCode declineCode) {
        if (declineCode == null) return null;
        return declineCode.code();
    }

}
