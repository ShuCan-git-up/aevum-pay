package io.aevumpay.merchant;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MerchantRepository extends JpaRepository<MerchantEntity, Long> {

    Optional<MerchantEntity> findByWalletAddress(String walletAddress);

    Optional<MerchantEntity> findByApiKeyHash(String apiKeyHash);
}
