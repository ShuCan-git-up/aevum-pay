package io.aevumpay.chain;

import io.aevumpay.common.Eth;
import io.aevumpay.config.AevumProperties;
import java.math.BigInteger;
import java.util.List;
import org.springframework.stereotype.Service;
import org.web3j.abi.FunctionEncoder;
import org.web3j.abi.TypeReference;
import org.web3j.abi.datatypes.Function;
import org.web3j.abi.datatypes.generated.Uint256;
import org.web3j.protocol.Web3j;
import org.web3j.protocol.core.DefaultBlockParameterName;
import org.web3j.protocol.core.methods.request.Transaction;
import org.web3j.protocol.core.methods.response.EthCall;
import org.web3j.utils.Numeric;

@Service
public class InvoiceReader {

    private final Web3j web3j;
    private final AevumProperties properties;

    public InvoiceReader(Web3j web3j, AevumProperties properties) {
        this.web3j = web3j;
        this.properties = properties;
    }

    public OnChainInvoice read(long chainInvoiceId) throws Exception {
        if (!properties.contractConfigured()) {
            throw new IllegalStateException("contract address is not set");
        }
        Function function = new Function(
                "getInvoice",
                List.of(new Uint256(BigInteger.valueOf(chainInvoiceId))),
                List.of(new TypeReference<Uint256>() {}));
        String data = FunctionEncoder.encode(function);
        EthCall call = web3j.ethCall(
                        Transaction.createEthCallTransaction(null, properties.getContractAddress(), data),
                        DefaultBlockParameterName.LATEST)
                .send();
        if (call.isReverted() || call.hasError() || call.getValue() == null || "0x".equals(call.getValue())) {
            String reason = call.getRevertReason() == null ? "invoice call reverted" : call.getRevertReason();
            throw new IllegalStateException(reason);
        }
        byte[] raw = Numeric.hexStringToByteArray(call.getValue());
        if (raw.length < 32 * 10) {
            throw new IllegalStateException("short getInvoice return");
        }
        String merchant = addressWord(raw, 0);
        long dueAt = word(raw, 1).longValueExact();
        boolean allowPartial = word(raw, 2).signum() != 0;
        int status = word(raw, 3).intValueExact();
        String payer = addressWord(raw, 4);
        long createdAt = word(raw, 5).longValueExact();
        BigInteger amount = word(raw, 6);
        BigInteger paid = word(raw, 7);
        BigInteger refunded = word(raw, 8);
        String orderRef = Eth.hex(java.util.Arrays.copyOfRange(raw, 9 * 32, 10 * 32));
        return new OnChainInvoice(merchant, dueAt, allowPartial, status, payer, createdAt, amount, paid, refunded, orderRef);
    }

    private static BigInteger word(byte[] raw, int index) {
        return new BigInteger(1, java.util.Arrays.copyOfRange(raw, index * 32, index * 32 + 32));
    }

    private static String addressWord(byte[] raw, int index) {
        byte[] word = java.util.Arrays.copyOfRange(raw, index * 32 + 12, index * 32 + 32);
        return Eth.hex(word);
    }
}
