package com.chaeyeongmin.payment_sim.van.client.assembler;

import com.chaeyeongmin.payment_sim.van.client.dto.VanInquiryRequest;
import org.springframework.stereotype.Component;

@Component
public class VanInquiryAssembler {
    /**
     * Q5: Inquiry 대상 attempt 정보를 VanInquiryRequest로 구성한다.
     * MVP에서는 VAN 내부 저장소를 따로 두지 않으므로
     * posTrx, attemptSeq, cardLast4를 전달해 시뮬레이터 규칙으로 결과를 만든다.
     */
    public VanInquiryRequest getVanInquiryRequest(
            String posTrx,
            int attemptSeq,
            String cardLast4
    ) {
        return VanInquiryRequest.builder()
                .posTrx(posTrx)
                .attemptSeq(attemptSeq)
                .vanTrxId(null) // TODO: 추후 원거래 vanTrxId 연결 이후 설정
                .cardLast4(cardLast4)
                .build();
    }

}
