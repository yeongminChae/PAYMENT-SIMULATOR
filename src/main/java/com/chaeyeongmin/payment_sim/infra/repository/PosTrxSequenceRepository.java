package com.chaeyeongmin.payment_sim.infra.repository;

/**
 * [Repository 역할]
 * - 포스TR(거래번호) 채번을 위한 저장소 추상화(포트)
 * - Service는 이 인터페이스만 바라보고, 구현은 infra/repository/impl 에 둔다
 */

public interface PosTrxSequenceRepository {
    /**
     * 다음 거래번호 일련번호(예: 0021 -> 0022)를 원자적으로 발급한다.
     * 반환값은 "숫자" 시퀀스(예: 22)로 두고, 포맷(0000 패딩 등)은 Service/Policy가 책임진다.
     */
    long nextSeq(String storeCd, String bizDate, String posNo);
}