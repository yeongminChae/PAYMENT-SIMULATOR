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

/** 운영자가 Recovery Task를 조회하고 MANUAL_REVIEW Task를 다시 실행하도록 요청하는 API다. */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/admin/recovery/tasks")
public class RecoveryAdminController {

    private final RecoveryAdminQueryService recoveryAdminQueryService;
    private final RecoveryAdminCommandService recoveryAdminCommandService;

    /** 요청한 상태의 Recovery Task 목록을 최근 변경된 순서로 반환한다. */
    @GetMapping
    public ApiResponse<List<RecoveryTaskSummaryResponse>> findTasks(
            @RequestParam RecoveryStatus status
    ) {
        return ApiResponse.ok(recoveryAdminQueryService.findTasksByStatus(status));
    }

    /** Recovery Task 한 건과 지금까지의 실행 이력을 함께 반환한다. */
    @GetMapping("/{taskId}")
    public ApiResponse<RecoveryTaskDetailResponse> findTaskDetail(
            @PathVariable Long taskId
    ) {
        return ApiResponse.ok(recoveryAdminQueryService.findTaskDetail(taskId));
    }

    /** 운영자가 MANUAL_REVIEW Task를 다시 자동 복구 대상인 PENDING 상태로 보낸다. */
    @PostMapping("/{taskId}/requeue")
    public ApiResponse<RecoveryTaskSummaryResponse> requeueTask(
            @PathVariable Long taskId
    ) {
        return ApiResponse.ok(recoveryAdminCommandService.requeue(taskId));
    }
}
