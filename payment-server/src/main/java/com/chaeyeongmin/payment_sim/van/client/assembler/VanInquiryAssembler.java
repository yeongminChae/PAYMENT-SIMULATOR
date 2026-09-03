package com.chaeyeongmin.payment_sim.van.client.assembler;

import com.chaeyeongmin.payment_sim.van.client.dto.VanInquiryRequest;
import com.chaeyeongmin.payment_sim.van.client.dto.VanInquiryTargetType;
import com.chaeyeongmin.payment_sim.van.client.policy.VanTraceIdPolicy;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class VanInquiryAssembler {

    private final VanTraceIdPolicy vanTraceIdPolicy;

    /**
     * Q5: Inquiry 대상 attempt 정보를 VanInquiryRequest로 구성한다.
     * MVP에서는 VAN 내부 저장소를 따로 두지 않으므로
     * posTrx, attemptSeq, cardLast4를 전달해 시뮬레이터 규칙으로 결과를 만든다.
     */
    public VanInquiryRequest getVanInquiryRequest(
            String posTrx,
            int attemptSeq,
            String cardLast4,
            String storedVanTrxId
    ) {
        String vanTrxId = vanTraceIdPolicy.resolveVanTrxId(
                posTrx,
                attemptSeq,
                storedVanTrxId
        );

        return VanInquiryRequest.builder()
                .targetType(VanInquiryTargetType.APPROVAL)
                .targetTrxNo(posTrx)
                .targetAttemptSeq(attemptSeq)
                .vanTrxId(vanTrxId) // 저장된 VAN 추적키가 있으면 사용하고, 없으면 fallback 정책으로 생성
                .cardLast4(cardLast4)
                .build();
    }

    /**
     * VAN Inquiry(CANCEL) 요청을 R5 공용 Inquiry 계약으로 구성한다.
     *
     * <p>
     * CANCEL 조회의 targetTrxNo는 cancelPosTrx이고 targetAttemptSeq는 null이어야 한다.
     * vanTrxId/cardLast4는 승인 조회 호환용 업무 DTO 필드이므로 TCP 전문에는 사용하지 않는다.
     */
    public VanInquiryRequest getCancelInquiryRequest(String cancelPosTrx) {
        return VanInquiryRequest.builder()
                .targetType(VanInquiryTargetType.CANCEL)
                .targetTrxNo(cancelPosTrx)
                .targetAttemptSeq(null)
                .vanTrxId(null)
                .cardLast4(null)
                .build();
    }

}
