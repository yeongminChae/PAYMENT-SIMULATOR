package com.chaeyeongmin.payment_sim.api.payment.integration;

import com.chaeyeongmin.payment_sim.domain.policy.card.CardFingerprintPolicy;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Map;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * MVP2 결제 흐름 통합 테스트.
 *
 * <p>목적:
 * - Controller -> Service -> VAN 시뮬레이터 -> Repository -> MyBatis -> SQLite까지 실제 애플리케이션 흐름을 태운다.
 * - 1차 MVP에서 놓치기 쉬웠던 "HTTP 호출 성공"과 "업무 결과 성공"의 구분을 검증한다.
 * - 승인/조회/취소/BIN 저장 정책이 API 응답과 DB row 기준으로 일관되는지 확인한다.
 *
 * <p>테스트 DB:
 * - 기존 PaymentFlowIntegrationTest와 분리된 SQLite 파일을 사용한다.
 * - 각 테스트 전후로 이 클래스가 사용하는 posTrx만 삭제해 반복 실행과 실행 순서 변경 영향을 줄인다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = "spring.datasource.url=jdbc:sqlite:./build/mvp2-payment-flow-integration-test.db")
class Mvp2PaymentFlowIntegrationTest {

    private static final String APPROVE_POS_TRX_IT_2_API_DECLINED = "2376-20260601-9991-1101";
    private static final String APPROVE_POS_TRX_IT_2_API_UNKNOWN = "2376-20260601-9991-1102";
    private static final String APPROVE_POS_TRX_IT_2_INQ_UNKNOWN_TO_APPROVED = "2376-20260601-9991-1103";
    private static final String APPROVE_POS_TRX_IT_2_CANCEL_SUCCESS = "2376-20260601-9991-1104";
    private static final String APPROVE_POS_TRX_IT_2_CANCEL_DUPLICATE = "2376-20260601-9991-1105";
    private static final String APPROVE_POS_TRX_IT_2_CANCEL_CONFLICT = "2376-20260601-9991-1106";
    private static final String APPROVE_POS_TRX_IT_2_BIN = "2376-20260601-9991-1107";
    private static final String APPROVE_POS_TRX_IT_2_CANCEL_CARD_MISMATCH = "2376-20260601-9991-1108";

    private static final String CANCEL_POS_TRX_IT_2_CANCEL_SUCCESS = "2376-20260601-9991-2101";
    private static final String FIRST_CANCEL_POS_TRX_IT_2_CANCEL_DUPLICATE = "2376-20260601-9991-2102";
    private static final String SECOND_CANCEL_POS_TRX_IT_2_CANCEL_DUPLICATE = "2376-20260601-9991-2103";
    private static final String EXISTING_CANCEL_POS_TRX_IT_2_CANCEL_CONFLICT = "2376-20260601-9991-2104";
    private static final String RETRY_CANCEL_POS_TRX_IT_2_CANCEL_CONFLICT = "2376-20260601-9991-2105";
    private static final String CANCEL_POS_TRX_IT_2_CANCEL_CARD_MISMATCH = "2376-20260601-9991-2106";

    private static final List<String> APPROVE_POS_TRXS = List.of(
            APPROVE_POS_TRX_IT_2_API_DECLINED,
            APPROVE_POS_TRX_IT_2_API_UNKNOWN,
            APPROVE_POS_TRX_IT_2_INQ_UNKNOWN_TO_APPROVED,
            APPROVE_POS_TRX_IT_2_CANCEL_SUCCESS,
            APPROVE_POS_TRX_IT_2_CANCEL_DUPLICATE,
            APPROVE_POS_TRX_IT_2_CANCEL_CONFLICT,
            APPROVE_POS_TRX_IT_2_BIN,
            APPROVE_POS_TRX_IT_2_CANCEL_CARD_MISMATCH
    );

    private static final List<String> CANCEL_POS_TRXS = List.of(
            CANCEL_POS_TRX_IT_2_CANCEL_SUCCESS,
            FIRST_CANCEL_POS_TRX_IT_2_CANCEL_DUPLICATE,
            SECOND_CANCEL_POS_TRX_IT_2_CANCEL_DUPLICATE,
            EXISTING_CANCEL_POS_TRX_IT_2_CANCEL_CONFLICT,
            RETRY_CANCEL_POS_TRX_IT_2_CANCEL_CONFLICT,
            CANCEL_POS_TRX_IT_2_CANCEL_CARD_MISMATCH
    );

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private CardFingerprintPolicy cardFingerprintPolicy;

    @BeforeEach
    void cleanBefore() {
        cleanupTestData();
    }

    @AfterEach
    void cleanAfter() {
        cleanupTestData();
    }

    @Test
    @DisplayName("IT-2-API-001 Approve 거절/미확정 응답의 result_code 계약 검증")
    void approveDeclinedAndUnknownTimeout_shouldExposeBusinessResultCode() throws Exception {
        // Given:
        // - 4111111111111111은 VAN 시뮬레이터 규칙상 DECLINED가 된다.
        // - 4111111100087777은 VAN 시뮬레이터 규칙상 UNKNOWN_TIMEOUT이 된다.
        //
        // When:
        // - Approve API를 각각 호출한다.
        //
        // Then:
        // - HTTP 200으로 감싸졌더라도 최상위 result_code는 업무 결과를 따라야 한다.
        // - data.finalStatus와 PAYMENT_ATTEMPT.FINAL_STATUS도 같은 업무 상태여야 한다.
        JsonNode declinedResponse = approveDeclined(APPROVE_POS_TRX_IT_2_API_DECLINED);
        JsonNode unknownResponse = approveUnknownTimeout(APPROVE_POS_TRX_IT_2_API_UNKNOWN);

        assertApproveBusinessResult(declinedResponse, "DECLINED");
        assertApproveBusinessResult(unknownResponse, "UNKNOWN_TIMEOUT");

        assertEquals(
                "DECLINED",
                findPaymentAttempt(APPROVE_POS_TRX_IT_2_API_DECLINED, attemptSeq(declinedResponse)).get("FINAL_STATUS")
        );
        assertEquals(
                "UNKNOWN_TIMEOUT",
                findPaymentAttempt(APPROVE_POS_TRX_IT_2_API_UNKNOWN, attemptSeq(unknownResponse)).get("FINAL_STATUS")
        );
    }

    @Test
    @DisplayName("IT-2-INQ-001 UNKNOWN_TIMEOUT 승인건 Inquiry 재조회")
    void inquiryUnknownTimeoutPayment_whenVanFinalizes_shouldPersistFinalStatusAndReturnDbStatus() throws Exception {
        // Given:
        // - 기존 Approve가 UNKNOWN_TIMEOUT으로 끝난 것처럼 PAYMENT_ATTEMPT row를 직접 준비한다.
        // - cardLast4=4242는 Inquiry VAN 재조회 시 APPROVED로 확정되는 시뮬레이터 규칙이다.
        //
        // When:
        // - Inquiry API가 UNKNOWN_TIMEOUT row를 조회한다.
        //
        // Then:
        // - VAN Inquiry 재조회 결과를 DB에 확정 반영한다.
        // - API 응답은 VAN 원본이 아니라 최종 DB row 기준 상태와 승인번호를 반환해야 한다.
        int attemptSeq = 1;
        insertPaymentAttempt(
                APPROVE_POS_TRX_IT_2_INQ_UNKNOWN_TO_APPROVED,
                attemptSeq,
                "42424242",
                "4242",
                "4242424242424242",
                "UNKNOWN_TIMEOUT",
                null,
                "TIMEOUT",
                APPROVE_POS_TRX_IT_2_INQ_UNKNOWN_TO_APPROVED + "-01"
        );

        JsonNode inquiryResponse = inquiry(APPROVE_POS_TRX_IT_2_INQ_UNKNOWN_TO_APPROVED, attemptSeq);

        assertEquals("OK", inquiryResponse.path("result_code").asText());
        assertEquals("APPROVED", inquiryResponse.path("data").path("finalStatus").asText());
        assertNotNull(textOrNull(inquiryResponse.path("data"), "approvalNo"));

        Map<String, Object> attemptRow =
                findPaymentAttempt(APPROVE_POS_TRX_IT_2_INQ_UNKNOWN_TO_APPROVED, attemptSeq);
        assertEquals("APPROVED", attemptRow.get("FINAL_STATUS"));
        assertEquals(textOrNull(inquiryResponse.path("data"), "approvalNo"), attemptRow.get("APPROVAL_NO"));
        assertEquals(1, countPaymentAttempt(APPROVE_POS_TRX_IT_2_INQ_UNKNOWN_TO_APPROVED, attemptSeq));
    }

    @Test
    @DisplayName("IT-2-CANCEL-001 APPROVED 원거래 Cancel 성공")
    void cancelApprovedPayment_shouldPersistCancelledRowAndExposeCancelStatusResult() throws Exception {
        // Given:
        // - Approve API로 APPROVED 상태의 원거래를 만든다.
        // - 해당 원거래에 대한 PAYMENT_CANCEL row는 아직 없다.
        //
        // When:
        // - Cancel API를 호출한다.
        //
        // Then:
        // - PAYMENT_CANCEL row가 생성되고 VAN cancel 결과가 CANCELLED로 저장된다.
        // - 최상위 result_code=OK와 data.cancelStatus=CANCELLED가 서로 맞아야 한다.
        JsonNode approveResponse = approve(APPROVE_POS_TRX_IT_2_CANCEL_SUCCESS);
        int attemptSeq = attemptSeq(approveResponse);

        JsonNode cancelResponse = cancel(
                CANCEL_POS_TRX_IT_2_CANCEL_SUCCESS,
                APPROVE_POS_TRX_IT_2_CANCEL_SUCCESS,
                attemptSeq,
                "4242424242424242"
        );

        assertEquals("OK", cancelResponse.path("result_code").asText());
        assertEquals("CANCELLED", cancelResponse.path("data").path("cancelStatus").asText());
        assertNotNull(textOrNull(cancelResponse.path("data"), "cancelApprovalNo"));

        Map<String, Object> cancelRow =
                findPaymentCancel(APPROVE_POS_TRX_IT_2_CANCEL_SUCCESS, attemptSeq);
        assertEquals("CANCELLED", cancelRow.get("CANCEL_STATUS"));
        assertEquals(CANCEL_POS_TRX_IT_2_CANCEL_SUCCESS, cancelRow.get("CURRENT_TRX_NO"));
        assertNotNull(cancelRow.get("CANCEL_APPROVAL_NO"));
    }

    @Test
    @DisplayName("IT-2-CANCEL-002 동일 원거래 재취소 시 기존 cancel row 재응답")
    void cancelSameOriginalPaymentTwice_shouldReuseExistingCancelRow() throws Exception {
        // Given:
        // - APPROVED 원거래를 만들고 첫 번째 Cancel 요청으로 취소를 완료한다.
        //
        // When:
        // - 같은 originalPosTrx + originalAttemptSeq로 두 번째 Cancel 요청을 보낸다.
        //
        // Then:
        // - 신규 PAYMENT_CANCEL row를 만들지 않는다.
        // - 두 번째 요청은 VAN cancel을 새로 수행하지 않고 기존 cancel row 기준으로 ALREADY_CANCELLED를 응답한다.
        // - 첫 취소 승인번호가 재응답되어 중복 요청도 같은 취소 결과를 확인할 수 있어야 한다.
        JsonNode approveResponse = approve(APPROVE_POS_TRX_IT_2_CANCEL_DUPLICATE);
        int attemptSeq = attemptSeq(approveResponse);

        JsonNode firstCancelResponse = cancel(
                FIRST_CANCEL_POS_TRX_IT_2_CANCEL_DUPLICATE,
                APPROVE_POS_TRX_IT_2_CANCEL_DUPLICATE,
                attemptSeq,
                "4242424242424242"
        );
        JsonNode secondCancelResponse = cancel(
                SECOND_CANCEL_POS_TRX_IT_2_CANCEL_DUPLICATE,
                APPROVE_POS_TRX_IT_2_CANCEL_DUPLICATE,
                attemptSeq,
                "4242424242424242"
        );

        assertEquals("OK", firstCancelResponse.path("result_code").asText());
        assertEquals("ALREADY_CANCELLED", secondCancelResponse.path("result_code").asText());
        assertEquals("CANCELLED", firstCancelResponse.path("data").path("cancelStatus").asText());
        assertEquals("ALREADY_CANCELLED", secondCancelResponse.path("data").path("cancelStatus").asText());
        assertEquals(
                textOrNull(firstCancelResponse.path("data"), "cancelApprovalNo"),
                textOrNull(secondCancelResponse.path("data"), "cancelApprovalNo")
        );

        assertEquals(1, countPaymentCancelByOriginal(APPROVE_POS_TRX_IT_2_CANCEL_DUPLICATE, attemptSeq));
        assertEquals(
                FIRST_CANCEL_POS_TRX_IT_2_CANCEL_DUPLICATE,
                findPaymentCancel(APPROVE_POS_TRX_IT_2_CANCEL_DUPLICATE, attemptSeq).get("CURRENT_TRX_NO")
        );
    }

    @Test
    @DisplayName("IT-2-CANCEL-003 Cancel unique 충돌/업데이트 empty 복구 대표 흐름")
    void cancelWhenExistingPendingRowRepresentsConflict_shouldRereadExistingCancelRow() throws Exception {
        // Given:
        // - APPROVED 원거래가 이미 있다.
        // - 다른 요청이 먼저 PAYMENT_CANCEL PENDING row를 만든 경합 상황을 DB row로 직접 구성한다.
        //
        // When:
        // - 동일 원거래에 대해 Cancel API가 다시 들어온다.
        //
        // Then:
        // - 시스템 오류로 실패하지 않고 original 기준 기존 cancel row를 재조회한다.
        // - 기존 row가 PENDING이므로 RETRY_LATER로 응답한다.
        // - 새 cancel row가 추가되지 않아 원거래 기준 row 수는 1개로 유지되어야 한다.
        //
        // 비고:
        // - 이 통합 테스트는 C5 unique 충돌/경합 복구의 대표 관측값을 검증한다.
        // - C7 update empty의 세부 분기는 서비스 단위 테스트에서 더 직접적으로 다룬다.
        int attemptSeq = 1;
        insertPaymentAttempt(
                APPROVE_POS_TRX_IT_2_CANCEL_CONFLICT,
                attemptSeq,
                "42424242",
                "4242",
                "4242424242424242",
                "APPROVED",
                "A000000001",
                null,
                APPROVE_POS_TRX_IT_2_CANCEL_CONFLICT + "-01"
        );
        insertPendingCancel(
                EXISTING_CANCEL_POS_TRX_IT_2_CANCEL_CONFLICT,
                APPROVE_POS_TRX_IT_2_CANCEL_CONFLICT,
                attemptSeq
        );

        JsonNode cancelResponse = cancel(
                RETRY_CANCEL_POS_TRX_IT_2_CANCEL_CONFLICT,
                APPROVE_POS_TRX_IT_2_CANCEL_CONFLICT,
                attemptSeq,
                "4242424242424242"
        );

        assertEquals("RETRY_LATER", cancelResponse.path("result_code").asText());
        assertEquals("RETRY_LATER", cancelResponse.path("data").path("cancelStatus").asText());
        assertEquals(1, countPaymentCancelByOriginal(APPROVE_POS_TRX_IT_2_CANCEL_CONFLICT, attemptSeq));
        assertEquals(
                EXISTING_CANCEL_POS_TRX_IT_2_CANCEL_CONFLICT,
                findPaymentCancel(APPROVE_POS_TRX_IT_2_CANCEL_CONFLICT, attemptSeq).get("CURRENT_TRX_NO")
        );
    }

    @Test
    @DisplayName("IT-2.1-CANCEL-CARD-002 취소 카드 fingerprint 불일치 시 CANCEL_NOT_ALLOWED")
    void cancelCardFingerprintMismatch_shouldReturnCancelNotAllowedWithoutPersistingCancelRow() throws Exception {
        // Given:
        // - Approve API에 4242424242424242 카드를 전달해 APPROVED 원거래를 만든다.
        // - 원승인 attempt에는 승인 요청 카드의 fingerprint가 저장된다.
        // - 해당 원거래를 대상으로 아직 생성된 PAYMENT_CANCEL row는 없다.
        JsonNode approveResponse = approve(APPROVE_POS_TRX_IT_2_CANCEL_CARD_MISMATCH);
        int attemptSeq = attemptSeq(approveResponse);

        assertEquals("OK", approveResponse.path("result_code").asText());
        assertEquals("APPROVED", approveResponse.path("data").path("finalStatus").asText());

        // When:
        // - 원승인 카드와 다른 유효 카드번호 4111111111111111로 Cancel API를 호출한다.
        JsonNode cancelResponse = cancel(
                CANCEL_POS_TRX_IT_2_CANCEL_CARD_MISMATCH,
                APPROVE_POS_TRX_IT_2_CANCEL_CARD_MISMATCH,
                attemptSeq,
                "4111111111111111"
        );

        // Then:
        // - 카드 불일치 요청은 CANCEL_NOT_ALLOWED/CARD_MISMATCH로 종료된다.
        // - C5 PENDING insert 전에 차단되므로 PAYMENT_CANCEL row가 생성되지 않는다.
        assertEquals("CANCEL_NOT_ALLOWED", cancelResponse.path("result_code").asText());
        assertEquals("CARD_MISMATCH", cancelResponse.path("message").asText());
        assertTrue(cancelResponse.path("data").isNull());
        assertEquals(
                0,
                countPaymentCancelByOriginal(APPROVE_POS_TRX_IT_2_CANCEL_CARD_MISMATCH, attemptSeq)
        );
    }

    @Test
    @DisplayName("IT-2-BIN-001 active BIN 승인 요청 시 PAYMENT_EXTERNAL_INFO 저장")
    void approveWithActiveBin_shouldPersistPaymentExternalInfoSnapshot() throws Exception {
        // Given:
        // - seed data에 active BIN 41111111 -> VISA / KB_CARD_TEST / KR 이 등록되어 있다.
        //
        // When:
        // - 해당 BIN을 가진 cardNo로 Approve API를 호출한다.
        //
        // Then:
        // - PAYMENT_EXTERNAL_INFO에 카드 식별 스냅샷이 저장된다.
        // - PAN 원문은 저장하지 않고 BIN 8자리, last4, masked card no만 남긴다.
        // - VAN provider는 내부 추적용 값 MOCK_VAN으로 저장된다.
        JsonNode approveResponse = approveDeclined(APPROVE_POS_TRX_IT_2_BIN);
        int attemptSeq = attemptSeq(approveResponse);

        Map<String, Object> externalInfoRow =
                findPaymentExternalInfo(APPROVE_POS_TRX_IT_2_BIN, attemptSeq);

        assertEquals("41111111", externalInfoRow.get("CARD_BIN"));
        assertEquals("1111", externalInfoRow.get("CARD_LAST4"));
        assertEquals("41111111******1111", externalInfoRow.get("MASKED_CARD_NO"));
        assertEquals("VISA", externalInfoRow.get("CARD_BRAND"));
        assertEquals("KB_CARD_TEST", externalInfoRow.get("CARD_ISSUER"));
        assertEquals("KR", externalInfoRow.get("CARD_COUNTRY"));
        assertEquals("MOCK_VAN", externalInfoRow.get("VAN_PROVIDER"));
        assertExternalInfoDoesNotContainPan(externalInfoRow, "4111111111111111");
    }

    @Test
    @DisplayName("IT-2-BIN-002 PAYMENT_ATTEMPT와 PAYMENT_EXTERNAL_INFO의 CARD_BIN 기준 통일")
    void approveWithActiveBin_shouldPersistSameEightDigitCardBinToAttemptAndExternalInfo() throws Exception {
        // Given:
        // - Approve 요청의 cardNo 앞 8자리가 41111111이다.
        //
        // When:
        // - 승인 attempt와 external info가 저장된다.
        //
        // Then:
        // - PAYMENT_ATTEMPT.CARD_BIN과 PAYMENT_EXTERNAL_INFO.CARD_BIN 모두 같은 8자리 BIN을 사용한다.
        // - 6자리/8자리 기준이 섞이는 회귀를 DB 저장 결과로 잡는다.
        JsonNode approveResponse = approveDeclined(APPROVE_POS_TRX_IT_2_BIN);
        int attemptSeq = attemptSeq(approveResponse);

        Map<String, Object> attemptRow = findPaymentAttempt(APPROVE_POS_TRX_IT_2_BIN, attemptSeq);
        Map<String, Object> externalInfoRow = findPaymentExternalInfo(APPROVE_POS_TRX_IT_2_BIN, attemptSeq);

        assertEquals("41111111", attemptRow.get("CARD_BIN"));
        assertEquals(attemptRow.get("CARD_BIN"), externalInfoRow.get("CARD_BIN"));
    }

    @Test
    @DisplayName("IT-2-BIN-003 API 응답에 BIN_CATALOG 식별값 미반영")
    void approveWithActiveBin_shouldNotExposeBinCatalogIdentifiersInApiResponse() throws Exception {
        // Given:
        // - BIN_CATALOG로 VISA / KB_CARD_TEST / KR / MOCK_VAN을 내부 식별할 수 있다.
        //
        // When:
        // - Approve API 응답을 확인한다.
        //
        // Then:
        // - 식별값은 내부 DB 스냅샷에만 남고 외부 API 응답에는 노출되지 않는다.
        // - 기존 DTO 구조상 cardBrand 필드는 null일 수 있지만, BIN_CATALOG의 VISA 값이 세팅되면 안 된다.
        JsonNode approveResponse = approveDeclined(APPROVE_POS_TRX_IT_2_BIN);
        JsonNode cardSummary = approveResponse.path("data").path("cardSummary");

        assertNotEquals("VISA", textOrNull(cardSummary, "cardBrand"));
        assertTrue(cardSummary.path("issuer").isMissingNode());
        assertTrue(cardSummary.path("country").isMissingNode());
        assertTrue(cardSummary.path("vanProvider").isMissingNode());

        String responseBody = approveResponse.toString();
        assertFalse(responseBody.contains("VISA"));
        assertFalse(responseBody.contains("KB_CARD_TEST"));
        assertFalse(responseBody.contains("KR"));
        assertFalse(responseBody.contains("MOCK_VAN"));
    }

    private JsonNode approve(String posTrx) throws Exception {
        return postJson(
                "/api/v1/payments/approve",
                """
                {
                  "posTrx": "%s",
                  "amount": 10000,
                  "card": {
                    "pan": "4242424242424242",
                    "expiryYyMm": "2812"
                  }
                }
                """.formatted(posTrx)
        );
    }

    private JsonNode approveDeclined(String posTrx) throws Exception {
        return postJson(
                "/api/v1/payments/approve",
                """
                {
                  "posTrx": "%s",
                  "amount": 10000,
                  "card": {
                    "pan": "4111111111111111",
                    "expiryYyMm": "2812"
                  }
                }
                """.formatted(posTrx)
        );
    }

    private JsonNode approveUnknownTimeout(String posTrx) throws Exception {
        return postJson(
                "/api/v1/payments/approve",
                """
                {
                  "posTrx": "%s",
                  "amount": 10000,
                  "card": {
                    "pan": "4111111100087777",
                    "expiryYyMm": "2812"
                  }
                }
                """.formatted(posTrx)
        );
    }

    private JsonNode inquiry(String posTrx, int attemptSeq) throws Exception {
        return postJson(
                "/api/v1/payments/inquiry",
                """
                {
                  "posTrx": "%s",
                  "attemptSeq": %d
                }
                """.formatted(posTrx, attemptSeq)
        );
    }

    private JsonNode cancel(
            String posTrx,
            String originalPosTrx,
            int originalAttemptSeq,
            String cardNo
    ) throws Exception {
        return postJson(
                "/api/v1/payments/cancel",
                """
                {
                  "posTrx": "%s",
                  "originalPosTrx": "%s",
                  "originalAttemptSeq": %d,
                  "cardNo": "%s"
                }
                """.formatted(posTrx, originalPosTrx, originalAttemptSeq, cardNo)
        );
    }

    private JsonNode postJson(String path, String requestBody) throws Exception {
        String responseBody = mockMvc.perform(post(path)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        return objectMapper.readTree(responseBody);
    }

    private void assertApproveBusinessResult(JsonNode response, String expectedResultCode) {
        assertEquals(expectedResultCode, response.path("result_code").asText());
        assertEquals(expectedResultCode, response.path("data").path("finalStatus").asText());
    }

    private int attemptSeq(JsonNode response) {
        return response.path("data").path("attemptSeq").asInt();
    }

    private int countPaymentAttempt(String posTrx, int attemptSeq) {
        return Objects.requireNonNull(jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*)
                FROM PAYMENT_ATTEMPT
                WHERE POS_TRX = ?
                  AND ATTEMPT_SEQ = ?
                """,
                Integer.class,
                posTrx,
                attemptSeq
        ));
    }

    private int countPaymentCancelByOriginal(String originalPosTrx, int originalAttemptSeq) {
        return Objects.requireNonNull(jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*)
                FROM PAYMENT_CANCEL
                WHERE ORIGINAL_TRX_NO = ?
                  AND ORIGINAL_ATTEMPT_SEQ = ?
                """,
                Integer.class,
                originalPosTrx,
                originalAttemptSeq
        ));
    }

    private Map<String, Object> findPaymentAttempt(String posTrx, int attemptSeq) {
        return jdbcTemplate.queryForMap(
                """
                SELECT
                    POS_TRX,
                    ATTEMPT_SEQ,
                    CARD_BIN,
                    CARD_LAST4,
                    FINAL_STATUS,
                    APPROVAL_NO,
                    DECLINE_CODE,
                    VAN_TRX_ID
                FROM PAYMENT_ATTEMPT
                WHERE POS_TRX = ?
                  AND ATTEMPT_SEQ = ?
                """,
                posTrx,
                attemptSeq
        );
    }

    private Map<String, Object> findPaymentExternalInfo(String posTrx, int attemptSeq) {
        return jdbcTemplate.queryForMap(
                """
                SELECT
                    POS_TRX,
                    ATTEMPT_SEQ,
                    CARD_BIN,
                    CARD_LAST4,
                    MASKED_CARD_NO,
                    CARD_BRAND,
                    CARD_ISSUER,
                    CARD_COUNTRY,
                    VAN_PROVIDER
                FROM PAYMENT_EXTERNAL_INFO
                WHERE POS_TRX = ?
                  AND ATTEMPT_SEQ = ?
                """,
                posTrx,
                attemptSeq
        );
    }

    private Map<String, Object> findPaymentCancel(String originalPosTrx, int originalAttemptSeq) {
        return jdbcTemplate.queryForMap(
                """
                SELECT
                    CURRENT_TRX_NO,
                    ORIGINAL_TRX_NO,
                    ORIGINAL_ATTEMPT_SEQ,
                    CANCEL_STATUS,
                    CANCEL_APPROVAL_NO,
                    DECLINE_CODE
                FROM PAYMENT_CANCEL
                WHERE ORIGINAL_TRX_NO = ?
                  AND ORIGINAL_ATTEMPT_SEQ = ?
                """,
                originalPosTrx,
                originalAttemptSeq
        );
    }

    private void insertPaymentAttempt(
            String posTrx,
            int attemptSeq,
            String cardBin,
            String cardLast4,
            String cardNo,
            String finalStatus,
            String approvalNo,
            String declineCode,
            String vanTrxId
    ) {
        jdbcTemplate.update(
                """
                INSERT INTO PAYMENT_ATTEMPT (
                    POS_TRX,
                    ATTEMPT_SEQ,
                    AMOUNT,
                    CARD_BIN,
                    CARD_LAST4,
                    CARD_FINGERPRINT,
                    FINAL_STATUS,
                    APPROVAL_NO,
                    DECLINE_CODE,
                    VAN_TRX_ID
                )
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                posTrx,
                attemptSeq,
                10000,
                cardBin,
                cardLast4,
                cardFingerprintPolicy.generate(cardNo),
                finalStatus,
                approvalNo,
                declineCode,
                vanTrxId
        );
    }

    private void insertPendingCancel(String currentPosTrx, String originalPosTrx, int originalAttemptSeq) {
        jdbcTemplate.update(
                """
                INSERT INTO PAYMENT_CANCEL (
                    CURRENT_TRX_NO,
                    ORIGINAL_TRX_NO,
                    ORIGINAL_ATTEMPT_SEQ,
                    CANCEL_STATUS
                )
                VALUES (?, ?, ?, ?)
                """,
                currentPosTrx,
                originalPosTrx,
                originalAttemptSeq,
                "PENDING"
        );
    }

    private void assertExternalInfoDoesNotContainPan(Map<String, Object> externalInfoRow, String pan) {
        externalInfoRow.values().stream()
                .map(value -> Objects.toString(value, ""))
                .forEach(value -> assertFalse(value.contains(pan)));
    }

    private String textOrNull(JsonNode node, String fieldName) {
        JsonNode value = node.path(fieldName);
        return value.isMissingNode() || value.isNull() ? null : value.asText();
    }

    private void cleanupTestData() {
        for (String cancelPosTrx : CANCEL_POS_TRXS) {
            jdbcTemplate.update("DELETE FROM PAYMENT_CANCEL WHERE CURRENT_TRX_NO = ?", cancelPosTrx);
        }

        for (String approvePosTrx : APPROVE_POS_TRXS) {
            jdbcTemplate.update("DELETE FROM PAYMENT_CANCEL WHERE ORIGINAL_TRX_NO = ?", approvePosTrx);
            jdbcTemplate.update("DELETE FROM PAYMENT_EXTERNAL_INFO WHERE POS_TRX = ?", approvePosTrx);
            jdbcTemplate.update("DELETE FROM PAYMENT_ATTEMPT WHERE POS_TRX = ?", approvePosTrx);
            jdbcTemplate.update("DELETE FROM PAYMENT_ATTEMPT_SEQ WHERE POS_TRX = ?", approvePosTrx);
        }
    }
}
