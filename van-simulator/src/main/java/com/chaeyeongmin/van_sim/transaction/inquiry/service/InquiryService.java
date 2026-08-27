package com.chaeyeongmin.van_sim.transaction.inquiry.service;

import com.chaeyeongmin.van_sim.transaction.inquiry.service.result.InquiryResult;

/**
 * VAN 승인 원장을 조회하는 Inquiry 유스케이스의 진입 계약이다.
 */
public interface InquiryService {

    InquiryResult inquire(String posTrx, int attemptSeq);
}
