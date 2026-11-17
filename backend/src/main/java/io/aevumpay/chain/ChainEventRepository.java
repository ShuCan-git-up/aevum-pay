package io.aevumpay.chain;

import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ChainEventRepository extends JpaRepository<ChainEventEntity, Long> {

    boolean existsByTxHashAndLogIndex(String txHash, int logIndex);

    List<ChainEventEntity> findByBlockNumberGreaterThan(long blockNumber);

    List<ChainEventEntity> findByChainInvoiceIdOrderByBlockNumberAscLogIndexAsc(long chainInvoiceId);

    void deleteByIdIn(Collection<Long> ids);
}
