package com.chaeyeongmin.payment_sim.api.postrx.service;

import com.chaeyeongmin.payment_sim.api.postrx.dto.PosTrxEotResponse;
import com.chaeyeongmin.payment_sim.api.postrx.dto.PosTrxIssueRequest;
import com.chaeyeongmin.payment_sim.api.postrx.dto.PosTrxIssueResponse;
import com.chaeyeongmin.payment_sim.common.api.ResultCode;
import com.chaeyeongmin.payment_sim.common.exception.BusinessException;
import com.chaeyeongmin.payment_sim.infra.repository.PosTrxSequenceRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;

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

    private static final String INVALID_POS_TRX_ISSUE_REQUEST = "INVALID_POS_TRX_ISSUE_REQUEST";
    private static final String POS_TRX_SEQUENCE_OUT_OF_RANGE = "POS_TRX_SEQUENCE_OUT_OF_RANGE";
    private static final DateTimeFormatter BIZ_DATE_FORMATTER =
            DateTimeFormatter.ofPattern("uuuuMMdd")
                    .withResolverStyle(ResolverStyle.STRICT);

    private final PosTrxSequenceRepository posTrxSequenceRepository;

    @Override
    public PosTrxIssueResponse issue(PosTrxIssueRequest request) {
        validateIssueRequest(request);

        String storeCd = request.storeCd();
        String bizDate = request.bizDate();
        String posNo = request.posNo();

        long nextSeq = posTrxSequenceRepository.nextSeq(storeCd, bizDate, posNo);

        if (nextSeq < 1 || nextSeq > 9999) {
            throw new BusinessException(ResultCode.INTERNAL_ERROR, POS_TRX_SEQUENCE_OUT_OF_RANGE);
        }

        String posTrx = String.format("%s-%s-%s-%04d", storeCd, bizDate, posNo, nextSeq);

        return new PosTrxIssueResponse(posTrx);

    }

    /**
     * - EOT(End Of Transaction) 호출 시점에 "다음 포스TR(거래번호)"를 발급한다.
     * - POS_TRX_SEQUENCE에서 (store_cd, biz_date, pos_no) 기준으로 시퀀스를 1 증가시킨 값을 받아오고,
     * 발급 규칙에 맞춰 거래번호 문자열을 조합하여 응답한다.
     * [20260125] 코드 중복 사항 리팩토링
     * [20260125] 결함 수정 UT_ID_EOT_002, UT_ID_EOT_004
     */
    @Override
    public PosTrxEotResponse eot(PosTrxIssueRequest request) {
        PosTrxIssueResponse issueResponse = issue(request);

        return new PosTrxEotResponse(
                request.storeCd(),
                request.bizDate(),
                request.posNo(),
                issueResponse.getPos_trx()
        );
    }

    private void validateIssueRequest(PosTrxIssueRequest request) {
        if (request == null
                || isNullOrBlank(request.storeCd())
                || isNullOrBlank(request.bizDate())
                || isNullOrBlank(request.posNo())
                || isFourDigitNumber(request.storeCd()) == false
                || isValidBizDate(request.bizDate()) == false
                || isFourDigitNumber(request.posNo()) == false) {
            throw new BusinessException(ResultCode.INVALID, INVALID_POS_TRX_ISSUE_REQUEST);
        }
    }

    private boolean isNullOrBlank(String str) {
        return str == null || str.isBlank();
    }

    private boolean isFourDigitNumber(String value) {
        return value != null && value.matches("\\d{4}");
    }

    private boolean isValidBizDate(String bizDate) {
        if (bizDate == null || bizDate.matches("\\d{8}") == false) {
            return false;
        }

        try {
            LocalDate.parse(bizDate, BIZ_DATE_FORMATTER);
            return true;
        } catch (DateTimeParseException e) {
            return false;
        }
    }

}
