package io.aevumpay.chain;

import io.aevumpay.config.AevumProperties;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.web3j.protocol.Web3j;
import org.web3j.protocol.core.DefaultBlockParameter;
import org.web3j.protocol.core.methods.request.EthFilter;
import org.web3j.protocol.core.methods.response.EthLog;
import org.web3j.protocol.core.methods.response.Log;

/**
 * 轮询 eth_getLogs，而不是 eth_newFilter。游标落在数据库里，进程重启从上次的区块接着扫。
 * 确认数把“刚出块、还可能重组”的头剪掉。类似消费 Kafka 时只提交已经处理完的 offset。
 */
@Service
public class ChainSyncService {

    private static final Logger log = LoggerFactory.getLogger(ChainSyncService.class);

    private final Web3j web3j;
    private final AevumProperties properties;
    private final SyncCursorRepository cursors;
    private final ChainApplyService applyService;

    public ChainSyncService(
            Web3j web3j, AevumProperties properties, SyncCursorRepository cursors, ChainApplyService applyService) {
        this.web3j = web3j;
        this.properties = properties;
        this.cursors = cursors;
        this.applyService = applyService;
    }

    public void syncOnce() {
        if (!properties.contractConfigured()) {
            return;
        }
        for (int i = 0; i < 20; i++) {
            if (!syncOneBatch()) {
                return;
            }
        }
    }

    public SyncView status() {
        long head = -1;
        try {
            head = web3j.ethBlockNumber().send().getBlockNumber().longValue();
        } catch (Exception exception) {
            log.debug("head lookup failed: {}", exception.toString());
        }
        SyncCursorEntity cursor = cursors.findById(SyncCursorEntity.NAME).orElse(null);
        Long cursorBlock = cursor == null ? null : cursor.getLastBlock();
        Long lag = head < 0 || cursorBlock == null ? null : Math.max(0, head - cursorBlock);
        return new SyncView(head, cursorBlock, lag, properties.getConfirmations(), properties.contractConfigured());
    }

    private boolean syncOneBatch() {
        try {
            long head = web3j.ethBlockNumber().send().getBlockNumber().longValue();
            long safeHead = head - Math.max(properties.getConfirmations(), 0);
            if (safeHead < 0) {
                return false;
            }
            SyncCursorEntity cursor = cursors.findById(SyncCursorEntity.NAME).orElseGet(this::newCursor);
            if (cursor.getLastBlock() >= 0 && cursor.getLastBlockHash() != null && !cursor.getLastBlockHash().isBlank()) {
                String hashNow = blockHash(cursor.getLastBlock());
                if (hashNow != null && !hashNow.equalsIgnoreCase(cursor.getLastBlockHash())) {
                    long floor = properties.getStartBlock() - 1;
                    long target = Math.max(floor, cursor.getLastBlock() - Math.max(properties.getReorgDepth(), 1));
                    String hash = target >= 0 ? blockHash(target) : "";
                    applyService.rewind(target, hash == null ? "" : hash);
                    return true;
                }
            }
            long from = cursor.getLastBlock() + 1;
            if (from > safeHead) {
                return false;
            }
            long to = Math.min(from + Math.max(properties.getLogBatchBlocks(), 1) - 1L, safeHead);
            List<DecodedEvent> decoded = fetch(from, to);
            String hash = blockHash(to);
            applyService.applyBatch(decoded, to, hash == null ? "" : hash);
            if (!decoded.isEmpty()) {
                log.info("indexed {} logs in blocks {}-{}", decoded.size(), from, to);
            }
            return to < safeHead;
        } catch (Exception exception) {
            log.warn("log poll failed: {}", exception.toString());
            return false;
        }
    }

    private SyncCursorEntity newCursor() {
        SyncCursorEntity cursor = new SyncCursorEntity();
        cursor.setName(SyncCursorEntity.NAME);
        cursor.setLastBlock(properties.getStartBlock() - 1);
        cursor.setLastBlockHash("");
        return cursors.save(cursor);
    }

    private List<DecodedEvent> fetch(long from, long to) throws Exception {
        EthFilter filter = new EthFilter(
                DefaultBlockParameter.valueOf(BigInteger.valueOf(from)),
                DefaultBlockParameter.valueOf(BigInteger.valueOf(to)),
                properties.getContractAddress());
        EthLog response = web3j.ethGetLogs(filter).send();
        if (response.hasError()) {
            throw new IllegalStateException(response.getError().getMessage());
        }
        List<Log> logs = new ArrayList<>();
        if (response.getLogs() != null) {
            for (EthLog.LogResult<?> result : response.getLogs()) {
                if (result.get() instanceof Log log && !log.isRemoved()) {
                    logs.add(log);
                }
            }
        }
        logs.sort(Comparator.comparing(Log::getBlockNumber, Comparator.nullsFirst(BigInteger::compareTo))
                .thenComparing(Log::getLogIndex, Comparator.nullsFirst(BigInteger::compareTo)));
        List<DecodedEvent> decoded = new ArrayList<>();
        for (Log log : logs) {
            EventDecoder.decode(log).ifPresent(decoded::add);
        }
        return decoded;
    }

    private String blockHash(long number) throws Exception {
        var block = web3j.ethGetBlockByNumber(DefaultBlockParameter.valueOf(BigInteger.valueOf(number)), false)
                .send();
        if (block.getBlock() == null) {
            return null;
        }
        return block.getBlock().getHash();
    }

    public record SyncView(long head, Long cursor, Long lag, int confirmations, boolean contractConfigured) {}
}
