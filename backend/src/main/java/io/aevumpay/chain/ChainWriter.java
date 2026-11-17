package io.aevumpay.chain;

import io.aevumpay.common.ApiException;
import io.aevumpay.common.Eth;
import io.aevumpay.config.AevumProperties;
import io.aevumpay.config.ChainBeans.OperatorCredentials;
import java.math.BigInteger;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.web3j.abi.FunctionEncoder;
import org.web3j.abi.TypeReference;
import org.web3j.abi.datatypes.Address;
import org.web3j.abi.datatypes.Bool;
import org.web3j.abi.datatypes.Function;
import org.web3j.abi.datatypes.generated.Bytes32;
import org.web3j.abi.datatypes.generated.Uint256;
import org.web3j.abi.datatypes.generated.Uint64;
import org.web3j.crypto.Credentials;
import org.web3j.protocol.Web3j;
import org.web3j.protocol.core.methods.request.Transaction;
import org.web3j.protocol.core.methods.response.EthEstimateGas;
import org.web3j.protocol.core.methods.response.EthSendTransaction;
import org.web3j.protocol.core.methods.response.TransactionReceipt;
import org.web3j.tx.RawTransactionManager;
import org.web3j.tx.response.PollingTransactionReceiptProcessor;

@Service
public class ChainWriter {

    private static final Logger log = LoggerFactory.getLogger(ChainWriter.class);
    private static final BigInteger MIN_GAS_PRICE = BigInteger.valueOf(1_000_000_000L);

    private final Web3j web3j;
    private final AevumProperties properties;
    private final OperatorCredentials credentials;
    private RawTransactionManager transactions;

    public ChainWriter(Web3j web3j, AevumProperties properties, OperatorCredentials credentials) {
        this.web3j = web3j;
        this.properties = properties;
        this.credentials = credentials;
    }

    public String operatorAddress() {
        return credentials.address();
    }

    public boolean available() {
        return credentials.credentials() != null && properties.contractConfigured();
    }

    public SendResult createInvoiceFor(
            String merchant, String payer, BigInteger amount, long dueAt, boolean allowPartial, String orderRef) {
        String payerOrZero = payer == null ? Eth.ZERO_ADDRESS : payer;
        Function function = new Function(
                "createInvoiceFor",
                List.of(
                        new Address(merchant),
                        new Address(payerOrZero),
                        new Uint256(amount),
                        new Uint64(BigInteger.valueOf(dueAt)),
                        new Bool(allowPartial),
                        new Bytes32(Eth.bytes32(orderRef))),
                List.of(new TypeReference<Uint256>() {}));
        return send(function);
    }

    public SendResult cancel(long chainInvoiceId) {
        Function function = new Function(
                "cancelInvoice", List.of(new Uint256(BigInteger.valueOf(chainInvoiceId))), List.of());
        return send(function);
    }

    private synchronized SendResult send(Function function) {
        if (!available()) {
            throw ApiException.unavailable("operator key or contract address is not configured");
        }
        try {
            assertChain();
            Credentials creds = credentials.credentials();
            String data = FunctionEncoder.encode(function);
            BigInteger gasPrice = web3j.ethGasPrice().send().getGasPrice();
            if (gasPrice.compareTo(MIN_GAS_PRICE) < 0) {
                gasPrice = MIN_GAS_PRICE;
            }
            EthEstimateGas estimate = web3j.ethEstimateGas(
                            Transaction.createEthCallTransaction(creds.getAddress(), properties.getContractAddress(), data))
                    .send();
            BigInteger gasLimit = estimate.hasError()
                    ? BigInteger.valueOf(600_000L)
                    : estimate.getAmountUsed().multiply(BigInteger.valueOf(12)).divide(BigInteger.TEN).add(BigInteger.valueOf(30_000L));
            EthSendTransaction sent = manager().sendTransaction(
                    gasPrice, gasLimit, properties.getContractAddress(), data, BigInteger.ZERO);
            if (sent.hasError()) {
                throw ApiException.badGateway(sent.getError().getMessage());
            }
            String txHash = sent.getTransactionHash();
            TransactionReceipt receipt = new PollingTransactionReceiptProcessor(
                            web3j, properties.getTxReceiptPollMs(), properties.getTxReceiptAttempts())
                    .waitForTransactionReceipt(txHash);
            if (receipt.getStatus() == null || !receipt.getStatus().equals("0x1")) {
                throw ApiException.badGateway("transaction reverted: " + txHash);
            }
            Long chainId = null;
            if (receipt.getLogs() != null) {
                for (var logEntry : receipt.getLogs()) {
                    var decoded = EventDecoder.decode(logEntry);
                    if (decoded.isPresent() && "InvoiceCreated".equals(decoded.get().eventName())) {
                        chainId = decoded.get().chainInvoiceId();
                    }
                }
            }
            return new SendResult(txHash, chainId, true, null);
        } catch (ApiException exception) {
            throw exception;
        } catch (Exception exception) {
            log.warn("chain send failed: {}", exception.toString());
            throw ApiException.badGateway(exception.getMessage() == null ? "chain send failed" : exception.getMessage());
        }
    }

    private RawTransactionManager manager() {
        if (transactions == null) {
            transactions = new RawTransactionManager(web3j, credentials.credentials(), properties.getChainId());
        }
        return transactions;
    }

    private void assertChain() throws Exception {
        BigInteger remote = web3j.ethChainId().send().getChainId();
        if (remote.longValue() != properties.getChainId()) {
            throw ApiException.badGateway(
                    "RPC chain id " + remote + " does not match configured " + properties.getChainId());
        }
    }
}
