-- =====================================================================
-- data.sql (SQLite)
-- 카드결제 시뮬레이터(MVP) seed data
--
-- 원칙:
-- 1) DML 전용 파일로 유지한다.
-- 2) 테스트용 기준 데이터는 재실행해도 안전하게 작성한다.
-- 3) 실제 카드사 소유 BIN으로 단정하지 않도록 issuer는 테스트명만 사용한다.
-- =====================================================================

INSERT INTO BIN_CATALOG (
	BIN,
	BRAND,
	ISSUER,
	COUNTRY,
	BIN_LEN,
	ACTIVE_YN,
	CREATED_AT,
	UPDATED_AT
)
VALUES
	('411111', 'VISA',   'KB_CARD_TEST',       'KR', 6, 'Y', STRFTIME('%Y-%m-%dT%H:%M:%fZ', 'now'), STRFTIME('%Y-%m-%dT%H:%M:%fZ', 'now')),
	('422222', 'VISA',   'SHINHAN_CARD_TEST',  'KR', 6, 'Y', STRFTIME('%Y-%m-%dT%H:%M:%fZ', 'now'), STRFTIME('%Y-%m-%dT%H:%M:%fZ', 'now')),
	('433333', 'VISA',   'HYUNDAI_CARD_TEST',  'KR', 6, 'Y', STRFTIME('%Y-%m-%dT%H:%M:%fZ', 'now'), STRFTIME('%Y-%m-%dT%H:%M:%fZ', 'now')),
	('444444', 'VISA',   'LOTTE_CARD_TEST',    'KR', 6, 'Y', STRFTIME('%Y-%m-%dT%H:%M:%fZ', 'now'), STRFTIME('%Y-%m-%dT%H:%M:%fZ', 'now')),
	('455555', 'VISA',   'WOORI_CARD_TEST',    'KR', 6, 'Y', STRFTIME('%Y-%m-%dT%H:%M:%fZ', 'now'), STRFTIME('%Y-%m-%dT%H:%M:%fZ', 'now')),

	('511111', 'MASTER', 'KB_CARD_TEST',       'KR', 6, 'Y', STRFTIME('%Y-%m-%dT%H:%M:%fZ', 'now'), STRFTIME('%Y-%m-%dT%H:%M:%fZ', 'now')),
	('522222', 'MASTER', 'SHINHAN_CARD_TEST',  'KR', 6, 'Y', STRFTIME('%Y-%m-%dT%H:%M:%fZ', 'now'), STRFTIME('%Y-%m-%dT%H:%M:%fZ', 'now')),
	('533333', 'MASTER', 'HYUNDAI_CARD_TEST',  'KR', 6, 'Y', STRFTIME('%Y-%m-%dT%H:%M:%fZ', 'now'), STRFTIME('%Y-%m-%dT%H:%M:%fZ', 'now')),
	('544444', 'MASTER', 'LOTTE_CARD_TEST',    'KR', 6, 'Y', STRFTIME('%Y-%m-%dT%H:%M:%fZ', 'now'), STRFTIME('%Y-%m-%dT%H:%M:%fZ', 'now')),
	('555555', 'MASTER', 'WOORI_CARD_TEST',    'KR', 6, 'Y', STRFTIME('%Y-%m-%dT%H:%M:%fZ', 'now'), STRFTIME('%Y-%m-%dT%H:%M:%fZ', 'now')),

	('611111', 'LOCAL',  'BC_CARD_TEST',       'KR', 6, 'Y', STRFTIME('%Y-%m-%dT%H:%M:%fZ', 'now'), STRFTIME('%Y-%m-%dT%H:%M:%fZ', 'now')),
	('622222', 'LOCAL',  'NH_CARD_TEST',       'KR', 6, 'Y', STRFTIME('%Y-%m-%dT%H:%M:%fZ', 'now'), STRFTIME('%Y-%m-%dT%H:%M:%fZ', 'now')),
	('633333', 'LOCAL',  'HANA_CARD_TEST',     'KR', 6, 'Y', STRFTIME('%Y-%m-%dT%H:%M:%fZ', 'now'), STRFTIME('%Y-%m-%dT%H:%M:%fZ', 'now')),

	('371111', 'AMEX',   'AMEX_TEST',          'US', 6, 'Y', STRFTIME('%Y-%m-%dT%H:%M:%fZ', 'now'), STRFTIME('%Y-%m-%dT%H:%M:%fZ', 'now')),

	('499999', 'VISA',   'INACTIVE_CARD_TEST', 'KR', 6, 'N', STRFTIME('%Y-%m-%dT%H:%M:%fZ', 'now'), STRFTIME('%Y-%m-%dT%H:%M:%fZ', 'now'))
ON CONFLICT(BIN) DO UPDATE SET
	BRAND = excluded.BRAND,
	ISSUER = excluded.ISSUER,
	COUNTRY = excluded.COUNTRY,
	BIN_LEN = excluded.BIN_LEN,
	ACTIVE_YN = excluded.ACTIVE_YN,
	UPDATED_AT = STRFTIME('%Y-%m-%dT%H:%M:%fZ', 'now');
