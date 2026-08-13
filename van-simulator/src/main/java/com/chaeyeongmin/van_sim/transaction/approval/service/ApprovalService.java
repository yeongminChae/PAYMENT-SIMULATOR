package com.chaeyeongmin.van_sim.transaction.approval.service;

import com.chaeyeongmin.van_sim.transaction.approval.service.command.ApprovalCommand;
import com.chaeyeongmin.van_sim.transaction.approval.service.result.ApprovalResult;

/**
 * VAN 승인 업무 처리 유스케이스의 진입 계약이다.
 */
public interface ApprovalService {

    ApprovalResult processApproval(ApprovalCommand command);
}
