package com.chaeyeongmin.van_sim.transaction.application.approval.service;

import com.chaeyeongmin.van_sim.transaction.application.approval.command.ApprovalCommand;
import com.chaeyeongmin.van_sim.transaction.application.approval.result.ApprovalResult;

/**
 * VAN 승인 업무 처리 유스케이스의 진입 계약이다.
 */
public interface ApprovalService {

    ApprovalResult processApproval(ApprovalCommand command);
}
