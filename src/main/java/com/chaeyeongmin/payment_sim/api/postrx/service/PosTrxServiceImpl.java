package com.chaeyeongmin.payment_sim.api.postrx.service;

import com.chaeyeongmin.payment_sim.api.postrx.dto.PosTrxEotRequest;
import com.chaeyeongmin.payment_sim.api.postrx.dto.PosTrxEotResponse;
import com.chaeyeongmin.payment_sim.api.postrx.dto.PosTrxIssueRequest;
import com.chaeyeongmin.payment_sim.api.postrx.dto.PosTrxIssueResponse;
import com.chaeyeongmin.payment_sim.infra.repository.PosTrxSequenceRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * PosTrxServiceImpl
 * <p>
 * [역할]
 * - 포스TR(거래번호) 발급/관리의 핵심 비즈니스 로직을 수행하는 Service 계층 구현체.
 * - 거래번호 발급을 위해 POS_TRX_SEQUENCE 저장소(Repository)를 호출해 시퀀스를 증가시키고,
 * 발급 규칙(포맷/조합)을 적용해 최종 거래번호 문자열을 생성한다.
 * <p>
 * [책임 범위]
 * - 포스TR 발급 규칙(포맷, 자리수, 구성요소) 확정 및 생성
 * - 시퀀스 증가(원자성/동시성) 및 저장소 연동
 * - 필요 시 입력값 검증(점포/영업일/포스번호), 예외 변환, 도메인 이벤트 로깅 등
 */
@Service
@RequiredArgsConstructor
public class PosTrxServiceImpl implements PosTrxService {

    private final PosTrxSequenceRepository posTrxSequenceRepository;

    @Override
    public PosTrxIssueResponse issue(PosTrxIssueRequest request) {
        // TODO: POS_TRX_SEQUENCE 사용해서 pos_trx 발급
        return new PosTrxIssueResponse(null);
    }

    /**
     * - EOT(End Of Transaction) 호출 시점에 "다음 포스TR(거래번호)"를 발급한다.
     * - POS_TRX_SEQUENCE에서 (store_cd, biz_date, pos_no) 기준으로 시퀀스를 1 증가시킨 값을 받아오고,
     * 발급 규칙에 맞춰 거래번호 문자열을 조합하여 응답한다.
     */
    @Override
    public PosTrxEotResponse eot(PosTrxEotRequest request) {
        // TODO: 다음 pos_trx 발급(EOT)
        long nextSeq = posTrxSequenceRepository.nextSeq(
                request.getStore_cd(),
                request.getBiz_date(),
                request.getPos_no()
        );

        return new PosTrxEotResponse(
                request.getStore_cd(),
                request.getBiz_date(),
                request.getPos_no(),
                getNextTran(request, nextSeq)
        );
    }

    // 최종 포스 TR 제작 메소드
    private String getNextTran(PosTrxEotRequest request, Long nextSeq) {
        return String.format("%s-%s-%s-%04d",
                request.getStore_cd(),
                request.getBiz_date(),
                request.getPos_no(),
                nextSeq);
    }

}
