package com.chaeyeongmin.payment_sim.van.gateway;


import com.chaeyeongmin.payment_sim.api.payment.dto.enums.PaymentFinalStatus;
import com.chaeyeongmin.payment_sim.van.client.dto.*;
import com.chaeyeongmin.payment_sim.van.client.dto.enums.VanDeclineCode;
import com.chaeyeongmin.payment_sim.van.factory.VanApproveResponseFactory;
import com.chaeyeongmin.payment_sim.van.factory.VanInquiryResponseFactory;
import com.chaeyeongmin.payment_sim.van.validate.VanApproveRequestValidator;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

@Component
@RequiredArgsConstructor
public class SimulatedVanGateway implements VanGateway {

    private final VanApproveRequestValidator approveValidator;
    private final VanApproveResponseFactory approveRespFactory;
    private final VanInquiryResponseFactory inquiryRespFactory;

    @Override
    public VanApproveResponse approve(VanApproveRequest request) {

        // [A6] VAN 승인 호출(시뮬레이터)
        // - 실제 VAN 서버라면 "요청 수신 → 인증/검증 → 승인 룰/리스크 판단 → 승인/거절/타임아웃 응답"의 역할을 한다.
        // - 지금은 외부 통신 없이, 입력값을 기반으로 "결정적인 규칙"으로 결과를 만들어낸다.

        // 1) 입력 검증(방어)
        // - VAN "입력값 그대로 전달 원칙"이라도, 시뮬레이터는 최소한의 형식
        approveValidator.validate(request);

        // 2) 가짜 네트워크/처리 지연
        // - 실제 VAN 호출은 네트워크 왕복 + VAN 처리시간이 존재한다.
        // - 시뮬레이터는 "현실감"을 위해 일정 지연을 흉내낸다.
        sleepSilently(50);

        // 3) 응답 생성을 위한 공통 값 추출
        // - posTrx/attemptSeq는 상위(포스서버)에서 생성/관리하는 추적 키
        // - vanTrxId는 VAN 내부 추적용 ID라고 가정(로그/조회/취소 연계에 필요)
        // - respondedAt은 VAN이 응답을 생성한 시각(관측 포인트)
        String posTrx = request.posTrx();
        int attemptSeq = request.attemptSeq();
        String vanTrxId = makeVanTrxId(posTrx, attemptSeq);
        LocalDateTime now = LocalDateTime.now();

        // 4) 타임아웃 규칙(시뮬레이터 전용)
        // - attemptSeq가 7의 배수이면 "VAN 타임아웃/미확정" 상황을 인위적으로 만든다.
        // - 실제로는 네트워크 장애/응답 지연/서버 과부하 등으로 발생할 수 있는 케이스를 흉내.
        // - declineCode는 TIMEOUT 같은 내부 코드를 사용(후속 A7 저장 & A4 재조회 분기 테스트용)
        if (attemptSeq % 7 == 0) {
            return approveRespFactory.unknownTimeout(
                    posTrx, attemptSeq, VanDeclineCode.TIMEOUT, vanTrxId, now
            );
        }

        // 5) 승인/거절 규칙(시뮬레이터 전용)
        // - 예: last4의 마지막 자리(0~9)가 짝수면 APPROVED, 홀수면 DECLINED
        if (isApprovedRule(request.cardLast4())) {
            // 승인인 경우 승인번호(approvalNo) 생성
            // - 실제 VAN 승인번호는 고유값이며, 승인 성공 응답에 포함된다.
            return approveRespFactory.approved(
                    posTrx, attemptSeq, makeApprovalNo(), vanTrxId, now
            );
        }

        // 6) 거절 응답
        // - 여기서는 대표적인 거절코드 DO_NOT_HONOR(05)로 통일
        // - 실제 VAN/카드사에서는 거절 사유가 다양하며 코드도 훨씬 많다.
        return approveRespFactory.declined(
                posTrx, attemptSeq, VanDeclineCode.DO_NOT_HONOR, vanTrxId, now
        );
    }

    @Override
    public VanInquiryResponse inquiry(VanInquiryRequest request) {
        // - posTrx/attemptSeq는 상위(포스서버)에서 생성/관리하는 추적 키
        // - vanTrxId는 VAN 내부 추적용 ID라고 가정(로그/조회/취소 연계에 필요)
        // - respondedAt은 VAN이 응답을 생성한 시각(관측 포인트)
        String posTrx = request.posTrx();
        int attemptSeq = request.attemptSeq();
        String vanTrxId = makeVanTrxId(posTrx, attemptSeq);
        String cardLast4 = request.cardLast4();
        LocalDateTime now = LocalDateTime.now();

        if ("0000".equals(cardLast4)) {
            return inquiryRespFactory.unknownTimeout(
                    posTrx, attemptSeq, VanDeclineCode.TIMEOUT, vanTrxId, now
            );
        }

        // PaymentFinalStatus.APPROVED
        if (isApprovedRule(cardLast4)) {
            // 승인인 경우 승인번호(approvalNo) 생성
            // - 실제 VAN 승인번호는 고유값이며, 승인 성공 응답에 포함된다.
            return inquiryRespFactory.approved(
                    posTrx, attemptSeq, makeApprovalNo(), vanTrxId, now
            );
        }

        // PaymentFinalStatus.DECLINED
        return inquiryRespFactory.declined(
                posTrx, attemptSeq, VanDeclineCode.DO_NOT_HONOR, vanTrxId, now
        );

    }

    @Override
    public VanCancelResponse cancel(VanCancelRequest request) {
        // [A6-확장] VAN 취소 시뮬레이터
        // - 실제 흐름: 원승인(vanTrxId/approvalNo 등)을 기반으로 취소 승인 요청을 보낸다.
        // - 구현 포인트:
        //   1) 원거래 존재 여부 확인(없으면 INVALID_ORIGINAL 같은 에러)
        //   2) 이미 취소되었는지(중복취소) 확인
        //   3) 취소 승인번호/취소 결과 생성
        // - 지금은 TODO: 원승인 상태를 저장/조회할 구조(메모리/DB)가 필요
        return new VanCancelResponse();
    }

    private static void sleepSilently(long ms) {
        // 시뮬레이터 지연 처리 유틸
        // - Thread.sleep은 InterruptedException을 발생시킬 수 있으므로,
        //   인터럽트 상태를 복구(Thread.currentThread().interrupt())한 뒤 런타임 예외로 감싼다.
        // - (주의) 실제 서비스에서는 "블로킹 sleep"을 남발하면 스레드 리소스를 잡아먹는다.
        //   하지만 지금은 시뮬레이터이며 지연을 명확히 보여주기 위한 최소 구현이다.
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        }
    }

    private String makeVanTrxId(String posTrx, int attemptSeq) {
        // 시뮬레이터용 VAN 트랜잭션 ID 생성
        // - 결정적(deterministic) 생성: 같은 입력이면 같은 vanTrxId가 나온다.
        // - 장점: 디버깅/재현/조회 테스트가 쉽다.
        // - 예: "2376-20260208-9991-0001-01"
        return posTrx + "-" + String.format("%02d", attemptSeq);
    }

    private String makeApprovalNo() {
        // 시뮬레이터용 승인번호 생성
        // - 실제 승인번호는 VAN/카드사가 발급하는 고유값
        // - 여기서는 nanoTime 기반으로 "대충 그럴듯한" 값 생성
        // - (주의) 완전한 유일성 보장은 아님. 시뮬레이터 수준에서는 충분.
        long t = System.nanoTime() % 1_000_000_000L;
        return String.format("A%09d", t);
    }

    private static boolean isApprovedRule(String last4) {
        // 시뮬레이터 승인 규칙
        // - cardLast4의 마지막 숫자가 짝수면 승인, 홀수면 거절
        // - 예: last4=1112(짝수) -> APPROVED, last4=1111(홀수) -> DECLINED
        //
        // 전제:
        // - approveValidator에서 cardLast4가 숫자 문자열임이 보장되어야 한다.
        char lastDigit = last4.charAt(last4.length() - 1);

        return ((lastDigit - '0') % 2) == 0;
    }

}