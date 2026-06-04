package com.chaeyeongmin.payment_sim.api.postrx;

import com.chaeyeongmin.payment_sim.api.postrx.dto.PosTrxEotRequest;
import com.chaeyeongmin.payment_sim.api.postrx.dto.PosTrxEotResponse;
import com.chaeyeongmin.payment_sim.api.postrx.service.PosTrxService;
import com.chaeyeongmin.payment_sim.api.postrx.service.PosTrxServiceImpl;
import com.chaeyeongmin.payment_sim.infra.repository.PosTrxSequenceRepository;

import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;

/**
 * PosTrxServiceImpl 단위 테스트(UT)
 * <p>
 * - 대상: PosTrxServiceImpl.eot()
 * - 범위: Mockito로 Repository를 Mock 처리하여 서비스 로직만 검증한다.
 */

@Slf4j
class PosTrxServiceImplTest {

    // UT-도메인-기능-일련번호
    private static final String UT_ID_EOT_001 = "UT-POS-TRX-EOT-001";
    private static final String UT_ID_EOT_002 = "UT-POS-TRX-EOT-002";
    private static final String UT_ID_EOT_003 = "UT-POS-TRX-EOT-003";
    private static final String UT_ID_EOT_004 = "UT-POS-TRX-EOT-004";

    private PosTrxSequenceRepository repo;
    private PosTrxService service;

    // repo/service 공통으로 세팅
    @BeforeEach
    void setUp() {
        repo = mock(PosTrxSequenceRepository.class);
        service = new PosTrxServiceImpl(repo);
    }

    /**
     * [UT_ID] UT-POS-TRX-EOT-001
     * <p>
     * [시나리오]
     * - Given: storeCd/bizDate/posNo가 정상으로 들어온다
     * - When : eot() 호출
     * - Then : 응답에 next_pos_trx(포스TR) 문자열이 규격대로 조립되어 내려온다
     * - And  : 포스TR 마지막 4자리는 repo.nextSeq(...) 기반으로 0패딩되어 반영된다(예: 0022)
     */
    @Test
    @DisplayName("[" + UT_ID_EOT_001 + "] EOT 호출 시 다음 포스TR 발급 성공")
    void eotTest1() {
        // given
        when(repo.nextSeq("2301", "20260121", "9999"))
                .thenReturn(22L);

        PosTrxEotRequest req = new PosTrxEotRequest("2301", "20260121", "9999");

        // when
        PosTrxEotResponse res = service.eot(req);

        // then
        assertEquals("2301-20260121-9999-0022", res.getNextPosTrx());
        verify(repo, times(1)).nextSeq("2301", "20260121", "9999");
    }

    /**
     * [UT_ID] UT-POS-TRX-EOT-002
     * <p>
     * [시나리오]
     * - Given: storeCd/bizDate/posNo 중 하나가 null 또는 blank로 들어온다
     * - When : eot() 호출
     * - Then : 서비스는 IllegalArgumentException을 던진다(입력 검증 실패)
     * - And  : 입력 검증에서 차단되므로 repo.nextSeq(...)는 호출되지 않는다
     */
    @Test
    @DisplayName("[" + UT_ID_EOT_002 + "] 필수값 누락: storeCd/bizDate/posNo 중 하나가 null/blank")
    void eotTest2() {
        // given
        PosTrxEotRequest req = new PosTrxEotRequest("  ", "20260121", "9999");

        // when + then
        assertThrows(IllegalArgumentException.class, () -> service.eot(req));
        verifyNoInteractions(repo);
    }

    /**
     * [UT_ID] UT-POS-TRX-EOT-003
     * <p>
     * [시나리오]
     * - Given: repo.nextSeq(...) 호출 시 저장소 오류(예: DB 락/장애)로 RuntimeException이 발생한다
     * - When : eot() 호출
     * - Then : 서비스는 해당 예외를 상위로 전파한다
     * - And  : repo.nextSeq(...)는 정확히 1회 호출된다
     */
    @Test
    @DisplayName("[" + UT_ID_EOT_003 + "] 레포지토리가 예외를 리턴 했을 경우")
    void eotTest3() {
        // given
        when(repo.nextSeq("2301", "20260121", "9999"))
                .thenThrow(new RuntimeException("DB 락 다운"));

        PosTrxEotRequest req = new PosTrxEotRequest("2301", "20260121", "9999");

        // when + then
        assertThrows(RuntimeException.class, () -> service.eot(req));
        verify(repo, times(1)).nextSeq("2301", "20260121", "9999");
    }

    /**
     * [UT_ID] UT-POS-TRX-EOT-004
     * <p>
     * [시나리오]
     * - Given: repo.nextSeq(...) 결과가 포스TR 규격 범위를 벗어난 값이다(예: -1 또는 10000 이상 등)
     * - When : eot() 호출
     * - Then : 서비스는 IllegalStateException을 던진다(규격/정합성 검증 실패)
     * - And  : repo.nextSeq(...)는 1회 호출되며, 서비스 레이어에서 결과값을 검증 후 예외 처리한다
     */
    @Test
    @DisplayName("[" + UT_ID_EOT_004 + "] 포스 TR 규격(0001~9999) 초과 시")
    void eotTest4() {
        // given
        when(repo.nextSeq("2301", "20260121", "9999"))
                .thenReturn(-1L);

        PosTrxEotRequest req = new PosTrxEotRequest("2301", "20260121", "9999");

        // when + then
        assertThrows(IllegalStateException.class, () -> service.eot(req));
        verify(repo, times(1)).nextSeq("2301", "20260121", "9999");
    }

}