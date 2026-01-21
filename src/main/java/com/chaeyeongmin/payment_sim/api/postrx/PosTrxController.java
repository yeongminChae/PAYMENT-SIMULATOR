package com.chaeyeongmin.payment_sim.api.postrx;

import com.chaeyeongmin.payment_sim.api.postrx.dto.PosTrxEotRequest;
import com.chaeyeongmin.payment_sim.api.postrx.dto.PosTrxEotResponse;
import com.chaeyeongmin.payment_sim.api.postrx.dto.PosTrxIssueRequest;
import com.chaeyeongmin.payment_sim.api.postrx.dto.PosTrxIssueResponse;
import com.chaeyeongmin.payment_sim.api.postrx.service.PosTrxService;
import com.chaeyeongmin.payment_sim.common.api.ApiResponse;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/pos-trx")
public class PosTrxController {

    private final PosTrxService posTrxService;

    public PosTrxController(PosTrxService posTrxService) {
        this.posTrxService = posTrxService;
    }

    @PostMapping("/issue")
    public ApiResponse<PosTrxIssueResponse> issue(@RequestBody PosTrxIssueRequest request) {
        return ApiResponse.ok(posTrxService.issue(request));
    }

    @PostMapping("/eot")
    public ApiResponse<PosTrxEotResponse> eot(@RequestBody PosTrxEotRequest request) {
        return ApiResponse.ok(posTrxService.eot(request));
    }
}