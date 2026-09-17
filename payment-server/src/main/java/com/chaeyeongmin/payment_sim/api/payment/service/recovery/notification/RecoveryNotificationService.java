package com.chaeyeongmin.payment_sim.api.payment.service.recovery.notification;

/**
 * Recovery 처리 결과를 운영자에게 알리는 공통 통로다.
 * Worker는 로그, Slack 같은 실제 알림 방법을 몰라도 이 인터페이스를 통해 알림을 요청할 수 있다.
 */
public interface RecoveryNotificationService {

    /**
     * 자동으로 복구하지 못해 운영자 확인이 필요한 Task 정보를 알린다.
     *
     * @param notification 운영자가 확인할 Task와 실패 원인 정보
     */
    void notifyManualReview(RecoveryManualReviewNotification notification);
}
