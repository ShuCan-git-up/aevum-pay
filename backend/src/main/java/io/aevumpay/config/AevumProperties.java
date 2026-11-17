package io.aevumpay.config;

import java.util.Arrays;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "aevum")
public class AevumProperties {

    private String rpcUrl = "https://rpc-test.qday.info";
    /** 浏览器钱包看到的 RPC。空则与 rpcUrl 相同。 */
    private String publicRpcUrl = "";
    private long chainId = 44_005L;
    private String contractAddress = "";
    private String tokenAddress = "0x456569838f1d43009A91ebA4263147Afd7233483";
    private int tokenDecimals = 6;
    private String explorerUrl = "https://explorer-test.qday.info";
    private long startBlock;
    private int confirmations = 2;
    private long pollIntervalMs = 3_000L;
    private int logBatchBlocks = 2_000;
    private int reorgDepth = 10;
    private String operatorPrivateKey = "";
    private String operatorKeystore = "";
    private String operatorKeystorePassword = "";
    private String publicBaseUrl = "http://localhost:8080";
    private long webhookDispatchIntervalMs = 2_000L;
    private String webhookRetrySeconds = "60,300,1800,7200,43200";
    private long reconcileIntervalMs = 600_000L;
    private int txReceiptAttempts = 40;
    private long txReceiptPollMs = 1_000L;

    public String getRpcUrl() {
        return rpcUrl;
    }

    public void setRpcUrl(String rpcUrl) {
        this.rpcUrl = rpcUrl;
    }

    public String getPublicRpcUrl() {
        return publicRpcUrl;
    }

    public void setPublicRpcUrl(String publicRpcUrl) {
        this.publicRpcUrl = publicRpcUrl;
    }

    public long getChainId() {
        return chainId;
    }

    public void setChainId(long chainId) {
        this.chainId = chainId;
    }

    public String getContractAddress() {
        return contractAddress;
    }

    public void setContractAddress(String contractAddress) {
        this.contractAddress = contractAddress;
    }

    public String getTokenAddress() {
        return tokenAddress;
    }

    public void setTokenAddress(String tokenAddress) {
        this.tokenAddress = tokenAddress;
    }

    public int getTokenDecimals() {
        return tokenDecimals;
    }

    public void setTokenDecimals(int tokenDecimals) {
        this.tokenDecimals = tokenDecimals;
    }

    public String getExplorerUrl() {
        return explorerUrl;
    }

    public void setExplorerUrl(String explorerUrl) {
        this.explorerUrl = explorerUrl;
    }

    public long getStartBlock() {
        return startBlock;
    }

    public void setStartBlock(long startBlock) {
        this.startBlock = startBlock;
    }

    public int getConfirmations() {
        return confirmations;
    }

    public void setConfirmations(int confirmations) {
        this.confirmations = confirmations;
    }

    public long getPollIntervalMs() {
        return pollIntervalMs;
    }

    public void setPollIntervalMs(long pollIntervalMs) {
        this.pollIntervalMs = pollIntervalMs;
    }

    public int getLogBatchBlocks() {
        return logBatchBlocks;
    }

    public void setLogBatchBlocks(int logBatchBlocks) {
        this.logBatchBlocks = logBatchBlocks;
    }

    public int getReorgDepth() {
        return reorgDepth;
    }

    public void setReorgDepth(int reorgDepth) {
        this.reorgDepth = reorgDepth;
    }

    public String getOperatorPrivateKey() {
        return operatorPrivateKey;
    }

    public void setOperatorPrivateKey(String operatorPrivateKey) {
        this.operatorPrivateKey = operatorPrivateKey;
    }

    public String getOperatorKeystore() {
        return operatorKeystore;
    }

    public void setOperatorKeystore(String operatorKeystore) {
        this.operatorKeystore = operatorKeystore;
    }

    public String getOperatorKeystorePassword() {
        return operatorKeystorePassword;
    }

    public void setOperatorKeystorePassword(String operatorKeystorePassword) {
        this.operatorKeystorePassword = operatorKeystorePassword;
    }

    public String getPublicBaseUrl() {
        return publicBaseUrl;
    }

    public void setPublicBaseUrl(String publicBaseUrl) {
        this.publicBaseUrl = publicBaseUrl;
    }

    public long getWebhookDispatchIntervalMs() {
        return webhookDispatchIntervalMs;
    }

    public void setWebhookDispatchIntervalMs(long webhookDispatchIntervalMs) {
        this.webhookDispatchIntervalMs = webhookDispatchIntervalMs;
    }

    public String getWebhookRetrySeconds() {
        return webhookRetrySeconds;
    }

    public void setWebhookRetrySeconds(String webhookRetrySeconds) {
        this.webhookRetrySeconds = webhookRetrySeconds;
    }

    public long getReconcileIntervalMs() {
        return reconcileIntervalMs;
    }

    public void setReconcileIntervalMs(long reconcileIntervalMs) {
        this.reconcileIntervalMs = reconcileIntervalMs;
    }

    public int getTxReceiptAttempts() {
        return txReceiptAttempts;
    }

    public void setTxReceiptAttempts(int txReceiptAttempts) {
        this.txReceiptAttempts = txReceiptAttempts;
    }

    public long getTxReceiptPollMs() {
        return txReceiptPollMs;
    }

    public void setTxReceiptPollMs(long txReceiptPollMs) {
        this.txReceiptPollMs = txReceiptPollMs;
    }

    public String browserRpcUrl() {
        if (publicRpcUrl == null || publicRpcUrl.isBlank()) {
            return rpcUrl;
        }
        return publicRpcUrl;
    }

    public boolean contractConfigured() {
        return contractAddress != null && !contractAddress.isBlank();
    }

    public List<Integer> retryScheduleSeconds() {
        return Arrays.stream(webhookRetrySeconds.split(","))
                .map(String::trim)
                .filter(part -> !part.isEmpty())
                .map(Integer::parseInt)
                .toList();
    }
}
