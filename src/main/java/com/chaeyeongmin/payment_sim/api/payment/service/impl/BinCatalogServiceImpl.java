package com.chaeyeongmin.payment_sim.api.payment.service.impl;

import com.chaeyeongmin.payment_sim.api.payment.service.BinCatalogService;
import com.chaeyeongmin.payment_sim.domain.model.CardIdentity;
import com.chaeyeongmin.payment_sim.infra.repository.BinCatalogRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 8자리 BIN 기준으로 카드 식별 정보를 만든다.
 *
 * <p>
 * 정책:
 * - BIN_CATALOG에 ACTIVE_YN='Y'이고 BIN_LEN=8인 row만 유효하다.
 * - 미등록/비활성/길이 불일치 BIN은 UNKNOWN으로 처리한다.
 * - VAN provider는 현재 시뮬레이터 정책상 MOCK_VAN으로 고정한다.
 */
@Service
@RequiredArgsConstructor
public class BinCatalogServiceImpl implements BinCatalogService {

    private static final int BIN_LENGTH = 8;

    private final BinCatalogRepository repository;

    @Override
    public CardIdentity identify(String cardBin, String cardLast4) {
        // 이 프로젝트의 cardBin은 모든 테이블에서 8자리 의미로 통일한다.
        if (cardBin == null || cardBin.length() != BIN_LENGTH) {
            return CardIdentity.unknown(cardBin, cardLast4);
        }

        return repository.findActiveBin8(cardBin)
                .map(catalog -> CardIdentity.from(cardBin, cardLast4, catalog))
                .orElseGet(() -> CardIdentity.unknown(cardBin, cardLast4));
    }
}
