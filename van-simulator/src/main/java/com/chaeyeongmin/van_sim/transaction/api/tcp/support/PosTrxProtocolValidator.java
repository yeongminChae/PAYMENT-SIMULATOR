package com.chaeyeongmin.van_sim.transaction.api.tcp.support;

import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.regex.Pattern;

/**
 * Payment Server가 발급하는 POS 거래번호 형식이다.
 * storeCd(4)-bizDate(8)-posNo(4)-seq(4) 구조를 검증한다.
 */
@Component
public class PosTrxProtocolValidator {

    private static final Pattern POS_TRX_PATTERN = Pattern.compile("^\\d{4}-\\d{8}-\\d{4}-\\d{4}$");
    private static final DateTimeFormatter BIZ_DATE_FORMATTER =
            DateTimeFormatter.ofPattern("uuuuMMdd").withResolverStyle(ResolverStyle.STRICT);

    /**
     * POS 거래번호의 형식과 bizDate 유효성을 검증한다.
     */
    public boolean isInvalid(String posTrx) {
        if (posTrx == null || posTrx.isBlank() || POS_TRX_PATTERN.matcher(posTrx).matches() == false) {
            return true;
        }

        String bizDate = posTrx.substring(5, 13);

        try {
            LocalDate.parse(bizDate, BIZ_DATE_FORMATTER);

            return false;
        } catch (DateTimeParseException e) {
            return true;
        }
    }
}
