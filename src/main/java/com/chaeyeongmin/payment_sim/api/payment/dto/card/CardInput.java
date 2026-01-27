package com.chaeyeongmin.payment_sim.api.payment.dto.card;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDate;

/**
 * [DTO] 카드 입력(CardInput)
 * <p>
 * 목적:
 * - 승인 요청에서 카드 유효성 검증 및 VAN 시뮬레이터 룰 판단을 위해 사용
 * <p>
 * 필드:
 * - pan        : 13~19자리 숫자 + Luhn 체크 대상 (민감정보)
 * - expiryYyMm : YYMM (예: 2601)
 * <p>
 * 보안:
 * - pan은 절대 로그/DB에 원문 저장 금지
 */

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class CardInput {
    private String pan;
    private String expiryYyMm;

    // 입력 받아온 카드 값 유효성 검증 함수
    public boolean validate(String pan, String expiryYyMm) {

        // 길이/숫자 체크: 하나라도 틀리면 false
        if (expiryYyMm.length() != 4 || pan.length() != 16
                || isNumeric(expiryYyMm, pan) == false
        ) return false;

        int yy = Integer.parseInt(expiryYyMm.substring(0, 2));
        int mm = Integer.parseInt(expiryYyMm.substring(2, 4));
        if (mm < 1 || mm > 12) return false;

        // 만료면 false 리턴 -> INVALID
        return isNotExpired(yy + 2000, mm);
    }

    // 입력 값이 숫자 타입으로 변환 가능한지 검증
    private boolean isNumeric(String str) {
        if (str == null || str.isEmpty()) return false;

        for (char c : str.toCharArray()) {
            if (Character.isDigit(c) == false) return false;
        }
        return true;
    }

    private boolean isNumeric(String str1, String str2) {
        return isNumeric(str1) && isNumeric(str2);
    }

    // 카드 유효기간 만료 여부 확인
    private boolean isNotExpired(int year, int month) {
        LocalDate now = LocalDate.now();
        int thisYear = now.getYear();
        int thisMonth = now.getMonthValue();

        // 입력 값이 이번 달 보다 더 크거나 같아야 정상
        // 그러므로 입력 값이 이번 달 보다 작을 경우 비정상으로 리턴
        if (year == thisYear) return month >= thisMonth;

        // 입력 연도가 올해보다 클 경우 항상 정상, 입력 연도가 올해보다 작을 경우 항상 비정상
        return year > thisYear;
    }

    // Luhn checksum (PAN 유효성 검사)
    private boolean luhnAlgorithm(String pan) {
        // - 오른쪽 끝부터 i=0으로 순회한다. (pan.charAt(len - 1 - i))
        int sum = 0, i = 0, len = pan.length();
        while (len > i) {
            // - i가 홀수인 자리(오른쪽에서 2번째, 4번째, ...)는 2배한다.
            int targetNum = (pan.charAt(len - 1 - i) - '0') * (i % 2 == 1 ? 2 : 1);
            // - 2배 결과가 10 이상이면 9를 빼서 자릿수 합과 동일하게 보정한다.
            // (예: 12 -> 1+2=3 == 12-9)
            sum += (targetNum - (i % 2 == 1 && targetNum >= 10 ? 9 : 0));

            i += 1;
        }

        // - 모든 값을 더한 합이 10의 배수(sum % 10 == 0)면 유효한 번호로 판단한다.
        return sum % 10 == 0;
    }

}