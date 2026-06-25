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
CREATE TABLE IF NOT EXISTS POS_TRX_SEQUENCE
(
	SEQ_ID     INTEGER PRIMARY KEY AUTOINCREMENT,
	STORE_CD   TEXT    NOT NULL, -- 점포코드(4자리 권장)
	BIZ_DATE   TEXT    NOT NULL, -- 영업일자(YYYYMMDD)
	POS_NO     TEXT    NOT NULL, -- 포스번호(4자리 권장)
	SEQ        INTEGER NOT NULL, -- 마지막 발번된 거래일련
	UPDATED_AT TEXT    NOT NULL DEFAULT (STRFTIME('%Y-%m-%dT%H:%M:%fZ', 'now')),

	UNIQUE (STORE_CD, BIZ_DATE, POS_NO)
);

-- ---------------------------------------------------------------------
-- 2) PAYMENT_ATTEMPT_SEQ
-- 목적: 동일 포스TR(POS_TRX) 내 결제시도 번호(ATTEMPT_SEQ)를
--       원자적으로 발급하기 위한 기준 테이블.
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS PAYMENT_ATTEMPT_SEQ
(
	SEQ_ID     INTEGER PRIMARY KEY AUTOINCREMENT,
	POS_TRX    TEXT    NOT NULL, -- 포스TR(거래번호)
	LAST_SEQ   INTEGER NOT NULL, -- 해당 POS_TRX에서 마지막으로 발급된 ATTEMPT_SEQ
	CREATED_AT TEXT    NOT NULL DEFAULT (STRFTIME('%Y-%m-%dT%H:%M:%fZ', 'now')),
	UPDATED_AT TEXT    NOT NULL DEFAULT (STRFTIME('%Y-%m-%dT%H:%M:%fZ', 'now')),

	UNIQUE (POS_TRX)
);

CREATE INDEX IF NOT EXISTS IDX_PAYMENT_ATTEMPT_SEQ_POS_TRX
	ON PAYMENT_ATTEMPT_SEQ (POS_TRX);

-- ---------------------------------------------------------------------
-- 3) PAYMENT_ATTEMPT
-- 목적: 승인 1회 "시도" 단위의 정본 데이터.
--       멱등/중복 방지를 위해 (POS_TRX, ATTEMPT_SEQ) 유니크를 보장한다.
-- 상태: FINAL_STATUS
--   - NULL = 처리중(미확정)
--   - APPROVED / DECLINED / UNKNOWN_TIMEOUT = 확정 상태
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS PAYMENT_ATTEMPT
(
	ATTEMPT_ID   INTEGER PRIMARY KEY AUTOINCREMENT,
	POS_TRX      TEXT    NOT NULL, -- 포스TR(거래번호)
	ATTEMPT_SEQ  INTEGER NOT NULL, -- 결제시도 순번(서버 발급/관리)
	AMOUNT       INTEGER NOT NULL, -- 결제금액(>0)
	CARD_BIN     TEXT    NULL,     -- 8자리 BIN(최소정보)
	CARD_LAST4   TEXT    NULL,     -- 마지막 4자리
	CARD_FINGERPRINT TEXT    NULL, -- HMAC-SHA256 카드 fingerprint(hex)
	CARD_BRAND   TEXT    NULL,     -- VISA/MC 등
	FINAL_STATUS TEXT    NULL,     -- NULL/APPROVED/DECLINED/UNKNOWN_TIMEOUT
	APPROVAL_NO  TEXT    NULL,     -- 승인번호(승인 시)
	DECLINE_CODE TEXT    NULL,     -- 거절코드(거절 시)
	VAN_TRX_ID   TEXT    NULL,     -- VAN 쪽에서 해당 승인/조회 건을 추적하기 위한 내부 거래키
	CREATED_AT   TEXT    NOT NULL DEFAULT (STRFTIME('%Y-%m-%dT%H:%M:%fZ', 'now')),
	UPDATED_AT   TEXT    NOT NULL DEFAULT (STRFTIME('%Y-%m-%dT%H:%M:%fZ', 'now')),

	UNIQUE (POS_TRX, ATTEMPT_SEQ),
	CHECK (AMOUNT > 0)
);

CREATE INDEX IF NOT EXISTS IDX_PAYMENT_ATTEMPT_POS_TRX
	ON PAYMENT_ATTEMPT (POS_TRX);

CREATE INDEX IF NOT EXISTS IDX_PAYMENT_ATTEMPT_FINAL_STATUS
	ON PAYMENT_ATTEMPT (FINAL_STATUS);

-- 기존 PAYMENT_ATTEMPT가 이미 만들어진 SQLite DB는 CREATE TABLE IF NOT EXISTS로 컬럼이 보강되지 않는다.
-- SQLite에는 ADD COLUMN IF NOT EXISTS가 없어, application.yml의 continue-on-error로 이미 존재하는 컬럼 오류만 통과시킨다.
ALTER TABLE PAYMENT_ATTEMPT ADD COLUMN CARD_FINGERPRINT TEXT NULL;

-- ---------------------------------------------------------------------
-- 4) PAYMENT_CANCEL
-- 목적: "전체취소" 추적용 row.
--       원거래(ORIGINAL_TRX_NO, ORIGINAL_ATTEMPT_SEQ) 기준 중복 취소 방지.
-- 상태: CANCEL_STATUS
--   - PENDING = 미확정(취소 타임아웃 등)
--   - CANCELLED / CANCEL_DECLINED = 확정 상태
-- FK(물리): 원거래 attempt를 참조(정상 흐름에서는 존재해야 함)
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS PAYMENT_CANCEL
(
	CANCEL_ID            INTEGER
		primary key autoincrement,
	CURRENT_TRX_NO       TEXT                                                 not null,
	ORIGINAL_TRX_NO      TEXT                                                 not null,
	ORIGINAL_ATTEMPT_SEQ INTEGER                                              not null,
	CANCEL_STATUS        TEXT                                                 not null,
	CANCEL_APPROVAL_NO   TEXT,
	DECLINE_CODE         TEXT,
	CREATED_AT           TEXT default (STRFTIME('%Y-%m-%dT%H:%M:%fZ', 'now')) not null,
	UPDATED_AT           TEXT default (STRFTIME('%Y-%m-%dT%H:%M:%fZ', 'now')) not null,

	unique (CURRENT_TRX_NO),
	unique (ORIGINAL_TRX_NO, ORIGINAL_ATTEMPT_SEQ),

	foreign key (ORIGINAL_TRX_NO, ORIGINAL_ATTEMPT_SEQ)
		references PAYMENT_ATTEMPT (POS_TRX, ATTEMPT_SEQ)
		on update restrict
		on delete restrict
);

CREATE INDEX IF NOT EXISTS IDX_PAYMENT_CANCEL_STATUS
	ON PAYMENT_CANCEL (CANCEL_STATUS);

CREATE INDEX IF NOT EXISTS IDX_PAYMENT_CANCEL_ORIGINAL_TRX
	ON PAYMENT_CANCEL (ORIGINAL_TRX_NO);

-- ---------------------------------------------------------------------
-- 5) BIN_CATALOG
-- 목적: BIN(카드빈) 매핑/검증 기준 데이터.
--       이 프로젝트의 카드 식별 기준은 8자리 BIN이다.
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS BIN_CATALOG
(
	BIN        TEXT    NOT NULL,             -- BIN 값(문자열)
	BRAND      TEXT    NULL,                 -- VISA/MC 등
	ISSUER     TEXT    NULL,                 -- 발급사(선택)
	COUNTRY    TEXT    NULL,                 -- 국가(선택)
	BIN_LEN    INTEGER NOT NULL,             -- 8
	ACTIVE_YN  TEXT    NOT NULL DEFAULT 'Y', -- Y/N
	CREATED_AT TEXT    NOT NULL DEFAULT (STRFTIME('%Y-%m-%dT%H:%M:%fZ', 'now')),
	UPDATED_AT TEXT    NOT NULL DEFAULT (STRFTIME('%Y-%m-%dT%H:%M:%fZ', 'now')),

	PRIMARY KEY (BIN),
	CHECK (ACTIVE_YN IN ('Y', 'N'))
);

CREATE INDEX IF NOT EXISTS IDX_BIN_CATALOG_ACTIVE
	ON BIN_CATALOG (ACTIVE_YN);

-- ---------------------------------------------------------------------
-- 6) PAYMENT_EXTERNAL_INFO
-- 목적: 승인 attempt에 연결된 카드/VAN/대외거래 식별 정보.
--       PAN 원문은 저장하지 않고 8자리 BIN, last4, masked card no만 저장한다.
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS PAYMENT_EXTERNAL_INFO
(
	EXTERNAL_INFO_ID INTEGER PRIMARY KEY AUTOINCREMENT,
	POS_TRX          TEXT    NOT NULL,
	ATTEMPT_SEQ      INTEGER NOT NULL,
	CARD_BIN         TEXT    NOT NULL, -- 8자리 BIN
	CARD_LAST4       TEXT    NOT NULL,
	MASKED_CARD_NO   TEXT    NOT NULL,
	CARD_BRAND       TEXT    NOT NULL,
	CARD_ISSUER      TEXT    NOT NULL,
	CARD_COUNTRY     TEXT    NOT NULL,
	VAN_PROVIDER     TEXT    NOT NULL,
	CREATED_AT       TEXT    NOT NULL DEFAULT (STRFTIME('%Y-%m-%dT%H:%M:%fZ', 'now')),
	UPDATED_AT       TEXT    NOT NULL DEFAULT (STRFTIME('%Y-%m-%dT%H:%M:%fZ', 'now')),

	UNIQUE (POS_TRX, ATTEMPT_SEQ),
	FOREIGN KEY (POS_TRX, ATTEMPT_SEQ)
		REFERENCES PAYMENT_ATTEMPT (POS_TRX, ATTEMPT_SEQ)
		ON UPDATE RESTRICT
		ON DELETE RESTRICT
);

CREATE INDEX IF NOT EXISTS IDX_PAYMENT_EXTERNAL_INFO_POS_TRX
	ON PAYMENT_EXTERNAL_INFO (POS_TRX);

-- ---------------------------------------------------------------------
-- 7) PAYMENT_EVENT_LOG
-- 목적: 승인/조회/취소 처리 과정의 중요 이벤트를 남기는 저널 테이블.
--       전문 원문 저장은 하지 않고, 코드/요약만 기록한다.
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS PAYMENT_EVENT_LOG
(
	EVENT_ID             INTEGER PRIMARY KEY AUTOINCREMENT,
	EVENT_TIME           TEXT    NOT NULL DEFAULT (STRFTIME('%Y-%m-%dT%H:%M:%fZ', 'now')),
	EVENT_TYPE           TEXT    NOT NULL, -- APPROVE_REQ/APPROVE_RES/INQUIRY_REQ/...
	POS_TRX              TEXT    NULL,
	ATTEMPT_SEQ          INTEGER NULL,
	CURRENT_TRX_NO       TEXT    NULL,     -- legacy: 신규 이벤트는 POS_TRX에 현재 요청 거래번호를 기록하고 이 컬럼은 사용하지 않는다.
	ORIGINAL_POS_TRX     TEXT    NULL,     -- 취소 이벤트의 원승인 거래번호
	ORIGINAL_ATTEMPT_SEQ INTEGER NULL,     -- 취소 이벤트의 원승인 attemptSeq
	RESULT_CODE          TEXT    NULL,     -- OK/DECLINED/RETRY_LATER 등
	STATUS_SNAPSHOT      TEXT    NULL,     -- FINAL_STATUS/CANCEL_STATUS 요약
	VAN_TRX_ID           TEXT    NULL,     -- VAN 추적 ID
	APPROVAL_NO          TEXT    NULL,     -- 승인번호 또는 취소승인번호
	DECLINE_CODE         TEXT    NULL,     -- 승인/취소 거절, 취소 불가 사유 코드
	CORRELATION_ID       TEXT    NULL,     -- 요청 추적 ID (X-REQUEST-ID 등)
	NOTE                 TEXT    NULL      -- 운영 메모(민감정보 금지)
);

-- 기존 PAYMENT_EVENT_LOG가 이미 만들어진 SQLite DB는 CREATE TABLE IF NOT EXISTS로 컬럼이 보강되지 않는다.
-- SQLite에는 ADD COLUMN IF NOT EXISTS가 없어, application.yml의 continue-on-error로 이미 존재하는 컬럼 오류만 통과시킨다.
ALTER TABLE PAYMENT_EVENT_LOG ADD COLUMN ORIGINAL_POS_TRX TEXT NULL;
ALTER TABLE PAYMENT_EVENT_LOG ADD COLUMN ORIGINAL_ATTEMPT_SEQ INTEGER NULL;
ALTER TABLE PAYMENT_EVENT_LOG ADD COLUMN VAN_TRX_ID TEXT NULL;
ALTER TABLE PAYMENT_EVENT_LOG ADD COLUMN APPROVAL_NO TEXT NULL;
ALTER TABLE PAYMENT_EVENT_LOG ADD COLUMN DECLINE_CODE TEXT NULL;

CREATE INDEX IF NOT EXISTS IDX_EVENT_LOG_POS_TRX_ATTEMPT
	ON PAYMENT_EVENT_LOG (POS_TRX, ATTEMPT_SEQ);

CREATE INDEX IF NOT EXISTS IDX_EVENT_LOG_ORIGINAL
	ON PAYMENT_EVENT_LOG (ORIGINAL_POS_TRX, ORIGINAL_ATTEMPT_SEQ);

CREATE INDEX IF NOT EXISTS IDX_EVENT_LOG_CORRELATION
	ON PAYMENT_EVENT_LOG (CORRELATION_ID);

CREATE INDEX IF NOT EXISTS IDX_EVENT_LOG_EVENT_TYPE
	ON PAYMENT_EVENT_LOG (EVENT_TYPE);

CREATE INDEX IF NOT EXISTS IDX_EVENT_LOG_EVENT_TIME
	ON PAYMENT_EVENT_LOG (EVENT_TIME);
