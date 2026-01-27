package com.chaeyeongmin.payment_sim.api.payment.service.impl;

import com.chaeyeongmin.payment_sim.api.payment.dto.ApproveRequest;
import com.chaeyeongmin.payment_sim.api.payment.dto.ApproveResponse;
import com.chaeyeongmin.payment_sim.api.payment.dto.card.CardInput;
import com.chaeyeongmin.payment_sim.api.payment.service.PaymentApprovalService;
import org.springframework.stereotype.Service;

/**
 * [Service]
 * 결제 승인(Approve) 유스케이스의 “오케스트레이션(흐름 제어)”을 담당한다.
 * <p>
 * 책임(Responsibility)
 * - (IMP-A2) 승인 요청의 입력 유효성 검증을 1곳으로 묶어 수행한다.
 * - (IMP-A3) attempt_seq(승인 시도 번호)를 서버에서 발급/확보하고 DB에 기록한다.
 * - (향후) 중복/처리중 분기, VAN 호출, 타임아웃 후속조회, 최종결과 저장/재응답 등을 순서대로 연결한다.
 * <p>
 * 비고(현재 단계)
 * - MVP 단계에서는 validate() 통과 시 attempt 발급/저장까지 먼저 완성한다.
 * - INVALID는 “DB/VAN을 타지 않고” 즉시 종료되어야 하므로 validate()에서 예외로 끊는 방식을 권장한다.
 */
@Service
public class PaymentApprovalServiceImpl implements PaymentApprovalService {

    /**
     * [Use Case Entry]
     * 승인 요청을 처리하는 메인 엔트리 포인트.
     * attempt_seq는 클라이언트가 보내지 않으며, 서버가 발급/관리한다.
     * 특정 posTrx에 대해 “다음 attempt_seq”를 발급받고, attempt row를 DB에 저장한다.
     */
    @Override
    public ApproveResponse approve(ApproveRequest request) {

        // 승인 요청 유효성 검증 묶음
        validate(request);

        return new ApproveResponse();
    }

    /*
    - **검증**(MVP 최소)
    - 거래번호/attempt_seq/금액/결제수단 필수값 존재
    - 금액 > 0
    - 카드 기본 유효성(길이/숫자/Luhn 등) 및 BIN 매핑 존재 여부
    - 실패 시 **A2-F → INVALID 응답**(외부 호출/DB 확정 저장 없이 종료)
     */
    private String validate(ApproveRequest request) {
        return isValidate(request) ? "VALID" : "INVALID"; // 값 추후 공통 상수로 처리
    }

    // 승인 인풋 유효성 검사, 전부 참이어야 참을 리턴함
    private boolean isValidate(ApproveRequest request) {
        return request != null
                && isNotNullOrBlank(request.getPosTrx())
                && isNotNullOrZero(request.getAmount())
                && isValidCard(request);
    }

    // 카드 유효성 검사
    private boolean isValidCard(ApproveRequest request) {
        if (request.getCard() == null) return false;

        CardInput card = request.getCard();
        String pan = card.getPan();
        String expiryYyMm = card.getExpiryYyMm();

        return card.validate(pan, expiryYyMm);
    }

    private boolean isNotNullOrBlank(String str) {
        return str != null && str.isBlank() == false;
    }

    private boolean isNotNullOrZero(Integer amt) {
        return amt != null && amt > 0;
    }

}