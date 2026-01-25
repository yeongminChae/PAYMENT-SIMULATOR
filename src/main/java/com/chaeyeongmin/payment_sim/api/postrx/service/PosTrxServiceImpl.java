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
     * [20260125] 코드 중복 사항 리팩토링
     * [20260125] 결함 수정 UT_ID_EOT_002, UT_ID_EOT_004
     */
    @Override
    public PosTrxEotResponse eot(PosTrxEotRequest request) {

        // [20260125] 결함 수정
        // 결함 사항 : UT_ID_EOT_002 실패
        // 수정 사항 : 입력 값, 유효성 체크 후, 예외 리턴 하게끔 수정
        if (isIllegalValidation(request))
            throw new IllegalArgumentException("입력 값 유효성 검사에 실패 했습니다.");

        String storeCd = request.getStoreCd();
        String bizDate = request.getBizDate();
        String posNo = request.getPosNo();
        long nextSeq = posTrxSequenceRepository.nextSeq(storeCd, bizDate, posNo);

        // [20260125] 결함 수정
        // 결함 사항 : UT_ID_EOT_004 실패
        // 수정 사항 : repo.nextSeq(...) 결과가 포스TR 규격 범위를 벗어날 시, 예외 리턴
        if (nextSeq < 1 || nextSeq > 9999)
            throw new IllegalStateException("관리자 호출 필요, 사유 : 포스 TR은 1부터 9999 사이 값 이어야 합니다.");

        return new PosTrxEotResponse(
                storeCd,
                bizDate,
                posNo,
                // 최종 포스 TR 제작
                String.format("%s-%s-%s-%04d", storeCd, bizDate, posNo, nextSeq)
        );
    }

    private boolean isIllegalValidation(PosTrxEotRequest request) {
        return request == null
                || isNullOrBlank(request.getStoreCd())
                || isNullOrBlank(request.getBizDate())
                || isNullOrBlank(request.getPosNo());
    }

    private boolean isNullOrBlank(String str) {
        return str == null || str.isBlank();
    }

}
