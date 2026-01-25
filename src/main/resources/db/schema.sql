-- =====================================================================
-- schema.sql (SQLite)
-- 카드결제 시뮬레이터(MVP) DB 스키마
--
-- 원칙:
-- 1) CREATE TABLE/INDEX IF NOT EXISTS 로 멱등하게 작성 (재실행해도 안전)
-- 2) 민감정보(PAN/Track/EMV 원문) 저장 금지
-- 3) 시간 컬럼은 ISO8601 문자열(UTC 권장)로 저장 (기본값 now 제공)
-- =====================================================================

-- SQLite FK 제약 활성화(연결 단위 설정이라, 앱 커넥션에서도 별도 설정 권장)
PRAGMA foreign_keys = ON;

-- ---------------------------------------------------------------------
-- 1) POS_TRX_SEQUENCE
-- 목적: 점포/영업일/포스번호 단위로 "거래일련(SEQ)"을 관리하여
--       포스TR(pos_trx) 발번에 사용한다.
-- 핵심: UNIQUE(STORE_CD, BIZ_DATE, POS_NO)
-- 수정 이력 : 20260123 Last-Seq -> SEQ로 칼럼명 변경
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS POS_TRX_SEQUENCE (
    SEQ_ID      INTEGER PRIMARY KEY AUTOINCREMENT,
    STORE_CD    TEXT    NOT NULL,              -- 점포코드(4자리 권장)
    BIZ_DATE    TEXT    NOT NULL,              -- 영업일자(YYYYMMDD)
    POS_NO      TEXT    NOT NULL,              -- 포스번호(4자리 권장)
    SEQ    INTEGER NOT NULL,                   -- 마지막 발번된 거래일련
    UPDATED_AT  TEXT    NOT NULL DEFAULT (STRFTIME('%Y-%m-%dT%H:%M:%fZ','now')),

    UNIQUE (STORE_CD, BIZ_DATE, POS_NO)
    );

-- ---------------------------------------------------------------------
-- 2) PAYMENT_ATTEMPT
-- 목적: 승인 1회 "시도" 단위의 정본 데이터.
--       멱등/중복 방지를 위해 (POS_TRX, ATTEMPT_SEQ) 유니크를 보장한다.
-- 상태: FINAL_STATUS
--   - NULL = 처리중(미확정)
--   - APPROVED / DECLINED / UNKNOWN_TIMEOUT = 확정 상태
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS PAYMENT_ATTEMPT (
    ATTEMPT_ID    INTEGER PRIMARY KEY AUTOINCREMENT,
    POS_TRX       TEXT    NOT NULL,            -- 포스TR(거래번호)
    ATTEMPT_SEQ   INTEGER NOT NULL,            -- 결제시도 순번(서버 발급/관리)
    AMOUNT        INTEGER NOT NULL,            -- 결제금액(>0)
    CARD_BIN      TEXT    NULL,                -- BIN(최소정보)
    CARD_LAST4    TEXT    NULL,                -- 마지막 4자리
    CARD_BRAND    TEXT    NULL,                -- VISA/MC 등
    FINAL_STATUS  TEXT    NULL,                -- NULL/APPROVED/DECLINED/UNKNOWN_TIMEOUT
    APPROVAL_NO   TEXT    NULL,                -- 승인번호(승인 시)
    DECLINE_CODE  TEXT    NULL,                -- 거절코드(거절 시)
    CREATED_AT    TEXT    NOT NULL DEFAULT (STRFTIME('%Y-%m-%dT%H:%M:%fZ','now')),
    UPDATED_AT    TEXT    NOT NULL DEFAULT (STRFTIME('%Y-%m-%dT%H:%M:%fZ','now')),

    UNIQUE (POS_TRX, ATTEMPT_SEQ),
    CHECK (AMOUNT > 0)
    );

CREATE INDEX IF NOT EXISTS IDX_PAYMENT_ATTEMPT_POS_TRX
    ON PAYMENT_ATTEMPT (POS_TRX);

CREATE INDEX IF NOT EXISTS IDX_PAYMENT_ATTEMPT_FINAL_STATUS
    ON PAYMENT_ATTEMPT (FINAL_STATUS);

-- ---------------------------------------------------------------------
-- 3) PAYMENT_CANCEL
-- 목적: "전체취소" 추적용 row.
--       원거래(ORIGINAL_TRX_NO, ORIGINAL_ATTEMPT_SEQ) 기준 중복 취소 방지.
-- 상태: CANCEL_STATUS
--   - PENDING = 미확정(취소 타임아웃 등)
--   - CANCELLED / CANCEL_DECLINED = 확정 상태
-- FK(물리): 원거래 attempt를 참조(정상 흐름에서는 존재해야 함)
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS PAYMENT_CANCEL (
    CANCEL_ID            INTEGER PRIMARY KEY AUTOINCREMENT,
    CURRENT_TRX_NO        TEXT    NOT NULL,    -- 현거래번호(취소 요청 단위)
    ORIGINAL_TRX_NO       TEXT    NOT NULL,    -- 원거래 포스TR
    ORIGINAL_ATTEMPT_SEQ  INTEGER NOT NULL,    -- 원거래 attempt_seq
    CANCEL_STATUS         TEXT    NOT NULL,    -- PENDING/CANCELLED/CANCEL_DECLINED
    CANCEL_APPROVAL_NO    TEXT    NULL,        -- 취소 승인번호(성공 시)
    DECLINE_CODE          TEXT    NULL,        -- 취소 거절코드(거절 시)
    CREATED_AT            TEXT    NOT NULL DEFAULT (STRFTIME('%Y-%m-%dT%H:%M:%fZ','now')),
    UPDATED_AT            TEXT    NOT NULL DEFAULT (STRFTIME('%Y-%m-%dT%H:%M:%fZ','now')),

    UNIQUE (ORIGINAL_TRX_NO, ORIGINAL_ATTEMPT_SEQ),

    -- 원거래 존재를 보장(정책: APPROVED인 원거래만 취소 가능)
    FOREIGN KEY (ORIGINAL_TRX_NO, ORIGINAL_ATTEMPT_SEQ)
    REFERENCES PAYMENT_ATTEMPT (POS_TRX, ATTEMPT_SEQ)
    ON UPDATE RESTRICT
    ON DELETE RESTRICT
    );

CREATE INDEX IF NOT EXISTS IDX_PAYMENT_CANCEL_STATUS
    ON PAYMENT_CANCEL (CANCEL_STATUS);

CREATE INDEX IF NOT EXISTS IDX_PAYMENT_CANCEL_ORIGINAL_TRX
    ON PAYMENT_CANCEL (ORIGINAL_TRX_NO);

-- ---------------------------------------------------------------------
-- 4) BIN_CATALOG
-- 목적: BIN(카드빈) 매핑/검증 기준 데이터.
--       "카드빈16" 컨셉은 BIN_LEN(6/8/16 등) 컬럼으로 반영.
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS BIN_CATALOG (
    BIN        TEXT    NOT NULL,               -- BIN 값(문자열)
    BRAND      TEXT    NULL,                   -- VISA/MC 등
    ISSUER     TEXT    NULL,                   -- 발급사(선택)
    COUNTRY    TEXT    NULL,                   -- 국가(선택)
    BIN_LEN    INTEGER NOT NULL,               -- 예: 6/8/16
    ACTIVE_YN  TEXT    NOT NULL DEFAULT 'Y',   -- Y/N
    CREATED_AT TEXT    NOT NULL DEFAULT (STRFTIME('%Y-%m-%dT%H:%M:%fZ','now')),
    UPDATED_AT TEXT    NOT NULL DEFAULT (STRFTIME('%Y-%m-%dT%H:%M:%fZ','now')),

    PRIMARY KEY (BIN),
    CHECK (ACTIVE_YN IN ('Y','N'))
    );

CREATE INDEX IF NOT EXISTS IDX_BIN_CATALOG_ACTIVE
    ON BIN_CATALOG (ACTIVE_YN);

-- ---------------------------------------------------------------------
-- 5) PAYMENT_EVENT_LOG
-- 목적: 승인/조회/취소 처리 과정의 중요 이벤트를 남기는 저널 테이블.
--       전문 원문 저장은 하지 않고, 코드/요약만 기록한다.
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS PAYMENT_EVENT_LOG (
    EVENT_ID         INTEGER PRIMARY KEY AUTOINCREMENT,
    EVENT_TIME       TEXT    NOT NULL DEFAULT (STRFTIME('%Y-%m-%dT%H:%M:%fZ','now')),
    EVENT_TYPE       TEXT    NOT NULL,     -- APPROVE_REQ/APPROVE_RES/INQUIRY_REQ/...
    POS_TRX          TEXT    NULL,
    ATTEMPT_SEQ      INTEGER NULL,
    CURRENT_TRX_NO   TEXT    NULL,         -- 취소 현거래번호(있으면)
    RESULT_CODE      TEXT    NULL,         -- OK/DECLINED/RETRY_LATER 등
    STATUS_SNAPSHOT  TEXT    NULL,         -- FINAL_STATUS/CANCEL_STATUS 요약
    CORRELATION_ID   TEXT    NULL,         -- 요청 추적 ID (X-REQUEST-ID 등)
    NOTE             TEXT    NULL          -- 운영 메모(민감정보 금지)
    );

CREATE INDEX IF NOT EXISTS IDX_EVENT_LOG_POS_TRX_ATTEMPT
    ON PAYMENT_EVENT_LOG (POS_TRX, ATTEMPT_SEQ);

CREATE INDEX IF NOT EXISTS IDX_EVENT_LOG_CORRELATION
    ON PAYMENT_EVENT_LOG (CORRELATION_ID);

CREATE INDEX IF NOT EXISTS IDX_EVENT_LOG_EVENT_TYPE
    ON PAYMENT_EVENT_LOG (EVENT_TYPE);

CREATE INDEX IF NOT EXISTS IDX_EVENT_LOG_EVENT_TIME
    ON PAYMENT_EVENT_LOG (EVENT_TIME);