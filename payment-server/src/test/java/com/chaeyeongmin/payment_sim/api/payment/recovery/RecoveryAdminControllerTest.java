package com.chaeyeongmin.payment_sim.api.payment.recovery;

import com.chaeyeongmin.payment_sim.api.payment.recovery.dto.RecoveryHistoryResponse;
import com.chaeyeongmin.payment_sim.api.payment.recovery.dto.RecoveryTaskDetailResponse;
import com.chaeyeongmin.payment_sim.api.payment.recovery.dto.RecoveryTaskSummaryResponse;
import com.chaeyeongmin.payment_sim.api.payment.recovery.exception.RecoveryTaskNotFoundException;
import com.chaeyeongmin.payment_sim.api.payment.service.recovery.admin.RecoveryAdminQueryService;
import com.chaeyeongmin.payment_sim.common.exception.GlobalExceptionHandler;
import com.chaeyeongmin.payment_sim.domain.policy.RecoveryStatus;
import com.chaeyeongmin.payment_sim.domain.policy.RecoveryTargetType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class RecoveryAdminControllerTest {

    private static final Long TASK_ID = 51L;
    private static final LocalDateTime CREATED_AT = LocalDateTime.of(2026, 9, 17, 9, 0);
    private static final LocalDateTime UPDATED_AT = LocalDateTime.of(2026, 9, 17, 10, 0);

    private RecoveryAdminQueryService queryService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        queryService = mock(RecoveryAdminQueryService.class);
        mockMvc = MockMvcBuilders
                .standaloneSetup(new RecoveryAdminController(queryService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void manualReview목록조회는_200과summary목록을반환한다() throws Exception {
        RecoveryTaskSummaryResponse summary = new RecoveryTaskSummaryResponse(
                TASK_ID,
                RecoveryTargetType.CANCEL,
                "ADMIN-CANCEL-1",
                null,
                "ADMIN-ORIGINAL-1",
                1,
                RecoveryStatus.MANUAL_REVIEW,
                2,
                null,
                CREATED_AT,
                UPDATED_AT
        );
        when(queryService.findTasksByStatus(RecoveryStatus.MANUAL_REVIEW))
                .thenReturn(List.of(summary));

        mockMvc.perform(get("/api/admin/recovery/tasks")
                        .param("status", "MANUAL_REVIEW"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result_code").value("OK"))
                .andExpect(jsonPath("$.data[0].taskId").value(TASK_ID))
                .andExpect(jsonPath("$.data[0].targetType").value("CANCEL"))
                .andExpect(jsonPath("$.data[0].recoveryStatus").value("MANUAL_REVIEW"));

        verify(queryService).findTasksByStatus(RecoveryStatus.MANUAL_REVIEW);
    }

    @Test
    void task상세조회는_200과history목록을반환한다() throws Exception {
        RecoveryHistoryResponse history = new RecoveryHistoryResponse(
                501L,
                1,
                "MANUAL_REVIEW",
                "RETRY_EXHAUSTED",
                CREATED_AT,
                UPDATED_AT
        );
        RecoveryTaskDetailResponse detail = new RecoveryTaskDetailResponse(
                TASK_ID,
                RecoveryTargetType.REVERSAL,
                "ADMIN-REVERSAL-1",
                null,
                "ADMIN-ORIGINAL-1",
                1,
                RecoveryStatus.MANUAL_REVIEW,
                2,
                null,
                CREATED_AT,
                UPDATED_AT,
                List.of(history)
        );
        when(queryService.findTaskDetail(TASK_ID)).thenReturn(detail);

        mockMvc.perform(get("/api/admin/recovery/tasks/{taskId}", TASK_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result_code").value("OK"))
                .andExpect(jsonPath("$.data.taskId").value(TASK_ID))
                .andExpect(jsonPath("$.data.histories[0].historyId").value(501L))
                .andExpect(jsonPath("$.data.histories[0].tryNo").value(1));
    }

    @Test
    void 존재하지않는task상세조회는_404를반환한다() throws Exception {
        when(queryService.findTaskDetail(TASK_ID))
                .thenThrow(new RecoveryTaskNotFoundException(TASK_ID));

        mockMvc.perform(get("/api/admin/recovery/tasks/{taskId}", TASK_ID))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.result_code").value("NOT_FOUND"))
                .andExpect(jsonPath("$.message").value("RECOVERY_TASK_NOT_FOUND"))
                .andExpect(jsonPath("$.data").doesNotExist());
    }
}
