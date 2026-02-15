package com.chaeyeongmin.payment_sim.van.client.assembler;

import com.chaeyeongmin.payment_sim.api.payment.dto.ApproveRequest;
import com.chaeyeongmin.payment_sim.api.payment.dto.card.CardInput;
import com.chaeyeongmin.payment_sim.van.client.dto.VanApproveRequest;
import org.springframework.stereotype.Component;

/**
 * [Assembler] VAN 승인 요청 조립기 (A5)
 *
 * 역할:
 * - 내부 승인 요청(ApproveRequest) + 서버에서 발급한 식별자(posTrx, attemptSeq)를
 *   외부 VAN 호출용 DTO(VanApproveRequest)로 변환/구성한다.
 *
 * 설계 의도:
 * - 서비스(PaymentApprovalServiceImpl)는 "흐름 제어"에 집중하고,
 *   DTO 조립(필드 매핑/파생값 계산)은 본 Assembler로 분리한다.
 * - VanApproveRequest가 ApproveRequest(API DTO)를 직접 참조하지 않도록(계층 침범 방지)
 *   서비스에서 필요한 최소 입력값만 넘기고, 여기서 매핑만 수행한다.
 *
 * 보안/주의:
 * - pan/expiry는 민감정보이므로, 이 클래스에서 절대 로그로 남기지 않는다.
 * - card.bin8()/last4()는 ApproveRequestValidator(A2)에서 PAN 길이/숫자 형식이
 *   보장된 이후에만 호출된다는 전제하에 안전하다.
 *
 * 사용 위치:
 * - A5 단계: VAN 승인 요청 데이터 구성(외부 호출 직전)
 */
@Component
public class VanApproveAssembler {
    /**
     * A5: ApproveRequest -> VanApproveRequest 변환(구성)
     */
    public VanApproveRequest getVanApproveRequest(String posTrx, int attemptSeq, ApproveRequest req) {
        CardInput card = req.getCard();

        return VanApproveRequest.builder()
                .posTrx(posTrx)
                .attemptSeq(attemptSeq)
                .amount(req.getAmount())
                .pan(card.getPan())
                .expiryYyMm(card.getExpiryYyMm())
                .cardBin(card.bin8())
                .cardLast4(card.last4())
                .build();
    }

}
