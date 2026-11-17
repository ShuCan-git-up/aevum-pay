package io.aevumpay.invoice;

import io.aevumpay.merchant.MerchantEntity;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface InvoiceRepository extends JpaRepository<InvoiceEntity, Long> {

    Optional<InvoiceEntity> findByChainInvoiceId(Long chainInvoiceId);

    Optional<InvoiceEntity> findByCreateTxHash(String createTxHash);

    Optional<InvoiceEntity> findByMerchantAndOrderRef(MerchantEntity merchant, String orderRef);

    boolean existsByMerchantAndOrderRef(MerchantEntity merchant, String orderRef);

    List<InvoiceEntity> findByMerchantOrderByIdDesc(MerchantEntity merchant);

    List<InvoiceEntity> findByMerchant_WalletAddressOrderByIdDesc(String walletAddress);

    List<InvoiceEntity> findByStatusIn(Collection<InvoiceStatus> statuses);
}
