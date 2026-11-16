package io.aevumpay.invoice;

import io.aevumpay.chain.ChainSyncService;
import io.aevumpay.chain.ChainWriter;
import io.aevumpay.common.Eth;
import io.aevumpay.config.AevumProperties;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class PublicController {

    private final InvoiceService invoices;
    private final AevumProperties properties;
    private final ChainSyncService sync;
    private final ChainWriter writer;

    public PublicController(
            InvoiceService invoices, AevumProperties properties, ChainSyncService sync, ChainWriter writer) {
        this.invoices = invoices;
        this.properties = properties;
        this.sync = sync;
        this.writer = writer;
    }

    @GetMapping("/api/v1/public/config")
    public Map<String, Object> config() {
        long chainId = properties.getChainId();
        String network = chainId == 44_005L ? "QDay Aevum Testnet" : chainId == 31_337L ? "Anvil" : "EVM";
        String nativeSymbol = chainId == 44_005L ? "tQDAY" : "ETH";
        return Map.ofEntries(
                Map.entry("chainId", chainId),
                Map.entry("chainIdHex", Eth.chainIdHex(chainId)),
                Map.entry("rpcUrl", properties.browserRpcUrl()),
                Map.entry("contractAddress", blank(properties.getContractAddress())),
                Map.entry("tokenAddress", blank(properties.getTokenAddress())),
                Map.entry("tokenDecimals", properties.getTokenDecimals()),
                Map.entry("tokenSymbol", "tUSD8"),
                Map.entry("explorerUrl", blank(properties.getExplorerUrl())),
                Map.entry("networkName", network),
                Map.entry("nativeSymbol", nativeSymbol),
                Map.entry("operatorAddress", writer.operatorAddress() == null ? "" : writer.operatorAddress()),
                Map.entry("publicBaseUrl", properties.getPublicBaseUrl()));
    }

    @GetMapping("/api/v1/public/sync")
    public ChainSyncService.SyncView sync() {
        return sync.status();
    }

    @GetMapping("/api/v1/public/invoices")
    public List<InvoiceView> list(@RequestParam String merchant) {
        return invoices.listPublic(merchant);
    }

    @GetMapping("/api/v1/public/invoices/{chainInvoiceId}")
    public InvoiceView one(@PathVariable long chainInvoiceId) {
        return invoices.publicByChainId(chainInvoiceId);
    }

    @GetMapping("/api/v1/public/invoices/{chainInvoiceId}/receipt")
    public Map<String, Object> receipt(@PathVariable long chainInvoiceId) {
        InvoiceView view = invoices.publicByChainId(chainInvoiceId);
        return Map.of(
                "invoice", view,
                "verification",
                "Call getInvoice(id) on the InvoicePayment contract, or open the tx in the explorer. The contract does not custody funds.");
    }

    private static String blank(String value) {
        return value == null ? "" : value;
    }
}
