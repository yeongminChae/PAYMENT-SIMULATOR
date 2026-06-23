package com.chaeyeongmin.payment_sim.infra.repository.dto;

import java.time.LocalDateTime;

/**
 * [Repository DTO / MyBatis Param]
 * 승인 시도(PAYMENT_ATTEMPT) INSERT를 위해 Service → Repository로 전달하는 "저장용 파라미터" 객체.
 *
 * 목적/원칙
 * - ApproveRequest(클라이언트 입력)와 분리된, DB 저장에 필요한 값만 담는다.
 * - PAN/expiry 같은 민감정보 원문은 절대 담지 않는다. (저장/로그 금지)
 * - MyBatis 매퍼 파라미터로 그대로 바인딩 가능한 형태(불변 record)를 사용한다.
 *
 * 사용 예
 * - service에서 attemptSeq 발급 후, cardSummary(BIN/last4/brand)로 축약하여 본 record를 생성
 * - repo.insertAttempt(param) 형태로 전달
 */
public record AttemptInsertParam(
        String posTrx,
        int attemptSeq,
        int amount,
        String cardBin,
        String cardLast4,
        String cardBrand,      // 지금 없으면 null로,
        String cardFingerprint,
//        String requestId, TODO : [20260206] requestId 우선은 null로
        LocalDateTime createdAt
) {}
