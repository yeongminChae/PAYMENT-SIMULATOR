package com.chaeyeongmin.payment_sim.van.client.dto;

/**
 * VAN Inquiry 원장 조회 결과 코드.
 *
 * <p>
 * NOT_FOUND는 원장 row 자체가 없다는 뜻이고, UNKNOWN은 별도의 status 값이다.
 * 즉 SUCCESS + UNKNOWN은 승인 row가 존재하지만 VAN 상태가 미확정인 경우이고,
 * NOT_FOUND는 조회 대상 approval/cancel row를 찾지 못한 경우다.
 */
public enum VanInquiryResultCode {
    SUCCESS,
    NOT_FOUND
}
