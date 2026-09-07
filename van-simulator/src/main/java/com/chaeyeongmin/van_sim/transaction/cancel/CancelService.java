package com.chaeyeongmin.van_sim.transaction.cancel;

import com.chaeyeongmin.van_sim.transaction.cancel.service.command.CancelCommand;
import com.chaeyeongmin.van_sim.transaction.cancel.service.result.CancelResult;

/**
 * VAN 취소 유스케이스 진입점이다.
 *
 * <p>
 * 구현체는 원승인 원장 조회, 중복 취소 방지, 취소 원장 저장까지 수행한다.
 * 현재 PostgreSQL 구현에서는 같은 원승인에 대한 동시 취소를 원승인 row lock으로 직렬화한다.
 */
public interface CancelService {

    /**
     * 단일 전체취소 요청을 처리한다.
     *
     * <p>
     * 같은 cancelPosTrx 재요청은 저장된 결과를 재사용하고,
     * 같은 원승인에 다른 cancelPosTrx가 들어오면 기존 취소 결과를 ALREADY_CANCELLED로 돌려준다.
     */
    CancelResult processCancel(CancelCommand command);
}
