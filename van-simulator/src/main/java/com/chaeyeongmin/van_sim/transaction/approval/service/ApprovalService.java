package com.chaeyeongmin.van_sim.transaction.approval.service;

import com.chaeyeongmin.van_sim.transaction.approval.service.command.ApprovalCommand;
import com.chaeyeongmin.van_sim.transaction.approval.service.result.ApprovalResult;

public interface ApprovalService {

    ApprovalResult approve(ApprovalCommand command);
}
