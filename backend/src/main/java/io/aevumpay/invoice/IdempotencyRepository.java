package io.aevumpay.invoice;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface IdempotencyRepository extends JpaRepository<IdempotencyRecord, Long> {

    Optional<IdempotencyRecord> findByMerchantIdAndIdemKey(Long merchantId, String idemKey);
}
