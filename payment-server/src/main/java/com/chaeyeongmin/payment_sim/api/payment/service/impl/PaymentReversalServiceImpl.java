package com.chaeyeongmin.payment_sim.api.payment.service.impl;

import com.chaeyeongmin.payment_sim.api.payment.dto.request.ReversalRequest;
import com.chaeyeongmin.payment_sim.api.payment.dto.response.ReversalResponse;
import com.chaeyeongmin.payment_sim.api.payment.service.PaymentReversalService;
import com.chaeyeongmin.payment_sim.api.payment.service.transaction.PaymentReversalTransactionService;
import com.chaeyeongmin.payment_sim.api.payment.service.transaction.model.PaymentReversalPrepareResult;
import com.chaeyeongmin.payment_sim.van.client.assembler.VanReversalAssembler;
import com.chaeyeongmin.payment_sim.van.client.dto.VanReversalRequest;
import com.chaeyeongmin.payment_sim.van.client.dto.VanReversalResponse;
import com.chaeyeongmin.payment_sim.van.gateway.VanGateway;
import com.chaeyeongmin.payment_sim.van.gateway.exception.VanGatewayRequestNotSentException;
import com.chaeyeongmin.payment_sim.van.gateway.exception.VanGatewayTimeoutException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class PaymentReversalServiceImpl implements PaymentReversalService {

    private final PaymentReversalTransactionService transactionService;
    private final VanGateway vanGateway;
    private final VanReversalAssembler vanReversalAssembler;

    @Override
    public ReversalResponse reversal(ReversalRequest request) {
        PaymentReversalPrepareResult prepared = transactionService.prepare(request);
        if (prepared.isCompleted()) return prepared.completedResponse();

        VanReversalRequest vanRequest = vanReversalAssembler.assemble(
                prepared.reversalPosTrx(),
                prepared.originalPosTrx(),
                prepared.originalAttemptSeq(),
                prepared.originalAttempt()
        );

        final VanReversalResponse vanResponse;
        try {
            vanResponse = vanGateway.reversal(vanRequest);

        } catch (VanGatewayRequestNotSentException e) {
            return transactionService.cleanupPendingAndRetryLater(prepared);

        } catch (VanGatewayTimeoutException e) {
            return ReversalResponse.retryLater(
                    prepared.reversalPosTrx(),
                    prepared.originalPosTrx(),
                    prepared.originalAttemptSeq()
            );
        }

        return transactionService.finalizeReversal(prepared, vanResponse);
    }
}
