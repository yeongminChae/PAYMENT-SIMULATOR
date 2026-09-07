package com.chaeyeongmin.payment_sim.infra.mybatis.mapper;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class PaymentCancelMapperXmlTest {

    @Test
    void cancel_inquiry_복구_update는_UNKNOWN_TIMEOUT_전용_WHERE_조건을_사용한다() throws IOException {
        String xml = mapperXml();

        assertThat(xml).contains("<select id=\"updateUnknownTimeoutToFinal\"");
        assertThat(xml).contains("WHERE CURRENT_TRX_NO = #{cancel.posTrx}");
        assertThat(xml).contains("AND ORIGINAL_TRX_NO = #{cancel.originalPosTrx}");
        assertThat(xml).contains("AND ORIGINAL_ATTEMPT_SEQ = #{cancel.originalAttemptSeq}");
        assertThat(xml).contains("AND CANCEL_STATUS = 'UNKNOWN_TIMEOUT'");
        assertThat(xml).contains("RETURNING");
    }

    private static String mapperXml() throws IOException {
        try (InputStream inputStream = PaymentCancelMapperXmlTest.class
                .getClassLoader()
                .getResourceAsStream("mybatis/mapper/PaymentCancelMapper.xml")) {
            assertThat(inputStream).isNotNull();
            return new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
