package io.aevumpay.config;

import io.aevumpay.common.ApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.web3j.crypto.Credentials;
import org.web3j.crypto.WalletUtils;
import org.web3j.protocol.Web3j;
import org.web3j.protocol.http.HttpService;

@Configuration
public class ChainBeans {

    private static final Logger log = LoggerFactory.getLogger(ChainBeans.class);

    @Bean(destroyMethod = "shutdown")
    public Web3j web3j(AevumProperties properties) {
        return Web3j.build(new HttpService(properties.getRpcUrl()));
    }

    @Bean
    public OperatorCredentials operatorCredentials(AevumProperties properties) {
        try {
            // 环境变量直接读一遍。Spring 对带连字符的属性做松散绑定时，
            // 有时不会把 AEVUM_OPERATOR_PRIVATE_KEY 填进 operator-private-key。
            String key = first(properties.getOperatorPrivateKey(), System.getenv("AEVUM_OPERATOR_PRIVATE_KEY"));
            if (!key.isBlank()) {
                String trimmed = key.trim();
                if (trimmed.startsWith("0x") || trimmed.startsWith("0X")) {
                    trimmed = trimmed.substring(2);
                }
                return new OperatorCredentials(Credentials.create(trimmed));
            }
            String keystore = first(properties.getOperatorKeystore(), System.getenv("AEVUM_OPERATOR_KEYSTORE"));
            if (!keystore.isBlank()) {
                String password = first(
                        properties.getOperatorKeystorePassword(), System.getenv("AEVUM_OPERATOR_KEYSTORE_PASSWORD"));
                return new OperatorCredentials(WalletUtils.loadCredentials(password, keystore));
            }
            return new OperatorCredentials(null);
        } catch (Exception exception) {
            throw ApiException.unavailable("operator credentials could not be loaded");
        }
    }

    private static String first(String preferred, String fallback) {
        if (preferred != null && !preferred.isBlank()) {
            return preferred;
        }
        return fallback == null ? "" : fallback;
    }

    @Bean
    public ApplicationRunner startupLog(AevumProperties properties, OperatorCredentials operatorCredentials) {
        return args -> log.info(
                "Aevum Pay ready chainId={} contract={} operator={} rpc={}",
                properties.getChainId(),
                blank(properties.getContractAddress()) ? "unset" : properties.getContractAddress(),
                operatorCredentials.address() == null ? "none" : operatorCredentials.address(),
                properties.getRpcUrl());
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    /** 总是有这个 bean。credentials 为空表示只索引、不发交易。 */
    public record OperatorCredentials(Credentials credentials) {
        public String address() {
            return credentials == null ? null : credentials.getAddress();
        }
    }
}
