package com.chaeyeongmin.payment_sim.api.payment.service.support;

import com.chaeyeongmin.payment_sim.van.client.dto.enums.VanDeclineCode;

public final class VanDeclineCodeMapper {

    private VanDeclineCodeMapper() {
    }

    public static String toCode(VanDeclineCode declineCode) {
        if (declineCode == null) {
            return null;
        }
        return declineCode.code();
    }

}
