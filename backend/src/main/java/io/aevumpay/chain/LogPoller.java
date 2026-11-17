package io.aevumpay.chain;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class LogPoller {

    private final ChainSyncService syncService;

    public LogPoller(ChainSyncService syncService) {
        this.syncService = syncService;
    }

    @Scheduled(fixedDelayString = "${aevum.poll-interval-ms:3000}")
    public void poll() {
        syncService.syncOnce();
    }
}
