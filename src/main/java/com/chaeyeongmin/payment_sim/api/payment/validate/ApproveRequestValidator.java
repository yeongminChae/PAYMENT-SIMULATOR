package com.chaeyeongmin.payment_sim.api.payment.validate;

import com.chaeyeongmin.payment_sim.api.payment.dto.ApproveRequest;
import com.chaeyeongmin.payment_sim.api.payment.dto.card.CardInput;
import org.springframework.stereotype.Component;

import java.time.LocalDate;

@Component
public class ApproveRequestValidator {

    public void validate(ApproveRequest request) {

        // 승인 요청 검증의 단일 진입점.
        // - 실패 시: ApproveValidationError.code()를 메시지로 예외를 던져 상위(서비스/컨트롤러)에서 공통 처리하게 한다.
        // - 성공 시: 아무것도 하지 않고 통과한다(예외 없음).
        ApproveValidationError error = validateAndGetError(request);

        if (error != null) {
            // TODO: 추후 BusinessException + result_code 표준으로 교체 예정
            throw new IllegalArgumentException(error.code());
        }
    }

    /**
     * 승인 요청에서 "가장 기본적인 유효성"을 검사하고,
     * 실패 원인을 ApproveValidationError로 반환한다.
     *
     * 반환 규칙:
     * - null  : OK (검증 통과)
     * - error : 해당 error.code()를 예외 메시지로 사용할 수 있음
     *
     * 주의:
     * - 여기서는 "첫 번째로 발견된 오류"만 반환한다. (에러 누적/리스트 반환 안 함)
     */
    private ApproveValidationError validateAndGetError(ApproveRequest request) {

        // request 자체가 없으면 이후 로직을 진행할 수 없음
        if (request == null) return ApproveValidationError.INVALID_REQUEST;

        // 거래번호(포스TR) 필수/공백 방지
        if (isNotNullOrBlank(request.getPosTrx()) == false)
            return ApproveValidationError.INVALID_POS_TRX;

        // 승인 금액은 0 초과만 허용
        if (request.getAmount() <= 0)
            return ApproveValidationError.INVALID_AMOUNT;

        // 카드 입력은 별도 함수에서 상세 검증(형식/만료/Luhn)
        if (isValidCard(request.getCard()) == false)
            return ApproveValidationError.INVALID_CARD;

        return null; // OK
    }

    /**
     * 문자열이 null이 아니고, 공백만으로 이루어지지 않았는지 검사한다.
     * (검증 로직에서 반복되는 null/blank 체크를 단순화하기 위한 유틸)
     */
    private boolean isNotNullOrBlank(String str) {
        return str != null && str.isBlank() == false;
    }

    /**
     * 카드 입력(CardInput)의 유효성 검사.
     *
     * 검증 범위:
     * - pan/expiry null 여부
     * - 길이/숫자 형식
     * - expiry 월 범위(01~12) 및 "현재 기준" 만료 여부
     * - pan Luhn checksum 통과 여부
     *
     * 보안 주의:
     * - 이 함수 안/밖에서 PAN 원문을 로그로 남기지 않는다.
     */
    private boolean isValidCard(CardInput card) {
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
        return isNotExpired(yy + 2000, mm) && luhnAlgorithm(pan);
    }

    /**
     * 문자열이 숫자로만 구성되어 있는지 검사한다.
     * (정규식 대신 문자 단위 검사로 단순/명확하게 처리)
     */
    private boolean isNumeric(String str) {
        if (str == null || str.isEmpty()) return false;

        for (char c : str.toCharArray()) {
            if (Character.isDigit(c) == false) return false;
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