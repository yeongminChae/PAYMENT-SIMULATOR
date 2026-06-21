package com.chaeyeongmin.payment_sim.common.policy;

import com.chaeyeongmin.payment_sim.api.payment.dto.card.CardInput;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.LocalDate;

@Component
@RequiredArgsConstructor
public class CardValidationPolicy {

    /**
     * 카드 입력(CardInput)의 유효성 검사.
     * <p>
     * 검증 범위:
     * - pan/expiry null 여부
     * - 길이/숫자 형식
     * - expiry 월 범위(01~12) 및 "현재 기준" 만료 여부
     * - pan Luhn checksum 통과 여부
     * <p>
     * 보안 주의:
     * - 이 함수 안/밖에서 PAN 원문을 로그로 남기지 않는다.
     */
    public boolean isValidCard(CardInput card) {
        if (card == null) return false;

        String pan = card.getPan();
        String expiryYyMm = card.getExpiryYyMm();

        // 필수값 및 기본 형식(길이/숫자) 검사
        if (pan == null || expiryYyMm == null) return false;
        if (expiryYyMm.length() != 4 || pan.length() != 16) return false;
        if (isNumeric(expiryYyMm) == false || isNumeric(pan) == false) return false;

        // expiry 파싱 및 월 범위 체크 (YYMM)
        int yy = Integer.parseInt(expiryYyMm.substring(0, 2));
        int mm = Integer.parseInt(expiryYyMm.substring(2, 4));
        if (mm < 1 || mm > 12) return false;

        // 만료 + Luhn 둘 다 통과해야 유효
        return isNotExpired(yy + 2000, mm) && isValidCardNo(pan);
    }

    /**
     * 카드번호(PAN) 유효성 검사.
     * <p>
     * 검증 범위:
     * - null 여부
     * - 길이(16자리)
     * - 숫자 형식
     * - Luhn checksum 통과 여부
     */
    public boolean isValidCardNo(String cardNo) {
        if (cardNo == null) return false;
        if (cardNo.length() != 16) return false;
        if (isNumeric(cardNo) == false) return false;

        return luhnAlgorithm(cardNo);
    }

    /**
     * 문자열이 숫자로만 구성되어 있는지 검사한다.
     * (정규식 대신 문자 단위 검사로 단순/명확하게 처리)
     */
    private boolean isNumeric(String str) {
        if (str == null || str.isEmpty()) return false;

        for (char c : str.toCharArray()) {
            if (c < '0' || c > '9') return false;
        }
        return true;
    }

    /**
     * 카드 유효기간이 "현재 기준" 만료되지 않았는지 확인한다.
     * - 입력 연도가 올해면: 입력 월 >= 현재 월
     * - 입력 연도가 올해보다 크면: 유효
     * - 입력 연도가 올해보다 작으면: 만료
     */
    private boolean isNotExpired(int year, int month) {
        LocalDate now = LocalDate.now();
        int thisYear = now.getYear();
        int thisMonth = now.getMonthValue();

        if (year == thisYear) return month >= thisMonth;
        return year > thisYear;
    }

    /**
     * Luhn checksum 알고리즘.
     * - 카드번호(PAN)의 체크섬 검증에 사용한다.
     * - sum % 10 == 0 이면 유효한 번호로 판단한다.
     */
    private boolean luhnAlgorithm(String pan) {
        int sum = 0, i = 0, len = pan.length();

        while (len > i) {
            int targetNum = (pan.charAt(len - 1 - i) - '0') * (i % 2 == 1 ? 2 : 1);
            sum += (targetNum - (i % 2 == 1 && targetNum >= 10 ? 9 : 0));
            i += 1;
        }

        return sum % 10 == 0;
    }

}
