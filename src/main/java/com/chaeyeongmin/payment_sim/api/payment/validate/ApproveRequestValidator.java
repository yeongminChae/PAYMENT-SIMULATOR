package com.chaeyeongmin.payment_sim.api.payment.validate;

import com.chaeyeongmin.payment_sim.api.payment.dto.ApproveRequest;
import com.chaeyeongmin.payment_sim.api.payment.dto.card.CardInput;
import org.springframework.stereotype.Component;

import java.time.LocalDate;

@Component
public class ApproveRequestValidator {

    public void validate(ApproveRequest request) {
        ApproveValidationError error = validateAndGetError(request);

        if (error != null) {
            // 나중에 BusinessException(error.code()) 같은 형태로 교체하기 좋음
            throw new IllegalArgumentException(error.code());
        }

    }

    private ApproveValidationError validateAndGetError(ApproveRequest request) {

        if (request == null) return ApproveValidationError.INVALID_REQUEST;

        if (isNotNullOrBlank(request.getPosTrx()) == false)
            return ApproveValidationError.INVALID_POS_TRX;

        if (request.getAmount() <= 0)
            return ApproveValidationError.INVALID_AMOUNT;

        if (isValidCard(request.getCard()) == false)
            return ApproveValidationError.INVALID_CARD;

        return null; // OK
    }

    private boolean isNotNullOrBlank(String str) {
        return str != null && str.isBlank() == false;
    }

    private boolean isValidCard(CardInput card) {
        if (card == null) return false;

        String pan = card.getPan();
        String expiryYyMm = card.getExpiryYyMm();

        if (pan == null || expiryYyMm == null) return false;
        if (expiryYyMm.length() != 4 || pan.length() != 16) return false;
        if (isNumeric(expiryYyMm) == false || isNumeric(pan) == false) return false;

        int yy = Integer.parseInt(expiryYyMm.substring(0, 2));
        int mm = Integer.parseInt(expiryYyMm.substring(2, 4));
        if (mm < 1 || mm > 12) return false;

        return isNotExpired(yy + 2000, mm) && luhnAlgorithm(pan);
    }

    private boolean isNumeric(String str) {
        if (str == null || str.isEmpty()) return false;

        for (char c : str.toCharArray()) {
            if (Character.isDigit(c) == false) return false;
        }
        return true;
    }

    private boolean isNotExpired(int year, int month) {
        LocalDate now = LocalDate.now();
        int thisYear = now.getYear();
        int thisMonth = now.getMonthValue();

        if (year == thisYear) return month >= thisMonth;
        return year > thisYear;
    }

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