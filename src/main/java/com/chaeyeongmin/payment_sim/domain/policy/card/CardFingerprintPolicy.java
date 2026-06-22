package com.chaeyeongmin.payment_sim.domain.policy.card;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

/**
 * 카드번호 원문 대신 저장하고 비교할 HMAC-SHA256 fingerprint를 생성한다.
 * <p>
 * 일반 해시가 아니라 서버 비밀키를 사용하는 HMAC을 적용해, 카드번호 후보를 대입하여
 * fingerprint를 미리 계산하는 공격을 어렵게 한다. 같은 카드를 계속 식별하려면 저장과
 * 비교 시점에 동일한 비밀키를 사용해야 한다.
 */
@Component
public class CardFingerprintPolicy {

    private static final String HMAC_ALGORITHM = "HmacSHA256";
    private final String secretKey;

    public CardFingerprintPolicy(
            @Value("${payment.card.secret-key}") String secretKey
    ) {
        if (secretKey == null || secretKey.isBlank())
            throw new IllegalArgumentException("fingerprint secret must not be blank");

        this.secretKey = secretKey;
    }

    /**
     * 카드번호와 서버 비밀키로 결정적인 fingerprint를 생성한다.
     * 동일한 키와 카드번호 조합은 항상 동일한 값을 반환하므로 PAN 원문 없이 카드 일치 여부를
     * 확인할 수 있다.
     */
    public String generate(String cardNo) {
        if (cardNo == null || cardNo.isBlank())
            throw new IllegalArgumentException("cardNo must not be blank");

        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            byte[] keyBytes = secretKey.getBytes(StandardCharsets.UTF_8);
            SecretKeySpec keySpec = new SecretKeySpec(keyBytes, HMAC_ALGORITHM);

            mac.init(keySpec);

            byte[] messageBytes = cardNo.getBytes(StandardCharsets.UTF_8);
            byte[] hmacBytes = mac.doFinal(messageBytes);

            return HexFormat.of().formatHex(hmacBytes);

        } catch (Exception e) {
            throw new IllegalStateException("Failed to generate card fingerprint", e);
        }

    }

    /**
     * 요청 카드번호에서 fingerprint를 다시 생성해 저장된 값과 비교한다.
     */
    boolean matches(String cardNo, String storedFingerprint) {
        if (storedFingerprint == null || storedFingerprint.isBlank())
            return false;

        String generatedFingerprint = generate(cardNo);
        byte[] generatedBytes = generatedFingerprint.getBytes(StandardCharsets.UTF_8);
        byte[] storedBytes = storedFingerprint.getBytes(StandardCharsets.UTF_8);

        // 단순 문자열 비교보다 비교 시간 차이를 줄여 fingerprint 추측에 이용될 정보를 최소화한다.
        return MessageDigest.isEqual(generatedBytes, storedBytes);
    }

}
