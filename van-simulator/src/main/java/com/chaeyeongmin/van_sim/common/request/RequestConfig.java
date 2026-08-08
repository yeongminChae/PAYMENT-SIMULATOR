package com.chaeyeongmin.van_sim.common.request;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RequestConfig {

    @Bean
    public RequestIdFilter requestIdFilter(RequestIdProvider requestIdProvider) {
        return new RequestIdFilter(requestIdProvider);
    }
}
