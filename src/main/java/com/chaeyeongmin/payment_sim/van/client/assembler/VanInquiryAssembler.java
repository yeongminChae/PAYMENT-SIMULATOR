package com.chaeyeongmin.payment_sim.van.client.assembler;

import com.chaeyeongmin.payment_sim.van.client.dto.VanInquiryRequest;
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
                .posTrx(posTrx)
                .attemptSeq(attemptSeq)
                .vanTrxId(vanTrxId) // TODO: 추후 원거래 vanTrxId 연결 이후 설정
                .cardLast4(cardLast4)
                .build();
    }

}
