package io.aevumpay.webhook;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface WebhookRepository extends JpaRepository<WebhookOutbox, Long> {

    List<WebhookOutbox> findTop20ByStatusAndNextAttemptAtLessThanEqualOrderByIdAsc(
            WebhookStatus status, Instant now);

    void deleteByChainEventIdInAndStatus(Collection<Long> chainEventIds, WebhookStatus status);

    boolean existsByChainEventIdInAndStatus(Collection<Long> chainEventIds, WebhookStatus status);

    List<WebhookOutbox> findByMerchantIdOrderByIdDesc(Long merchantId);
}
