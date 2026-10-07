package com.chaeyeongmin.payment_sim.payment.application.inquiry.transaction;

import com.chaeyeongmin.payment_sim.common.api.ResultCode;
import com.chaeyeongmin.payment_sim.common.exception.BusinessException;
import com.chaeyeongmin.payment_sim.infra.repository.PaymentCancelRepository;
import com.chaeyeongmin.payment_sim.infra.repository.dto.CancelResultUpdateParam;
import com.chaeyeongmin.payment_sim.payment.application.common.VanDeclineCodeMapper;
import com.chaeyeongmin.payment_sim.payment.api.cancel.CancelResponse;
import com.chaeyeongmin.payment_sim.payment.domain.cancel.CancelStatus;
import com.chaeyeongmin.payment_sim.payment.domain.cancel.PaymentCancel;
import com.chaeyeongmin.payment_sim.van.client.dto.VanInquiryResponse;
import com.chaeyeongmin.payment_sim.van.client.dto.VanInquiryResultCode;
import com.chaeyeongmin.payment_sim.van.client.dto.VanInquiryTargetType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * Cancel UNKNOWN_TIMEOUT에 대한 VAN Inquiry 확정 결과를 DB에 반영하는 TX 경계다.
 *
 * <p>
 * 일반 cancel finalize의 PENDING 전이와 분리해 UNKNOWN_TIMEOUT row만 조건부로 갱신한다.
 * update miss가 발생하면 cancel row를 다시 읽고 실제 DB 상태를 기준으로 응답한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CancelInquiryTxService {

    private final PaymentCancelRepository cancelRepository;

    /**
     * VAN Inquiry(CANCEL)가 확정 결과를 반환한 경우 UNKNOWN_TIMEOUT cancel row를 최종 상태로 전이한다.
     *
     * <p>
     * 조건부 update가 실패하면 동시 Inquiry가 먼저 확정했을 수 있으므로 DB를 재조회한다.
     */
    @Transactional
    public CancelResponse applyResolvedResult(
            PaymentCancel cancel,
            VanInquiryResponse response
    ) {
        // 이 메서드는 VAN I/O 이후의 짧은 DB 확정 transaction만 담당한다.
        // UNKNOWN_TIMEOUT cancel row와 SUCCESS/CANCEL 응답만 받아 상태 전이를 좁게 제한한다.
        if (cancel == null
                || response == null
                || cancel.cancelStatus() != CancelStatus.UNKNOWN_TIMEOUT
                || response.resultCode() != VanInquiryResultCode.SUCCESS
                || response.targetType() != VanInquiryTargetType.CANCEL
                || cancel.posTrx().equals(response.targetTrxNo()) == false
                || response.targetAttemptSeq() != null
                || response.status() == null) {

            throw new BusinessException(
                    ResultCode.INTERNAL_ERROR,
                    "CANCEL_INQUIRY_FINALIZE_INVALID"
            );
        }

        // VAN Inquiry(CANCEL) 응답 status를 PAYMENT_CANCEL 최종 상태 update 파라미터로 변환한다.
        // APPROVAL 계열 status가 섞이면 TCP boundary 검증 누락이므로 여기서도 한 번 더 차단한다.
        CancelResultUpdateParam param = switch (response.status()) {

            case CANCELLED ->
                    CancelResultUpdateParam.cancelled(
                            cancel.posTrx(),
                            cancel.originalPosTrx(),
                            cancel.originalAttemptSeq(),
                            response.vanTrxId(),
                            response.cancelApprovalNo()
                    );

            case CANCEL_DECLINED ->
                    CancelResultUpdateParam.declined(
                            cancel.posTrx(),
                            cancel.originalPosTrx(),
                            cancel.originalAttemptSeq(),
                            response.vanTrxId(),
                            VanDeclineCodeMapper.toCode(response.declineCode())
                    );

            default ->
                    throw new BusinessException(
                            ResultCode.INTERNAL_ERROR,
                            "CANCEL_INQUIRY_STATUS_INVALID"
                    );
        };

        // 기존 updateCancelResult는 PENDING 전용으로 유지한다.
        // Inquiry 복구는 UNKNOWN_TIMEOUT row에만 반영해야 일반 취소 확정 경로와 조건이 섞이지 않는다.
        Optional<PaymentCancel> updated = cancelRepository.updateUnknownTimeoutToFinal(param);

        if (updated.isPresent()) {
            log.info("[cancel-inquiry][finalized] cancelPosTrx={}, status={}, vanTrxId={}",
                    cancel.posTrx(), updated.get().cancelStatus(), response.vanTrxId());
            return responseFromCurrentCancel(updated.get());
        }

        // 같은 UNKNOWN_TIMEOUT row에 동시에 inquiry가 들어오면 한 요청만 update에 성공할 수 있다.
        // update miss 시에는 cancel posTrx로 재조회해서 이미 확정된 DB 상태를 정본으로 응답한다.
        Optional<PaymentCancel> reread = cancelRepository.findByPosTrx(cancel.posTrx());
        log.info("[cancel-inquiry][update-miss] cancelPosTrx={}, rereadPresent={}",
                cancel.posTrx(), reread.isPresent());

        if (reread.isEmpty()) {
            log.error(
                    "[cancel-inquiry] update missed and cancel row not found. posTrx={}",
                    cancel.posTrx()
            );

            return CancelResponse.retryLater(
                    cancel.posTrx(),
                    cancel.originalPosTrx(),
                    cancel.originalAttemptSeq()
            );
        }

        return responseFromCurrentCancel(reread.get());
    }

    /**
     * PAYMENT_CANCEL 현재 row 상태를 cancel inquiry API 응답으로 변환한다.
     *
     * <p>
     * 기존 재취소 요청에서는 CANCELLED를 ALREADY_CANCELLED로 응답하지만,
     * cancel inquiry는 저장된 최종 결과를 확인하는 API이므로 CANCELLED를 그대로 반환한다.
     */
    private CancelResponse responseFromCurrentCancel(PaymentCancel cancel) {
        return switch (cancel.cancelStatus()) {

            case CANCELLED ->
                    CancelResponse.cancelled(
                            cancel.posTrx(),
                            cancel.originalPosTrx(),
                            cancel.originalAttemptSeq(),
                            cancel.cancelApprovalNo()
                    );

            case CANCEL_DECLINED ->
                    CancelResponse.declined(
                            cancel.posTrx(),
                            cancel.originalPosTrx(),
                            cancel.originalAttemptSeq(),
                            cancel.declineCode()
                    );

            case PENDING, UNKNOWN_TIMEOUT ->
                    CancelResponse.retryLater(
                            cancel.posTrx(),
                            cancel.originalPosTrx(),
                            cancel.originalAttemptSeq()
                    );
        };
    }

}
