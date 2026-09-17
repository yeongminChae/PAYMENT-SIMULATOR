package com.chaeyeongmin.payment_sim.api.payment.service.recovery.notification;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/** MANUAL_REVIEW 정보를 검색하기 쉬운 {@code key=value} 형태로 애플리케이션 로그에 남긴다. */
@Service
@Slf4j
public class LoggingRecoveryNotificationService implements RecoveryNotificationService {

    /**
     * 운영자가 Task와 실패 원인을 한 줄에서 확인할 수 있도록 WARN 로그를 기록한다.
     * Slack이나 Email 같은 외부 시스템은 호출하지 않는다.
     */
    @Override
    public void notifyManualReview(RecoveryManualReviewNotification notification) {
        // 고정된 event 이름과 각 필드의 key를 함께 남겨 로그 검색과 필터링이 쉽도록 한다.
        log.warn(
                "event=RECOVERY_MANUAL_REVIEW taskId={} targetType={} targetTrxNo={} "
                        + "targetAttemptSeq={} originalPosTrx={} originalAttemptSeq={} "
                        + "retryCount={} errorCode={} occurredAt={}",
                notification.taskId(),
                notification.targetType(),
                notification.targetTrxNo(),
                notification.targetAttemptSeq(),
                notification.originalPosTrx(),
                notification.originalAttemptSeq(),
                notification.retryCount(),
                notification.errorCode(),
                notification.occurredAt()
        );
    }
}
