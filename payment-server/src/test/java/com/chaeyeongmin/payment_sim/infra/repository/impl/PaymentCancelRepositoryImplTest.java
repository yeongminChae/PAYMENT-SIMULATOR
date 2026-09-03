package com.chaeyeongmin.payment_sim.infra.repository.impl;

import com.chaeyeongmin.payment_sim.domain.model.PaymentCancel;
import com.chaeyeongmin.payment_sim.domain.policy.CancelStatus;
import com.chaeyeongmin.payment_sim.infra.mybatis.mapper.PaymentCancelMapper;
import com.chaeyeongmin.payment_sim.infra.repository.dto.CancelResultUpdateParam;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PaymentCancelRepositoryImplTest {

    @Test
    void updateUnknownTimeoutToFinal을_mapper에_위임한다() {
        PaymentCancelMapper mapper = mock(PaymentCancelMapper.class);
        PaymentCancelRepositoryImpl repository = new PaymentCancelRepositoryImpl(mapper);
        CancelResultUpdateParam param = CancelResultUpdateParam.cancelled(
                "2301-20260808-9999-0002",
                "2301-20260808-9999-0001",
                1,
                "VAN-CANCEL-001",
                "CANCEL-APPROVAL-001"
        );
        PaymentCancel updated = new PaymentCancel(
                param.posTrx(),
                param.originalPosTrx(),
                param.originalAttemptSeq(),
                CancelStatus.CANCELLED,
                param.vanCancelTrxId(),
                param.cancelApprovalNo(),
                null
        );
        when(mapper.updateUnknownTimeoutToFinal(param)).thenReturn(Optional.of(updated));

        Optional<PaymentCancel> result = repository.updateUnknownTimeoutToFinal(param);

        assertThat(result).contains(updated);
        verify(mapper).updateUnknownTimeoutToFinal(param);
    }
}
