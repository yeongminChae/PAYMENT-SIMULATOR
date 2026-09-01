package com.chaeyeongmin.van_sim.transaction.cancel;

import com.chaeyeongmin.van_sim.transaction.cancel.service.command.CancelCommand;
import com.chaeyeongmin.van_sim.transaction.cancel.service.result.CancelResult;

public interface CancelService {
    CancelResult processCancel(CancelCommand command);
}
