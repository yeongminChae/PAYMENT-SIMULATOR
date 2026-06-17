package com.chaeyeongmin.payment_sim.api.payment.service.impl;

import com.chaeyeongmin.payment_sim.domain.model.BinCatalog;
import com.chaeyeongmin.payment_sim.domain.model.CardIdentity;
import com.chaeyeongmin.payment_sim.infra.repository.BinCatalogRepository;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BinCatalogServiceImplTest {

    private final BinCatalogRepository repository = mock(BinCatalogRepository.class);
    private final BinCatalogServiceImpl service = new BinCatalogServiceImpl(repository);

    /**
     * [UT_ID] UT-2-BIN-CATALOG-001
     *
     * <p>
     * 등록 active 8자리 BIN은 BIN_CATALOG의 issuer/brand/country를 사용하고,
     * VAN provider는 시뮬레이터 정책상 MOCK_VAN으로 고정한다.
     */
    @Test
    void identify_activeBin8_shouldReturnIssuerBrandCountryAndMockVan() {
        when(repository.findActiveBin8("41111111"))
                .thenReturn(Optional.of(new BinCatalog("41111111", "VISA", "KB_CARD_TEST", "KR", 8, "Y")));

        CardIdentity identity = service.identify("41111111", "1111");

        assertEquals("41111111", identity.cardBin());
        assertEquals("1111", identity.cardLast4());
        assertEquals("VISA", identity.brand());
        assertEquals("KB_CARD_TEST", identity.issuer());
        assertEquals("KR", identity.country());
        assertEquals("MOCK_VAN", identity.vanProvider());
        verify(repository).findActiveBin8("41111111");
    }

    /**
     * [UT_ID] UT-2-BIN-CATALOG-002
     *
     * <p>
     * 미등록 BIN은 결제 흐름을 실패시키지 않고 UNKNOWN 식별값으로 fallback한다.
     */
    @Test
    void identify_missingBin8_shouldReturnUnknownAndMockVan() {
        when(repository.findActiveBin8("99999999")).thenReturn(Optional.empty());

        CardIdentity identity = service.identify("99999999", "9999");

        assertUnknown("99999999", "9999", identity);
        verify(repository).findActiveBin8("99999999");
    }

    /**
     * [UT_ID] UT-2-BIN-CATALOG-003
     *
     * <p>
     * 비활성 BIN은 repository의 active 조회 대상에서 제외되므로 UNKNOWN으로 처리한다.
     */
    @Test
    void identify_inactiveBin8_shouldReturnUnknownAndMockVan() {
        when(repository.findActiveBin8("49999999")).thenReturn(Optional.empty());

        CardIdentity identity = service.identify("49999999", "9999");

        assertUnknown("49999999", "9999", identity);
        verify(repository).findActiveBin8("49999999");
    }

    private void assertUnknown(String cardBin, String cardLast4, CardIdentity identity) {
        assertEquals(cardBin, identity.cardBin());
        assertEquals(cardLast4, identity.cardLast4());
        assertEquals("UNKNOWN", identity.brand());
        assertEquals("UNKNOWN", identity.issuer());
        assertEquals("UNKNOWN", identity.country());
        assertEquals("MOCK_VAN", identity.vanProvider());
    }
}
