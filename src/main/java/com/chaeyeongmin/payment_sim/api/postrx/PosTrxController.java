package com.chaeyeongmin.payment_sim.api.postrx;

import com.chaeyeongmin.payment_sim.api.postrx.dto.PosTrxEotRequest;
import com.chaeyeongmin.payment_sim.api.postrx.dto.PosTrxEotResponse;
import com.chaeyeongmin.payment_sim.api.postrx.dto.PosTrxIssueRequest;
import com.chaeyeongmin.payment_sim.api.postrx.dto.PosTrxIssueResponse;
import com.chaeyeongmin.payment_sim.api.postrx.service.PosTrxService;
import com.chaeyeongmin.payment_sim.common.api.ApiResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * PosTrxController
 * <p>
 * [역할]
 * - POS 단말이 사용할 "포스TR(거래번호)" 관련 API 엔드포인트를 제공하는 Web 계층(Controller).
 * - 요청 DTO를 받아 Service로 위임하고, 공통 응답 포맷(ApiResponse)으로 감싸서 반환한다.
 * <p>
 * [책임 범위]
 * - HTTP 요청/응답 처리 (URL, Method, Body 매핑)
 * - 입력값 검증/인증/인가/로깅 등 Web 관심사(필요 시)
 * - 비즈니스 로직은 PosTrxService로 위임 (Controller에 로직 두지 않기)
 */
@RestController
@RequestMapping("/api/v1/pos-trx")
@Slf4j
public class PosTrxController {

    private final PosTrxService posTrxService;

    public PosTrxController(PosTrxService posTrxService) {
        this.posTrxService = posTrxService;
    }

    /**
     * [역할]
     * - 포스TR(거래번호) 발급 요청 API.
     */
    @PostMapping("/issue")
    public ApiResponse<PosTrxIssueResponse> issue(@RequestBody PosTrxIssueRequest request) {
        return ApiResponse.ok(posTrxService.issue(request));
    }

    /**
     * [역할]
     * - EOT(End Of Transaction) 시점에 "다음 포스TR(거래번호)"를 발급하는 API.
     */
    @PostMapping("/eot")
    public ApiResponse<PosTrxEotResponse> eot(@RequestBody PosTrxEotRequest request) {

        // 요청 로깅
        log.info("[EOT] req storeCd={} bizDate={} posNo={}",
                request.getStoreCd(), request.getBizDate(), request.getPosNo());

        PosTrxEotResponse res = posTrxService.eot(request);

        // 응답 로깅
        log.info("[EOT] res nextPosTrx={}", res.getNextPosTrx());

        return ApiResponse.ok(posTrxService.eot(request));
    }
}