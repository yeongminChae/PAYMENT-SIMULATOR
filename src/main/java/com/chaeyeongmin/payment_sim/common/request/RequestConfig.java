package com.chaeyeongmin.payment_sim.common.request;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * [Config]
 * - RequestIdFilter를 스프링 빈으로 등록한다.
 */
@Configuration
public class RequestConfig {

    @Bean
    public RequestIdFilter requestIdFilter(RequestIdProvider requestIdProvider) {
        return new RequestIdFilter(requestIdProvider);
    }
}