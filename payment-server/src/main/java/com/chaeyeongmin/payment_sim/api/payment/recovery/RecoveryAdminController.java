package com.chaeyeongmin.payment_sim.api.payment.recovery;

import com.chaeyeongmin.payment_sim.api.payment.recovery.dto.RecoveryTaskDetailResponse;
import com.chaeyeongmin.payment_sim.api.payment.recovery.dto.RecoveryTaskSummaryResponse;
import com.chaeyeongmin.payment_sim.api.payment.service.recovery.admin.RecoveryAdminCommandService;
import com.chaeyeongmin.payment_sim.api.payment.service.recovery.admin.RecoveryAdminQueryService;
import com.chaeyeongmin.payment_sim.common.api.ApiResponse;
import com.chaeyeongmin.payment_sim.domain.policy.RecoveryStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/admin/recovery/tasks")
public class RecoveryAdminController {

    private final RecoveryAdminQueryService recoveryAdminQueryService;
    private final RecoveryAdminCommandService recoveryAdminCommandService;

    @GetMapping
    public ApiResponse<List<RecoveryTaskSummaryResponse>> findTasks(
            @RequestParam RecoveryStatus status
    ) {
        return ApiResponse.ok(recoveryAdminQueryService.findTasksByStatus(status));
    }

    @GetMapping("/{taskId}")
    public ApiResponse<RecoveryTaskDetailResponse> findTaskDetail(
            @PathVariable Long taskId
    ) {
        return ApiResponse.ok(recoveryAdminQueryService.findTaskDetail(taskId));
    }

    /** 운영자가 MANUAL_REVIEW Task를 다시 자동 복구 대기 상태로 보낸다. */
    @PostMapping("/{taskId}/requeue")
    public ApiResponse<RecoveryTaskSummaryResponse> requeueTask(
            @PathVariable Long taskId
    ) {
        return ApiResponse.ok(recoveryAdminCommandService.requeue(taskId));
    }
}
