package com.chaeyeongmin.payment_sim.api.payment.integration;

import com.chaeyeongmin.payment_sim.domain.model.PaymentCancel;
import com.chaeyeongmin.payment_sim.domain.policy.CancelStatus;
import com.chaeyeongmin.payment_sim.infra.repository.PaymentCancelRepository;
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
 * 이 클래스는 개별 메서드만 격리해서 확인하는 단위 테스트가 아니라 통합 테스트다.
 * 실제 Controller -> Service -> Repository -> MyBatis -> SQLite 흐름을 타게 해서
 * 계층 간 연결과 SQL 실행 결과까지 함께 검증한다.
 * 테스트 전용 SQLite 파일을 사용하고, 각 테스트가 사용하는 고유 거래번호를
 * FK 순서에 맞춰 정리해 반복 실행과 테스트 순서 변경에도 영향을 받지 않게 한다.
 */
// @SpringBootTest는 운영 코드와 같은 Spring ApplicationContext를 구성해 실제 Bean 연결을 검증한다.
@SpringBootTest
// @AutoConfigureMockMvc는 서버 포트를 직접 띄우지 않고도 Controller부터 HTTP 요청 흐름을 실행할 MockMvc를 준비한다.
@AutoConfigureMockMvc
// @TestPropertySource는 운영 DB 대신 테스트 전용 SQLite 파일을 사용하도록 datasource URL을 덮어쓴다.
@TestPropertySource(properties = "spring.datasource.url=jdbc:sqlite:./build/payment-flow-integration-test.db")
class PaymentFlowIntegrationTest {

    // 각 시나리오가 서로의 DB row를 재사용하지 않도록 승인 거래번호를 분리한다.
    // 고유 거래번호를 사용하면 실행 순서가 바뀌거나 일부 테스트만 반복 실행되어도 결과가 섞이지 않는다.
    // IT-APP-001 승인 저장 검증용 원거래 번호다.
    private static final String APPROVE_POS_TRX_IT_APP_001 = "2376-20260601-9991-1001";
    // IT-APP-002 승인 거절 저장 검증용 원거래 번호다.
    private static final String APPROVE_POS_TRX_IT_APP_002 = "2376-20260601-9991-1002";
    // IT-APP-003 승인 UNKNOWN_TIMEOUT 저장 검증용 원거래 번호다.
    private static final String APPROVE_POS_TRX_IT_APP_003 = "2376-20260601-9991-1003";
    // IT-APP-004 확정 승인건 Inquiry 검증용 원거래 번호다.
    private static final String APPROVE_POS_TRX_IT_APP_004 = "2376-20260601-9991-1004";
    // IT-APP-005 승인 후 취소 검증용 원거래 번호다.
    private static final String APPROVE_POS_TRX_IT_APP_005 = "2376-20260601-9991-1005";
    // IT-APP-006 거절 승인건 취소 불가 검증용 원거래 번호다.
    private static final String APPROVE_POS_TRX_IT_APP_006 = "2376-20260601-9991-1006";
    // IT-APP-007 동일 원거래 재취소 검증용 원거래 번호다.
    private static final String APPROVE_POS_TRX_IT_APP_007 = "2376-20260601-9991-1017";
    // IT-APP-008 active 8자리 BIN 기반 PAYMENT_EXTERNAL_INFO 저장 검증용 거래 번호다.
    private static final String APPROVE_POS_TRX_IT_APP_008 = "2376-20260601-9991-1008";

    // 취소 요청 거래번호도 시나리오별로 분리한다. IT-APP-007은 재취소 요청 자체도 두 번 구분한다.
    // IT-APP-005에서 원거래를 취소할 때 사용하는 현재 취소 거래번호다.
    private static final String CANCEL_POS_TRX_IT_APP_005 = "2376-20260601-9991-2001";
    // IT-APP-006에서 거절 원거래의 취소를 시도할 때 사용하는 현재 취소 거래번호다.
    private static final String CANCEL_POS_TRX_IT_APP_006 = "2376-20260601-9991-2003";
    // IT-APP-007의 첫 번째 취소 요청 거래번호다.
    private static final String FIRST_CANCEL_POS_TRX_IT_APP_007 = "2376-20260601-9991-2007";
    // IT-APP-007의 두 번째 취소 요청 거래번호다. 원거래는 같지만 요청 자체는 별도임을 표현한다.
    private static final String SECOND_CANCEL_POS_TRX_IT_APP_007 = "2376-20260601-9991-2008";

    private static final List<String> APPROVE_POS_TRXS = List.of(
            APPROVE_POS_TRX_IT_APP_001,
            APPROVE_POS_TRX_IT_APP_002,
            APPROVE_POS_TRX_IT_APP_003,
            APPROVE_POS_TRX_IT_APP_004,
            APPROVE_POS_TRX_IT_APP_005,
            APPROVE_POS_TRX_IT_APP_006,
            APPROVE_POS_TRX_IT_APP_007,
            APPROVE_POS_TRX_IT_APP_008
    );

    private static final List<String> CANCEL_POS_TRXS = List.of(
            CANCEL_POS_TRX_IT_APP_005,
            CANCEL_POS_TRX_IT_APP_006,
            FIRST_CANCEL_POS_TRX_IT_APP_007,
            SECOND_CANCEL_POS_TRX_IT_APP_007
    );

    // MockMvc는 실제 서버 포트를 띄우지 않고도 Controller 계층부터 HTTP 요청 흐름을 테스트할 수 있게 해준다.
    @Autowired
    private MockMvc mockMvc;

    // ObjectMapper는 JSON 문자열을 JsonNode 트리로 변환해 필요한 응답 필드를 읽을 수 있게 해준다.
    @Autowired
    private ObjectMapper objectMapper;

    // JdbcTemplate은 API 호출 뒤 SQLite에 저장된 row를 SQL로 직접 확인하고 정리할 때 사용한다.
    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PaymentCancelRepository paymentCancelRepository;

    // @BeforeEach는 각 테스트 실행 직전에 호출된다. 이전 실행이 남긴 DB 데이터가 현재 테스트에 영향을 주지 않게 한다.
    @BeforeEach
    void cleanBefore() {
        cleanupTestData();
    }

    // @AfterEach는 각 테스트 실행 직후에도 호출된다. 성공 여부와 무관하게 다음 실행을 위해 테스트 데이터를 치운다.
    @AfterEach
    void cleanAfter() {
        cleanupTestData();
    }

    @Test
    @DisplayName("IT-APP-001 승인 성공 -> PAYMENT_ATTEMPT 저장 확인")
    void approveSuccess_shouldPersistApprovedPaymentAttempt() throws Exception {
        // IT-APP-001은 승인 API 성공 응답과 PAYMENT_ATTEMPT 저장 결과가 일치하는지 확인한다.
        // JsonNode는 Java 예약어가 아니라 Jackson 라이브러리의 JSON 트리 객체다.
        // approve()가 실제 API 응답 JSON 문자열을 JsonNode로 변환해서 반환한다.
        JsonNode approveResponse = approve(APPROVE_POS_TRX_IT_APP_001);

        // assertEquals(expected, actual)는 두 값이 같은지 검증한다. 다르면 이 테스트는 실패한다.
        // path("result_code")는 JSON 객체에서 result_code 필드로 이동한다.
        // asText()는 해당 JSON 값을 Java String으로 꺼낸다.
        // 예: {"result_code":"OK"} -> path("result_code").asText() 결과는 "OK"
        assertEquals("OK", approveResponse.path("result_code").asText());

        // data는 JSON 내부에 중첩된 객체다. JsonNode에 담아두면 data 아래 필드를 반복해서 읽기 편하다.
        JsonNode approveData = approveResponse.path("data");
        // asInt()는 JSON 숫자 값을 Java int로 변환한다.
        int attemptSeq = approveData.path("attemptSeq").asInt();

        // assertEquals는 실제 값이 기대값과 같은지, assertNotNull은 필수 응답 값이 생성되었는지 검증한다.
        assertEquals("APPROVED", approveData.path("finalStatus").asText());
        assertNotNull(textOrNull(approveData, "approvalNo"));

        // 통합 테스트는 API 응답만 확인하면 부족하므로, 실제 DB row가 기대 상태로 저장되었는지도 함께 검증한다.
        Map<String, Object> attemptRow = findPaymentAttempt(APPROVE_POS_TRX_IT_APP_001, attemptSeq);

        assertEquals("APPROVED", attemptRow.get("FINAL_STATUS"));
        assertEquals(APPROVE_POS_TRX_IT_APP_001, attemptRow.get("POS_TRX"));
        assertEquals(10000, ((Number) attemptRow.get("AMOUNT")).intValue());
        assertEquals("4242", attemptRow.get("CARD_LAST4"));
        assertNotNull(attemptRow.get("APPROVAL_NO"));
        assertNotNull(attemptRow.get("VAN_TRX_ID"));
        // assertNull은 승인 성공 row에 거절 사유가 잘못 저장되지 않았는지 확인한다.
        assertNull(attemptRow.get("DECLINE_CODE"));

    }

    @Test
    @DisplayName("IT-APP-002 승인 거절 -> PAYMENT_ATTEMPT 저장 확인")
    void approveDeclined_shouldPersistDeclinedPaymentAttempt() throws Exception {
        // IT-APP-002는 VAN 거절 응답이 API와 PAYMENT_ATTEMPT에 동일하게 반영되는지 확인한다.
        // 이 테스트가 통과하면 승인 거절도 유실되지 않고 후속 조회·취소 판단에 사용할 수 있다.
        JsonNode approveResponse = approveDeclined(APPROVE_POS_TRX_IT_APP_002);

        assertEquals("DECLINED", approveResponse.path("result_code").asText());

        JsonNode approveData = approveResponse.path("data");
        int attemptSeq = approveData.path("attemptSeq").asInt();

        assertEquals("DECLINED", approveData.path("finalStatus").asText());
        // textOrNull()은 JSON null과 누락 필드를 Java null로 통일해 선택 필드 검증을 명확하게 만든다.
        assertNull(textOrNull(approveData, "approvalNo"));
        assertNotNull(textOrNull(approveData, "declineCode"));
        assertEquals("1111", approveData.path("cardSummary").path("cardLast4").asText());

        // 응답만 맞고 DB 저장이 누락되는 회귀를 잡기 위해 실제 거절 row도 함께 확인한다.
        Map<String, Object> attemptRow = findPaymentAttempt(APPROVE_POS_TRX_IT_APP_002, attemptSeq);

        assertEquals("DECLINED", attemptRow.get("FINAL_STATUS"));
        assertNull(attemptRow.get("APPROVAL_NO"));
        assertNotNull(attemptRow.get("DECLINE_CODE"));
        assertEquals("1111", attemptRow.get("CARD_LAST4"));
        assertNotNull(attemptRow.get("VAN_TRX_ID"));
        // count = 1은 거절 attempt가 정확히 한 row로 저장되었다는 의미다.
        assertEquals(1, countPaymentAttempt(APPROVE_POS_TRX_IT_APP_002, attemptSeq));
    }

    @Test
    @DisplayName("IT-APP-008 active 8자리 BIN 승인 요청 -> PAYMENT_EXTERNAL_INFO 저장 확인")
    void approveWithActiveBin_shouldPersistPaymentExternalInfoSnapshot() throws Exception {
        // 41111111은 BIN_CATALOG seed에서 active 상태로 등록된 테스트 BIN이다.
        // last4=1111은 VAN 시뮬레이터 규칙상 DECLINED지만, external info 스냅샷은 신규 attempt 저장 시점에 생성된다.
        JsonNode approveResponse = approveDeclined(APPROVE_POS_TRX_IT_APP_008);

        assertEquals("DECLINED", approveResponse.path("result_code").asText());

        JsonNode approveData = approveResponse.path("data");
        int attemptSeq = approveData.path("attemptSeq").asInt();
        JsonNode cardSummary = approveData.path("cardSummary");

        assertEquals("41111111", cardSummary.path("cardBin").asText());
        assertEquals("1111", cardSummary.path("cardLast4").asText());
        // 이번 PR의 응답 정책은 "필드 자체 제거"가 아니라 BIN_CATALOG 식별 brand 값을 응답에 세팅하지 않는 것이다.
        assertNotEquals("VISA", textOrNull(cardSummary, "cardBrand"));
        assertTrue(cardSummary.path("issuer").isMissingNode());
        assertTrue(cardSummary.path("country").isMissingNode());
        assertTrue(cardSummary.path("vanProvider").isMissingNode());

        Map<String, Object> attemptRow =
                findPaymentAttempt(APPROVE_POS_TRX_IT_APP_008, attemptSeq);
        Map<String, Object> externalInfoRow =
                findPaymentExternalInfo(APPROVE_POS_TRX_IT_APP_008, attemptSeq);

        assertEquals(APPROVE_POS_TRX_IT_APP_008, externalInfoRow.get("POS_TRX"));
        assertEquals(attemptSeq, ((Number) externalInfoRow.get("ATTEMPT_SEQ")).intValue());
        assertEquals("41111111", attemptRow.get("CARD_BIN"));
        assertEquals(attemptRow.get("CARD_BIN"), externalInfoRow.get("CARD_BIN"));
        assertEquals("1111", externalInfoRow.get("CARD_LAST4"));
        assertEquals("41111111******1111", externalInfoRow.get("MASKED_CARD_NO"));
        assertEquals("VISA", externalInfoRow.get("CARD_BRAND"));
        assertEquals("KB_CARD_TEST", externalInfoRow.get("CARD_ISSUER"));
        assertEquals("KR", externalInfoRow.get("CARD_COUNTRY"));
        assertEquals("MOCK_VAN", externalInfoRow.get("VAN_PROVIDER"));

        assertExternalInfoDoesNotContainPan(externalInfoRow, "4111111111111111");
    }

    @Test
    @DisplayName("IT-APP-003 승인 UNKNOWN_TIMEOUT -> Inquiry 재조회 후 UNKNOWN 유지")
    void approveUnknownTimeout_thenInquiry_shouldKeepUnknownTimeout() throws Exception {
        // given + when: UNKNOWN_TIMEOUT 유도용 카드로 approve API를 호출한다.
        JsonNode approveResponse = approveUnknownTimeout(APPROVE_POS_TRX_IT_APP_003);

        // then: Approve 응답이 UNKNOWN_TIMEOUT인지 확인한다.
        assertEquals("UNKNOWN_TIMEOUT", approveResponse.path("result_code").asText());

        JsonNode approveData = approveResponse.path("data");
        int attemptSeq = approveData.path("attemptSeq").asInt();

        assertEquals("UNKNOWN_TIMEOUT", approveData.path("finalStatus").asText());
        assertNull(textOrNull(approveData, "approvalNo"));
        assertNotNull(textOrNull(approveData, "declineCode"));
        assertEquals("7777", approveData.path("cardSummary").path("cardLast4").asText());

        // Approve 직후 DB에 UNKNOWN_TIMEOUT 상태가 저장되었는지 확인한다.
        Map<String, Object> attemptRowBeforeInquiry =
                findPaymentAttempt(APPROVE_POS_TRX_IT_APP_003, attemptSeq);
        int attemptCountBeforeInquiry =
                countPaymentAttempt(APPROVE_POS_TRX_IT_APP_003, attemptSeq);

        assertEquals(1, attemptCountBeforeInquiry);
        assertEquals("UNKNOWN_TIMEOUT", attemptRowBeforeInquiry.get("FINAL_STATUS"));
        assertEquals(APPROVE_POS_TRX_IT_APP_003, attemptRowBeforeInquiry.get("POS_TRX"));
        assertEquals(10000, ((Number) attemptRowBeforeInquiry.get("AMOUNT")).intValue());
        assertEquals("7777", attemptRowBeforeInquiry.get("CARD_LAST4"));
        assertNull(attemptRowBeforeInquiry.get("APPROVAL_NO"));
        assertNotNull(attemptRowBeforeInquiry.get("DECLINE_CODE"));
        assertNotNull(attemptRowBeforeInquiry.get("VAN_TRX_ID"));

        // when: UNKNOWN_TIMEOUT 거래를 inquiry로 다시 조회한다.
        JsonNode inquiryResponse = inquiry(APPROVE_POS_TRX_IT_APP_003, attemptSeq);

        // then: MVP 1차 정책상 inquiry 이후에도 UNKNOWN_TIMEOUT을 유지한다.
        assertEquals("UNKNOWN_TIMEOUT", inquiryResponse.path("result_code").asText());

        JsonNode inquiryData = inquiryResponse.path("data");
        assertEquals("UNKNOWN_TIMEOUT", inquiryData.path("finalStatus").asText());
        assertNull(textOrNull(inquiryData, "approvalNo"));
        assertEquals(textOrNull(approveData, "declineCode"), textOrNull(inquiryData, "declineCode"));

        // Inquiry 이후에도 attempt row가 새로 생성되거나 최종 상태가 바뀌지 않았는지 확인한다.
        Map<String, Object> attemptRowAfterInquiry =
                findPaymentAttempt(APPROVE_POS_TRX_IT_APP_003, attemptSeq);

        assertEquals(attemptCountBeforeInquiry, countPaymentAttempt(APPROVE_POS_TRX_IT_APP_003, attemptSeq));
        assertEquals("UNKNOWN_TIMEOUT", attemptRowAfterInquiry.get("FINAL_STATUS"));
        assertNull(attemptRowAfterInquiry.get("APPROVAL_NO"));
        assertEquals(attemptRowBeforeInquiry.get("VAN_TRX_ID"), attemptRowAfterInquiry.get("VAN_TRX_ID"));
    }

    @Test
    @DisplayName("IT-APP-004 이미 확정된 거래 Inquiry -> DB 재응답")
    void inquiryApprovedPayment_shouldReturnPersistedAttemptWithoutChangingRow() throws Exception {
        // IT-APP-004는 이미 APPROVED로 확정된 거래를 Inquiry할 때 저장된 결과를 그대로 재응답하는지 확인한다.
        // 통합 테스트에서는 VAN 호출 여부를 직접 verify하지 않고, 응답 값 유지와 DB row 불변성으로 DB 재응답 성격을 검증한다.
        JsonNode approveData = approve(APPROVE_POS_TRX_IT_APP_004).path("data");
        int attemptSeq = approveData.path("attemptSeq").asInt();
        String approvalNo = textOrNull(approveData, "approvalNo");
        Map<String, Object> attemptRowBeforeInquiry = findPaymentAttempt(APPROVE_POS_TRX_IT_APP_004, attemptSeq);
        int attemptCountBeforeInquiry = countPaymentAttempt(APPROVE_POS_TRX_IT_APP_004, attemptSeq);

        JsonNode inquiryResponse = inquiry(APPROVE_POS_TRX_IT_APP_004, attemptSeq);

        assertEquals("OK", inquiryResponse.path("result_code").asText());

        JsonNode inquiryData = inquiryResponse.path("data");
        assertEquals("APPROVED", inquiryData.path("finalStatus").asText());
        // Inquiry 응답 승인번호를 최초 Approve 응답과 비교해 저장된 확정 결과를 재사용했는지 확인한다.
        assertEquals(approvalNo, textOrNull(inquiryData, "approvalNo"));

        Map<String, Object> attemptRowAfterInquiry = findPaymentAttempt(APPROVE_POS_TRX_IT_APP_004, attemptSeq);

        // count = 1 유지와 주요 컬럼 비교는 Inquiry가 새 attempt를 만들거나 기존 확정값을 덮어쓰지 않았음을 뜻한다.
        assertEquals(1, attemptCountBeforeInquiry);
        assertEquals(attemptCountBeforeInquiry, countPaymentAttempt(APPROVE_POS_TRX_IT_APP_004, attemptSeq));
        assertEquals("APPROVED", attemptRowAfterInquiry.get("FINAL_STATUS"));
        assertEquals(approvalNo, attemptRowAfterInquiry.get("APPROVAL_NO"));
        assertEquals(attemptRowBeforeInquiry.get("VAN_TRX_ID"), attemptRowAfterInquiry.get("VAN_TRX_ID"));
    }

    @Test
    @DisplayName("IT-APP-005 APPROVED 원거래 -> Cancel 성공")
    void cancelApprovedPayment_shouldPersistCancelledRow() throws Exception {
        // IT-APP-005는 승인으로 만든 원거래를 취소 API가 참조해 PAYMENT_CANCEL row로 저장하는지 확인한다.
        // PAYMENT_CANCEL은 원거래의 posTrx와 attemptSeq를 함께 보관해 어느 승인 시도를 취소했는지 연결한다.
        JsonNode approveResponse = approve(APPROVE_POS_TRX_IT_APP_005);
        JsonNode approveData = approveResponse.path("data");
        int attemptSeq = approveData.path("attemptSeq").asInt();

        assertEquals("APPROVED", approveData.path("finalStatus").asText());

        JsonNode cancelResponse = cancel(
                CANCEL_POS_TRX_IT_APP_005,
                APPROVE_POS_TRX_IT_APP_005,
                attemptSeq
        );

        assertEquals("OK", cancelResponse.path("result_code").asText());

        JsonNode cancelData = cancelResponse.path("data");
        assertEquals("CANCELLED", cancelData.path("cancelStatus").asText());
        assertNotNull(textOrNull(cancelData, "cancelApprovalNo"));

        Map<String, Object> cancelRow = findPaymentCancel(APPROVE_POS_TRX_IT_APP_005, attemptSeq);

        assertEquals(APPROVE_POS_TRX_IT_APP_005, cancelRow.get("ORIGINAL_TRX_NO"));
        assertEquals(attemptSeq, ((Number) cancelRow.get("ORIGINAL_ATTEMPT_SEQ")).intValue());
        assertEquals("CANCELLED", cancelRow.get("CANCEL_STATUS"));
        assertEquals(CANCEL_POS_TRX_IT_APP_005, cancelRow.get("CURRENT_TRX_NO"));
        assertNotNull(cancelRow.get("CANCEL_APPROVAL_NO"));
        assertNull(cancelRow.get("DECLINE_CODE"));

        PaymentCancel paymentCancel = paymentCancelRepository
                .findByOriginalPosTrxAndOriginalAttemptSeq(APPROVE_POS_TRX_IT_APP_005, attemptSeq)
                .orElseThrow();

        assertEquals(CancelStatus.CANCELLED, paymentCancel.cancelStatus());

    }

    @Test
    @DisplayName("IT-APP-006 DECLINED 원거래 -> Cancel 불가")
    void cancelDeclinedPayment_shouldNotPersistCancelRow() throws Exception {
        // IT-APP-006은 DECLINED 원거래의 취소 요청이 C4에서 차단되고 PAYMENT_CANCEL row를 만들지 않는지 확인한다.
        // 이 테스트가 통과하면 승인되지 않은 거래가 VAN 취소 단계로 넘어가는 잘못된 업무 흐름을 방지할 수 있다.
        JsonNode approveData = approveDeclined(APPROVE_POS_TRX_IT_APP_006).path("data");
        int attemptSeq = approveData.path("attemptSeq").asInt();

        assertEquals("DECLINED", approveData.path("finalStatus").asText());

        JsonNode cancelResponse = cancel(
                CANCEL_POS_TRX_IT_APP_006,
                APPROVE_POS_TRX_IT_APP_006,
                attemptSeq
        );

        assertEquals("CANCEL_NOT_ALLOWED", cancelResponse.path("result_code").asText());

        JsonNode cancelData = cancelResponse.path("data");
        assertEquals("CANCEL_NOT_ALLOWED", cancelData.path("cancelStatus").asText());
        assertEquals("ORIGINAL_NOT_APPROVED", cancelData.path("declineCode").asText());
        assertNull(textOrNull(cancelData, "cancelApprovalNo"));
        // CANCEL_NOT_ALLOWED는 응답 전용 상태다. count = 0은 C5 PENDING insert 전에 종료되었다는 의미다.
        assertEquals(0, countPaymentCancelByOriginal(APPROVE_POS_TRX_IT_APP_006, attemptSeq));
    }

    @Test
    @DisplayName("IT-APP-007 Cancel 성공 후 동일 원거래 재취소 -> 기존 cancel row 재응답")
    void cancelSameOriginalPaymentTwice_shouldReuseExistingCancelRow() throws Exception {
        // IT-APP-007은 같은 원거래를 다시 취소해도 새 row를 만들지 않고 기존 취소 결과를 재응답하는지 확인한다.
        JsonNode approveResponse = approve(APPROVE_POS_TRX_IT_APP_007);
        int attemptSeq = approveResponse.path("data").path("attemptSeq").asInt();

        JsonNode firstCancelResponse = cancel(
                FIRST_CANCEL_POS_TRX_IT_APP_007,
                APPROVE_POS_TRX_IT_APP_007,
                attemptSeq
        );
        JsonNode secondCancelResponse = cancel(
                SECOND_CANCEL_POS_TRX_IT_APP_007,
                APPROVE_POS_TRX_IT_APP_007,
                attemptSeq
        );

        JsonNode firstCancelData = firstCancelResponse.path("data");
        JsonNode secondCancelData = secondCancelResponse.path("data");
        String firstCancelApprovalNo = textOrNull(firstCancelData, "cancelApprovalNo");
        Map<String, Object> cancelRow = findPaymentCancel(APPROVE_POS_TRX_IT_APP_007, attemptSeq);

        assertEquals(FIRST_CANCEL_POS_TRX_IT_APP_007, cancelRow.get("CURRENT_TRX_NO"));
        assertEquals("CANCELLED", cancelRow.get("CANCEL_STATUS"));
        assertEquals("OK", firstCancelResponse.path("result_code").asText());
        assertEquals("ALREADY_CANCELLED", secondCancelResponse.path("result_code").asText());
        assertEquals("CANCELLED", firstCancelData.path("cancelStatus").asText());
        assertEquals("ALREADY_CANCELLED", secondCancelData.path("cancelStatus").asText());
        assertNotNull(firstCancelApprovalNo);
        // 두 번째 요청에도 첫 취소 승인번호를 돌려주는지 확인해 기존 cancel row 재응답 동작을 검증한다.
        assertEquals(firstCancelApprovalNo, textOrNull(secondCancelData, "cancelApprovalNo"));

        // queryForObject는 단일 집계값을 읽는다. row count = 1은 중복 취소 row 생성을 방어했다는 의미다.
        Integer cancelRowCount = jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*)
                FROM PAYMENT_CANCEL
                WHERE ORIGINAL_TRX_NO = ?
                  AND ORIGINAL_ATTEMPT_SEQ = ?
                """,
                Integer.class,
                APPROVE_POS_TRX_IT_APP_007,
                attemptSeq
        );

        assertEquals(1, cancelRowCount);
    }

    /**
     * MockMvc로 실제 approve API를 호출한다.
     * <p>
     * 이 메서드는 성공 결과를 임의로 만들어 반환하는 코드가 아니다.
     * 테스트용 HTTP 요청을 Controller에 보내고, 애플리케이션이 실제로 만든 응답 본문을 반환한다.
     * 요청은 Controller -> Service -> Repository -> MyBatis -> SQLite 흐름을 그대로 거친다.
     */
    private JsonNode approve(String posTrx) throws Exception {
        // %s는 문자열 자리표시자이고, formatted(posTrx)가 테스트별 거래번호를 그 자리에 넣는다.
        // requestBody는 예상 응답이 아니라 Controller에 전송할 실제 요청 JSON 문자열이다.
        String requestBody = """
                {
                  "posTrx": "%s",
                  "amount": 10000,
                  "card": {
                    "pan": "4242424242424242",
                    "expiryYyMm": "2812"
                  }
                }
                """.formatted(posTrx);

        /*
         * 아래 코드는 메서드 호출 결과에 다음 메서드를 이어 붙이는 fluent API 문법이다.
         *
         * 1. post("/api/v1/payments/approve")
         *    - HTTP POST 요청 객체를 만든다.
         *    - 승인 API는 데이터를 서버에 전달해 처리를 요청하므로 POST를 사용한다.
         *
         * 2. .contentType(MediaType.APPLICATION_JSON)
         *    - 요청 본문이 JSON 형식임을 Content-Type 헤더로 알린다.
         *
         * 3. .content(requestBody)
         *    - 위에서 만든 JSON 문자열을 HTTP 요청 본문에 넣는다.
         *
         * 4. mockMvc.perform(...)
         *    - 가짜 성공 응답을 만드는 것이 아니라 Spring MVC에 요청을 실제로 전달한다.
         *    - 실제 서버 포트는 열지 않지만 Controller부터 애플리케이션 로직은 실행된다.
         *
         * 5. .andExpect(status().isOk())
         *    - 애플리케이션이 반환한 HTTP 상태가 200 OK인지 검증한다.
         *
         * 6. .andReturn().getResponse().getContentAsString()
         *    - 실행 결과에서 HTTP 응답 객체를 꺼내고, JSON 응답 본문을 Java String으로 읽는다.
         */
        String responseBody = mockMvc.perform(post("/api/v1/payments/approve")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();

        // responseBody는 서버가 실제로 반환한 JSON 문자열이다.
        // readTree()는 문자열을 JsonNode 트리로 바꿔 path("data")처럼 필드 단위로 탐색할 수 있게 한다.
        return objectMapper.readTree(responseBody);
    }

    // 승인 거절용 카드로 실제 approve API를 호출한다.
    // APPROVED helper와 분리하면 시나리오가 어떤 VAN 규칙을 의도하는지 요청 데이터만 읽어도 알 수 있다.
    // HTTP 요청 생성과 응답 변환 과정은 approve()와 동일하고, 전송하는 카드번호만 다르다.
    private JsonNode approveDeclined(String posTrx) throws Exception {
        String requestBody = """
                {
                  "posTrx": "%s",
                  "amount": 10000,
                  "card": {
                    "pan": "4111111111111111",
                    "expiryYyMm": "2812"
                  }
                }
                """.formatted(posTrx);

        String responseBody = mockMvc.perform(post("/api/v1/payments/approve")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();

        return objectMapper.readTree(responseBody);
    }

    private JsonNode approveUnknownTimeout(String posTrx) throws Exception {
        String requestBody = """
            {
              "posTrx": "%s",
              "amount": 10000,
              "card": {
                "pan": "4111111100087777",
                "expiryYyMm": "2812"
              }
            }
            """.formatted(posTrx);

        String responseBody = mockMvc.perform(post("/api/v1/payments/approve")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();

        return objectMapper.readTree(responseBody);
    }

    // MockMvc로 실제 inquiry API를 호출한다.
    // 확정건 Inquiry는 VAN 재호출이 아니라 PAYMENT_ATTEMPT에 저장된 결과를 재응답하는 흐름이어야 한다.
    // approve()와 같은 MockMvc 문법을 사용하지만 URL과 요청 JSON 필드는 Inquiry API 계약에 맞춘다.
    private JsonNode inquiry(String posTrx, int attemptSeq) throws Exception {
        String requestBody = """
                {
                  "posTrx": "%s",
                  "attemptSeq": %d
                }
                """.formatted(posTrx, attemptSeq);

        String responseBody = mockMvc.perform(post("/api/v1/payments/inquiry")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();

        return objectMapper.readTree(responseBody);
    }

    // MockMvc로 실제 cancel API를 호출한다.
    // originalPosTrx + originalAttemptSeq는 여러 승인 시도 중 취소할 원거래 row를 정확히 지정하는 복합 식별키다.
    // approve()와 같은 MockMvc 문법을 사용하지만 URL과 요청 JSON 필드는 Cancel API 계약에 맞춘다.
    private JsonNode cancel(
            String posTrx,
            String originalPosTrx,
            int originalAttemptSeq
    ) throws Exception {
        String requestBody = """
                {
                  "posTrx": "%s",
                  "originalPosTrx": "%s",
                  "originalAttemptSeq": %d
                }
                """.formatted(posTrx, originalPosTrx, originalAttemptSeq);

        // approve helper와 같은 방식으로 Controller부터 실제 취소 처리 흐름을 실행한다.
        String responseBody = mockMvc.perform(post("/api/v1/payments/cancel")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();

        return objectMapper.readTree(responseBody);
    }

    // 단일 집계값을 반환하는 queryForObject로 동일 식별키의 attempt row 수를 확인한다.
    // 확정건 Inquiry 전후의 count를 비교하면 조회 요청이 새 attempt를 만들지 않았는지 검증할 수 있다.
    private int countPaymentAttempt(String posTrx, int attemptSeq) {
        // SQL의 ?는 값이 나중에 안전하게 들어갈 자리다. 아래 posTrx, attemptSeq가 순서대로 바인딩된다.
        // queryForObject는 SELECT COUNT(*)처럼 결과가 값 하나일 때 사용하고, Integer.class는 반환 타입을 지정한다.
        return Objects.requireNonNull(jdbcTemplate.queryForObject(
                // COUNT(*)는 정상적으로 null을 반환하지 않지만, Integer -> int 자동 변환 시 발생할 수 있는
                // NullPointerException 경고를 없애고 예상 밖 null을 즉시 발견하기 위해 requireNonNull()을 사용한다.
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

    // 취소 불가 거래에서 PAYMENT_CANCEL row가 생기지 않았는지 원거래 복합 식별키로 확인한다.
    // count = 0이면 C4 검증에서 종료되어 C5 PENDING insert가 실행되지 않았다는 의미다.
    private int countPaymentCancelByOriginal(String originalPosTrx, int originalAttemptSeq) {
        return Objects.requireNonNull(jdbcTemplate.queryForObject(
                // COUNT(*)는 정상적으로 null을 반환하지 않지만, Integer -> int 자동 변환 시 발생할 수 있는
                // NullPointerException 경고를 없애고 예상 밖 null을 즉시 발견하기 위해 requireNonNull()을 사용한다.
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

    // 승인 API가 저장한 PAYMENT_ATTEMPT row를 거래번호와 시도 순번으로 조회해 검증한다.
    private Map<String, Object> findPaymentAttempt(String posTrx, int attemptSeq) {
        // queryForMap은 결과 row 하나를 컬럼명-값 Map으로 반환한다. row가 없거나 여러 개면 테스트가 실패한다.
        // 예: attemptRow.get("FINAL_STATUS")는 조회된 row의 FINAL_STATUS 컬럼 값을 읽는다.
        return jdbcTemplate.queryForMap(
                """
                    SELECT
                        POS_TRX,
                        ATTEMPT_SEQ,
                        AMOUNT,
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

    private void assertExternalInfoDoesNotContainPan(Map<String, Object> externalInfoRow, String pan) {
        externalInfoRow.values().stream()
                .map(value -> Objects.toString(value, ""))
                .forEach(value -> assertFalse(value.contains(pan)));
    }

    // 취소 API가 저장한 PAYMENT_CANCEL row를 원거래 복합 식별키로 조회해 검증한다.
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

    // JsonNode.path()는 JSON 객체에서 fieldName에 해당하는 자식 필드를 찾는다.
    // 필드가 없을 때도 예외를 던지지 않고 missing node를 반환한다.
    // JSON null과 missing 값을 Java null로 통일하면 assertNull()로 같은 방식으로 검증할 수 있다.
    private String textOrNull(JsonNode node, String fieldName) {
        JsonNode value = node.path(fieldName);
        return value.isMissingNode() || value.isNull() ? null : value.asText();
    }

    // 이 클래스가 만든 거래번호만 삭제해 테스트 간 DB 오염을 막고, 다른 데이터는 건드리지 않는다.
    private void cleanupTestData() {
        for (String cancelPosTrx : CANCEL_POS_TRXS) {
            jdbcTemplate.update("DELETE FROM PAYMENT_CANCEL WHERE CURRENT_TRX_NO = ?", cancelPosTrx);
        }

        for (String approvePosTrx : APPROVE_POS_TRXS) {
            // PAYMENT_CANCEL이 PAYMENT_ATTEMPT를 FK로 참조하므로 자식 row를 먼저 삭제한 뒤 승인 row와 순번 row를 삭제한다.
            jdbcTemplate.update("DELETE FROM PAYMENT_CANCEL WHERE ORIGINAL_TRX_NO = ?", approvePosTrx);
            jdbcTemplate.update("DELETE FROM PAYMENT_EXTERNAL_INFO WHERE POS_TRX = ?", approvePosTrx);
            jdbcTemplate.update("DELETE FROM PAYMENT_ATTEMPT WHERE POS_TRX = ?", approvePosTrx);
            jdbcTemplate.update("DELETE FROM PAYMENT_ATTEMPT_SEQ WHERE POS_TRX = ?", approvePosTrx);
        }
    }
}
