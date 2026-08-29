package com.chaeyeongmin.payment_sim.api.payment.integration.postgres;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * PostgreSQL 전용 핵심 흐름 통합 테스트.
 *
 * <p>이 테스트의 목적은 SQLite 기반 통합 테스트로는 잡기 어려운 PostgreSQL SQL 문법과
 * 실제 스키마 타입 차이를 자동으로 검증하는 것이다. 예를 들어 PostgreSQL의
 * {@code ON CONFLICT ... DO UPDATE} 컬럼 참조 모호성, {@code RETURNING} 처리,
 * {@code TIMESTAMP} 컬럼 갱신 같은 동작은 실제 PostgreSQL에서 실행해 보아야 회귀를 확실히 잡을 수 있다.
 *
 * <p>테스트는 로컬에 설치된 PostgreSQL이나 개발자 PC의 {@code POSTGRES_PASSWORD}에 의존하지 않는다.
 * Testcontainers가 테스트 실행 중 임시 PostgreSQL 컨테이너를 만들고, Spring Boot가 그 컨테이너의
 * JDBC 접속 정보를 테스트 ApplicationContext에 주입한다.
 */
@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        // 운영 application-postgres.yml을 활성화하지 않고, 테스트에서 필요한 datasource/초기화만 명시한다.
        // 이렇게 해야 localhost:5432의 로컬 DB나 POSTGRES_PASSWORD 환경변수가 테스트에 개입하지 않는다.
        "spring.datasource.driver-class-name=org.postgresql.Driver",
        "spring.sql.init.mode=always",
        // PostgreSQL 컨테이너에는 운영 PostgreSQL 검증에 사용한 schema/data SQL을 그대로 적용한다.
        // continue-on-error=false로 두어 SQL 초기화 실패를 숨기지 않는다.
        "spring.sql.init.schema-locations=classpath:schema-postgres.sql",
        "spring.sql.init.data-locations=classpath:data-postgres.sql",
        "spring.sql.init.continue-on-error=false",
        // 카드 fingerprint는 HMAC 기반이라 테스트에서도 고정 secret이 필요하다.
        // Testcontainers 내부 DB와 함께 쓰는 테스트 전용 값이며 운영 비밀값이 아니다.
        "payment.card.secret-key=postgres-testcontainers-card-secret-key",
        "logging.file.name=./build/logs/postgres-core-flow-it.log"
})
class PostgresCoreFlowIntegrationTest {

    /*
     * @Container
     * - JUnit/Testcontainers가 이 필드를 테스트 컨테이너로 인식하게 한다.
     * - static 필드이므로 이 테스트 클래스 전체에서 PostgreSQL 컨테이너 1개를 공유한다.
     *
     * @ServiceConnection
     * - Spring Boot가 컨테이너의 JDBC URL, username, password를 자동으로 datasource에 연결한다.
     * - 컨테이너는 임의의 호스트 포트를 사용하므로 포트 번호를 코드에 고정하지 않는다.
     *
     * postgres:17
     * - 수동 검증 대상과 같은 PostgreSQL 17 계열로 고정해 버전 차이로 인한 SQL 동작 차이를 줄인다.
     */
    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>("postgres:17")
                    .withDatabaseName("payment_sim_it")
                    .withUsername("payment_sim_it")
                    .withPassword("payment_sim_it");

    private static final String POS_TRX_STORE_CD = "2376";
    private static final String POS_TRX_BIZ_DATE = "20260804";
    private static final String POS_TRX_POS_NO = "7711";
    private static final String POS_TRX_OTHER_POS_NO = "7712";

    private static final String APPROVE_IDEMPOTENT_POS_TRX = "2376-20260804-9911-3101";
    private static final String INQUIRY_POS_TRX = "2376-20260804-9911-3102";
    private static final String CANCEL_ORIGINAL_POS_TRX = "2376-20260804-9911-3103";
    private static final String FIRST_CANCEL_POS_TRX = "2376-20260804-9911-4101";
    private static final String SECOND_CANCEL_POS_TRX = "2376-20260804-9911-4102";

    // MockMvc는 실제 HTTP 포트를 열지 않고 Controller부터 Service, Repository, MyBatis까지 실행한다.
    // 따라서 네트워크 서버 관리 없이도 API 계약과 DB 반영 결과를 함께 검증할 수 있다.
    @Autowired
    private MockMvc mockMvc;

    // API 응답 JSON에서 result_code, data 필드를 안정적으로 읽기 위해 사용한다.
    @Autowired
    private ObjectMapper objectMapper;

    // API 호출 후 실제 PostgreSQL row 상태를 직접 확인하기 위한 테스트용 DB 접근 도구다.
    @Autowired
    private JdbcTemplate jdbcTemplate;

    // 각 테스트는 고유한 키를 쓰지만, 반복 실행과 부분 실행을 고려해 시작 전에 대상 데이터를 삭제한다.
    @BeforeEach
    void cleanBefore() {
        cleanupTestData();
    }

    // 실패한 테스트가 남긴 row가 다음 테스트나 다음 로컬 실행에 영향을 주지 않도록 한 번 더 정리한다.
    @AfterEach
    void cleanAfter() {
        cleanupTestData();
    }

    /**
     * [시나리오]
     * - Given: 비어 있는 PostgreSQL POS_TRX_SEQUENCE에 동일한 storeCd/bizDate/posNo 채번 키가 주어진다.
     * - When : POS 거래번호 발급 API를 같은 키로 3회 호출하고, 다른 posNo로 1회 추가 호출한다.
     * - Then : 동일 키 응답은 0001, 0002, 0003으로 증가하고 다른 posNo는 0001부터 시작한다.
     * - And  : DB에는 복합키별 row가 1건만 남고, 저장된 최종 seq는 응답의 마지막 4자리와 일치한다.
     *
     * [검증 의도]
     * - PostgreSQL의 INSERT ... ON CONFLICT ... DO UPDATE ... RETURNING 흐름이 실제 DB에서 동작하는지 확인한다.
     * - SQLite에서는 통과할 수 있는 UPSERT SQL이 PostgreSQL에서 컬럼 참조 모호성으로 깨지는 회귀를 잡는다.
     */
    @Test
    @DisplayName("PostgreSQL POS 거래번호 발급은 복합키별로 순번을 증가시킨다")
    void issuePosTrx_shouldIncrementSequencePerStoreDateAndPosNo() throws Exception {
        // 같은 점포/영업일/포스번호로 3회 요청해 INSERT 이후 ON CONFLICT UPDATE 경로를 모두 실행한다.
        // 이 흐름은 PostgreSQL에서 컬럼 참조가 모호하면 즉시 SQL 오류로 실패한다.
        JsonNode first = issuePosTrx(POS_TRX_STORE_CD, POS_TRX_BIZ_DATE, POS_TRX_POS_NO);
        JsonNode second = issuePosTrx(POS_TRX_STORE_CD, POS_TRX_BIZ_DATE, POS_TRX_POS_NO);
        JsonNode third = issuePosTrx(POS_TRX_STORE_CD, POS_TRX_BIZ_DATE, POS_TRX_POS_NO);

        // 다른 posNo는 별도 복합키이므로 seq가 1부터 시작해야 한다.
        JsonNode otherPos = issuePosTrx(POS_TRX_STORE_CD, POS_TRX_BIZ_DATE, POS_TRX_OTHER_POS_NO);

        assertEquals("OK", first.path("result_code").asText());
        assertEquals("2376-20260804-7711-0001", first.path("data").path("pos_trx").asText());
        assertEquals("2376-20260804-7711-0002", second.path("data").path("pos_trx").asText());
        assertEquals("2376-20260804-7711-0003", third.path("data").path("pos_trx").asText());
        assertEquals("2376-20260804-7712-0001", otherPos.path("data").path("pos_trx").asText());

        // 응답만 확인하면 DB unique/upsert 회귀를 놓칠 수 있으므로, 최종 seq와 row 수까지 확인한다.
        assertEquals(3, posTrxSequence(POS_TRX_STORE_CD, POS_TRX_BIZ_DATE, POS_TRX_POS_NO));
        assertEquals(1, posTrxSequence(POS_TRX_STORE_CD, POS_TRX_BIZ_DATE, POS_TRX_OTHER_POS_NO));
        assertEquals(1, countPosTrxSequence(POS_TRX_STORE_CD, POS_TRX_BIZ_DATE, POS_TRX_POS_NO));
        assertEquals(1, countPosTrxSequence(POS_TRX_STORE_CD, POS_TRX_BIZ_DATE, POS_TRX_OTHER_POS_NO));
    }

    /**
     * [시나리오]
     * - Given: 승인 이력이 없는 posTrx와 정상 승인되는 카드번호가 주어진다.
     * - When : 승인 API를 동일한 요청 payload로 2회 호출한다.
     * - Then : 두 응답은 같은 attemptSeq와 같은 approvalNo를 반환한다.
     * - And  : PAYMENT_ATTEMPT와 PAYMENT_EXTERNAL_INFO는 각각 1건만 유지된다.
     *
     * [검증 의도]
     * - 중복 승인 요청이 신규 VAN 승인이나 신규 attempt 생성으로 이어지지 않는지 확인한다.
     * - PostgreSQL에서 PAYMENT_ATTEMPT_SEQ UPSERT와 승인 확정 UPDATE RETURNING이 함께 정상 동작하는지 검증한다.
     */
    @Test
    @DisplayName("PostgreSQL 승인 멱등 요청은 기존 승인 row를 재사용한다")
    void approveSameRequestTwice_shouldReuseApprovedAttempt() throws Exception {
        // 동일 승인 요청을 두 번 보내면 두 번째 요청은 VAN/DB 신규 승인 흐름이 아니라 기존 확정 row를 재사용해야 한다.
        JsonNode first = approve(APPROVE_IDEMPOTENT_POS_TRX);
        JsonNode second = approve(APPROVE_IDEMPOTENT_POS_TRX);

        JsonNode firstData = first.path("data");
        JsonNode secondData = second.path("data");
        String approvalNo = textOrNull(firstData, "approvalNo");

        assertEquals("OK", first.path("result_code").asText());
        assertEquals("OK", second.path("result_code").asText());
        assertEquals(1, firstData.path("attemptSeq").asInt());
        assertEquals(firstData.path("attemptSeq").asInt(), secondData.path("attemptSeq").asInt());
        assertEquals(approvalNo, textOrNull(secondData, "approvalNo"));
        assertNotNull(approvalNo);

        // 멱등성의 핵심은 응답 값뿐 아니라 PAYMENT_ATTEMPT와 부가 정보 row가 늘어나지 않는 것이다.
        assertEquals(1, countPaymentAttempts(APPROVE_IDEMPOTENT_POS_TRX));
        assertEquals(1, countPaymentExternalInfos(APPROVE_IDEMPOTENT_POS_TRX));
        assertEquals("APPROVED", paymentAttemptStatus(APPROVE_IDEMPOTENT_POS_TRX, 1));
        assertEquals(approvalNo, paymentAttemptApprovalNo(APPROVE_IDEMPOTENT_POS_TRX, 1));
    }

    /**
     * [시나리오]
     * - Given: 승인 API로 APPROVED 상태의 PAYMENT_ATTEMPT row가 이미 저장되어 있다.
     * - When : 해당 posTrx와 attemptSeq로 승인 거래 조회 API를 호출한다.
     * - Then : 조회 응답은 기존 APPROVED 상태와 기존 approvalNo를 반환한다.
     * - And  : 조회 과정에서 PAYMENT_ATTEMPT나 PAYMENT_EXTERNAL_INFO row가 새로 생성되지 않는다.
     *
     * [검증 의도]
     * - 확정 승인건 조회가 저장된 DB 결과를 재응답하는지 확인한다.
     * - 조회 요청이 승인 시도 생성이나 외부 정보 중복 저장 같은 부수효과를 만들지 않는지 고정한다.
     */
    @Test
    @DisplayName("PostgreSQL 승인 거래 조회는 기존 APPROVED row를 반환하고 새 row를 만들지 않는다")
    void inquiryApprovedPayment_shouldReturnPersistedAttemptWithoutCreatingRows() throws Exception {
        // 먼저 정상 승인을 만들어 inquiry 대상 row를 준비한다.
        JsonNode approve = approve(INQUIRY_POS_TRX);
        int attemptSeq = approve.path("data").path("attemptSeq").asInt();
        String approvalNo = textOrNull(approve.path("data"), "approvalNo");
        int attemptCountBefore = countPaymentAttempts(INQUIRY_POS_TRX);

        // 확정 승인건 조회는 저장된 APPROVED row를 반환해야 하며, 새 결제 attempt를 만들면 안 된다.
        JsonNode inquiry = inquiry(INQUIRY_POS_TRX, attemptSeq);

        assertEquals("OK", inquiry.path("result_code").asText());
        assertEquals("APPROVED", inquiry.path("data").path("finalStatus").asText());
        assertEquals(approvalNo, textOrNull(inquiry.path("data"), "approvalNo"));
        assertEquals(attemptCountBefore, countPaymentAttempts(INQUIRY_POS_TRX));
        assertEquals(1, countPaymentExternalInfos(INQUIRY_POS_TRX));
    }

    /**
     * [시나리오]
     * - Given: 승인 완료된 원거래가 있고, 아직 취소 row는 없다.
     * - When : 첫 번째 cancel posTrx로 취소 API를 호출한 뒤, 다른 cancel posTrx로 같은 원거래를 다시 취소 요청한다.
     * - Then : 첫 요청은 CANCELLED로 확정되고, 두 번째 요청은 ALREADY_CANCELLED로 기존 취소 결과를 재응답한다.
     * - And  : PAYMENT_CANCEL은 원거래 기준 1건만 유지되며, 두 번째 cancel posTrx row는 생성되지 않는다.
     * - And  : PAYMENT_EVENT_LOG에는 CANCEL_REUSED_BY_ORIGINAL 이벤트가 남는다.
     *
     * [검증 의도]
     * - PostgreSQL에서 취소 PENDING INSERT, 취소 결과 UPDATE RETURNING, 동일 원거래 재취소 조회 흐름을 검증한다.
     * - 중복 취소가 데이터 중복이나 승인번호 변경으로 이어지지 않는지 확인한다.
     */
    @Test
    @DisplayName("PostgreSQL 취소 후 같은 원거래 재취소는 기존 cancel row를 재응답한다")
    void cancelSameOriginalWithDifferentCancelPosTrx_shouldReuseExistingCancel() throws Exception {
        // 취소는 승인 완료된 원거래가 필요하므로 approve API로 원거래를 먼저 만든다.
        JsonNode approve = approve(CANCEL_ORIGINAL_POS_TRX);
        int attemptSeq = approve.path("data").path("attemptSeq").asInt();

        // 첫 요청은 실제 cancel row를 만들고 CANCELLED로 확정한다.
        JsonNode firstCancel = cancel(FIRST_CANCEL_POS_TRX, CANCEL_ORIGINAL_POS_TRX, attemptSeq);
        // 두 번째 요청은 cancel posTrx는 다르지만 original이 같으므로 기존 cancel row를 재사용해야 한다.
        JsonNode secondCancel = cancel(SECOND_CANCEL_POS_TRX, CANCEL_ORIGINAL_POS_TRX, attemptSeq);
        String cancelApprovalNo = textOrNull(firstCancel.path("data"), "cancelApprovalNo");

        assertEquals("OK", firstCancel.path("result_code").asText());
        assertEquals("CANCELLED", firstCancel.path("data").path("cancelStatus").asText());
        assertNotNull(cancelApprovalNo);

        assertEquals("ALREADY_CANCELLED", secondCancel.path("result_code").asText());
        assertEquals("ALREADY_CANCELLED", secondCancel.path("data").path("cancelStatus").asText());
        assertEquals(cancelApprovalNo, textOrNull(secondCancel.path("data"), "cancelApprovalNo"));

        // 중복 취소에서 중요한 DB 불변식:
        // - 원거래 기준 cancel row는 1건만 유지한다.
        // - 두 번째 cancel posTrx로는 PAYMENT_CANCEL row를 만들지 않는다.
        // - 재사용 응답은 PAYMENT_EVENT_LOG에 추적 이벤트로 남긴다.
        assertEquals(1, countPaymentCancelByOriginal(CANCEL_ORIGINAL_POS_TRX, attemptSeq));
        assertEquals(FIRST_CANCEL_POS_TRX, currentCancelPosTrx(CANCEL_ORIGINAL_POS_TRX, attemptSeq));
        assertEquals(0, countPaymentCancelByCurrent(SECOND_CANCEL_POS_TRX));
        assertEquals(1, countCancelReusedByOriginalEvents(CANCEL_ORIGINAL_POS_TRX, attemptSeq));
    }

    // POS 거래번호 발급 API를 호출한다. 응답의 pos_trx는 storeCd-bizDate-posNo-seq 형식이다.
    private JsonNode issuePosTrx(String storeCd, String bizDate, String posNo) throws Exception {
        return postJson(
                "/api/v1/pos-trx/issue",
                """
                {
                  "storeCd": "%s",
                  "bizDate": "%s",
                  "posNo": "%s"
                }
                """.formatted(storeCd, bizDate, posNo)
        );
    }

    // 정상 승인 카드번호로 approve API를 호출한다. 현재 VAN 시뮬레이터 규칙상 last4=4242는 APPROVED가 된다.
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

    // 승인 조회 API를 호출한다. posTrx와 attemptSeq가 조회 대상 PAYMENT_ATTEMPT row의 복합 식별자다.
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

    // 취소 API를 호출한다. 이번 테스트는 원승인 카드와 같은 카드번호를 사용해 정상 취소/재취소 흐름만 검증한다.
    private JsonNode cancel(String posTrx, String originalPosTrx, int originalAttemptSeq) throws Exception {
        return postJson(
                "/api/v1/payments/cancel",
                """
                {
                  "posTrx": "%s",
                  "originalPosTrx": "%s",
                  "originalAttemptSeq": %d,
                  "cardNo": "4242424242424242"
                }
                """.formatted(posTrx, originalPosTrx, originalAttemptSeq)
        );
    }

    /*
     * MockMvc 공통 POST helper.
     *
     * 실제 웹 서버 포트는 열지 않지만 Spring MVC DispatcherServlet을 통해 Controller가 호출된다.
     * 따라서 validation, controller mapping, service transaction, MyBatis SQL 실행까지 애플리케이션 흐름을 탄다.
     */
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

    // POS_TRX_SEQUENCE의 현재 seq를 읽어 응답의 마지막 4자리와 DB 상태가 일치하는지 확인한다.
    private int posTrxSequence(String storeCd, String bizDate, String posNo) {
        return Objects.requireNonNull(jdbcTemplate.queryForObject(
                """
                SELECT SEQ
                FROM POS_TRX_SEQUENCE
                WHERE STORE_CD = ?
                  AND BIZ_DATE = ?
                  AND POS_NO = ?
                """,
                Integer.class,
                storeCd,
                bizDate,
                posNo
        ));
    }

    // 복합키 unique 제약이 정상 동작하면 같은 storeCd/bizDate/posNo 조합은 항상 1 row만 존재한다.
    private int countPosTrxSequence(String storeCd, String bizDate, String posNo) {
        return count(
                """
                SELECT COUNT(*)
                FROM POS_TRX_SEQUENCE
                WHERE STORE_CD = ?
                  AND BIZ_DATE = ?
                  AND POS_NO = ?
                """,
                storeCd,
                bizDate,
                posNo
        );
    }

    // PAYMENT_ATTEMPT row 수로 approve/inquiry가 불필요한 결제 row를 만들지 않았는지 검증한다.
    private int countPaymentAttempts(String posTrx) {
        return count("SELECT COUNT(*) FROM PAYMENT_ATTEMPT WHERE POS_TRX = ?", posTrx);
    }

    // PAYMENT_EXTERNAL_INFO는 승인 attempt당 1건만 있어야 한다.
    private int countPaymentExternalInfos(String posTrx) {
        return count("SELECT COUNT(*) FROM PAYMENT_EXTERNAL_INFO WHERE POS_TRX = ?", posTrx);
    }

    // 원거래 기준 취소 row 수를 확인한다. 전액취소 MVP 정책에서는 원거래당 cancel row 1건이 상한이다.
    private int countPaymentCancelByOriginal(String originalPosTrx, int originalAttemptSeq) {
        return count(
                """
                SELECT COUNT(*)
                FROM PAYMENT_CANCEL
                WHERE ORIGINAL_TRX_NO = ?
                  AND ORIGINAL_ATTEMPT_SEQ = ?
                """,
                originalPosTrx,
                originalAttemptSeq
        );
    }

    // 두 번째 cancel posTrx가 PAYMENT_CANCEL row로 저장되지 않았는지 확인할 때 사용한다.
    private int countPaymentCancelByCurrent(String currentPosTrx) {
        return count("SELECT COUNT(*) FROM PAYMENT_CANCEL WHERE CURRENT_TRX_NO = ?", currentPosTrx);
    }

    // 동일 원거래 재취소가 기존 cancel row를 재사용했다는 운영 추적 이벤트를 확인한다.
    private int countCancelReusedByOriginalEvents(String originalPosTrx, int originalAttemptSeq) {
        return count(
                """
                SELECT COUNT(*)
                FROM PAYMENT_EVENT_LOG
                WHERE EVENT_TYPE = 'CANCEL_REUSED_BY_ORIGINAL'
                  AND ORIGINAL_POS_TRX = ?
                  AND ORIGINAL_ATTEMPT_SEQ = ?
                """,
                originalPosTrx,
                originalAttemptSeq
        );
    }

    // 승인 row의 최종 상태를 직접 확인한다.
    private String paymentAttemptStatus(String posTrx, int attemptSeq) {
        return jdbcTemplate.queryForObject(
                """
                SELECT FINAL_STATUS
                FROM PAYMENT_ATTEMPT
                WHERE POS_TRX = ?
                  AND ATTEMPT_SEQ = ?
                """,
                String.class,
                posTrx,
                attemptSeq
        );
    }

    // 멱등 재응답이 최초 승인번호를 그대로 재사용했는지 DB 값과 비교한다.
    private String paymentAttemptApprovalNo(String posTrx, int attemptSeq) {
        return jdbcTemplate.queryForObject(
                """
                SELECT APPROVAL_NO
                FROM PAYMENT_ATTEMPT
                WHERE POS_TRX = ?
                  AND ATTEMPT_SEQ = ?
                """,
                String.class,
                posTrx,
                attemptSeq
        );
    }

    // 원거래 기준으로 저장된 실제 cancel posTrx를 읽는다.
    private String currentCancelPosTrx(String originalPosTrx, int originalAttemptSeq) {
        return jdbcTemplate.queryForObject(
                """
                SELECT CURRENT_TRX_NO
                FROM PAYMENT_CANCEL
                WHERE ORIGINAL_TRX_NO = ?
                  AND ORIGINAL_ATTEMPT_SEQ = ?
                """,
                String.class,
                originalPosTrx,
                originalAttemptSeq
        );
    }

    // COUNT(*) 쿼리는 null을 반환하지 않아야 하므로 requireNonNull로 예상 밖 null을 즉시 드러낸다.
    private int count(String sql, Object... args) {
        return Objects.requireNonNull(jdbcTemplate.queryForObject(sql, Integer.class, args));
    }

    // Jackson JsonNode에서 JSON null과 missing field를 Java null로 통일해 검증 코드를 단순하게 만든다.
    private String textOrNull(JsonNode node, String fieldName) {
        JsonNode value = node.path(fieldName);
        return value.isMissingNode() || value.isNull() ? null : value.asText();
    }

    /*
     * 테스트 데이터 정리.
     *
     * 컨테이너 DB는 이 클래스 동안 공유되므로 테스트 간 데이터가 섞이지 않도록 대상 키만 삭제한다.
     * 삭제 순서는 FK 제약을 고려해 이벤트/취소/부가정보/승인/시퀀스 순서로 둔다.
     * seed 데이터인 BIN_CATALOG는 schema/data 초기화 검증 대상이므로 삭제하지 않는다.
     */
    private void cleanupTestData() {
        jdbcTemplate.update(
                """
                DELETE FROM PAYMENT_EVENT_LOG
                WHERE POS_TRX IN (?, ?, ?, ?, ?)
                   OR ORIGINAL_POS_TRX IN (?, ?, ?)
                """,
                APPROVE_IDEMPOTENT_POS_TRX,
                INQUIRY_POS_TRX,
                CANCEL_ORIGINAL_POS_TRX,
                FIRST_CANCEL_POS_TRX,
                SECOND_CANCEL_POS_TRX,
                APPROVE_IDEMPOTENT_POS_TRX,
                INQUIRY_POS_TRX,
                CANCEL_ORIGINAL_POS_TRX
        );
        jdbcTemplate.update("DELETE FROM PAYMENT_CANCEL WHERE CURRENT_TRX_NO IN (?, ?)", FIRST_CANCEL_POS_TRX, SECOND_CANCEL_POS_TRX);
        jdbcTemplate.update(
                "DELETE FROM PAYMENT_CANCEL WHERE ORIGINAL_TRX_NO IN (?, ?, ?)",
                APPROVE_IDEMPOTENT_POS_TRX,
                INQUIRY_POS_TRX,
                CANCEL_ORIGINAL_POS_TRX
        );
        jdbcTemplate.update(
                "DELETE FROM PAYMENT_EXTERNAL_INFO WHERE POS_TRX IN (?, ?, ?)",
                APPROVE_IDEMPOTENT_POS_TRX,
                INQUIRY_POS_TRX,
                CANCEL_ORIGINAL_POS_TRX
        );
        jdbcTemplate.update(
                "DELETE FROM PAYMENT_ATTEMPT WHERE POS_TRX IN (?, ?, ?)",
                APPROVE_IDEMPOTENT_POS_TRX,
                INQUIRY_POS_TRX,
                CANCEL_ORIGINAL_POS_TRX
        );
        jdbcTemplate.update(
                "DELETE FROM PAYMENT_ATTEMPT_SEQ WHERE POS_TRX IN (?, ?, ?)",
                APPROVE_IDEMPOTENT_POS_TRX,
                INQUIRY_POS_TRX,
                CANCEL_ORIGINAL_POS_TRX
        );
        jdbcTemplate.update(
                """
                DELETE FROM POS_TRX_SEQUENCE
                WHERE STORE_CD = ?
                  AND BIZ_DATE = ?
                  AND POS_NO IN (?, ?)
                """,
                POS_TRX_STORE_CD,
                POS_TRX_BIZ_DATE,
                POS_TRX_POS_NO,
                POS_TRX_OTHER_POS_NO
        );
    }
}
