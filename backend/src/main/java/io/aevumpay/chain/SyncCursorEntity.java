package io.aevumpay.chain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "sync_cursor")
public class SyncCursorEntity {

    public static final String NAME = "InvoicePayment";

    @Id
    @Column(length = 50)
    private String name = NAME;

    private long lastBlock;

    @Column(length = 66)
    private String lastBlockHash = "";

    @Column(nullable = false)
    private Instant updatedAt = Instant.now();

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public long getLastBlock() {
        return lastBlock;
    }

    public void setLastBlock(long lastBlock) {
        this.lastBlock = lastBlock;
    }

    public String getLastBlockHash() {
        return lastBlockHash;
    }

    public void setLastBlockHash(String lastBlockHash) {
        this.lastBlockHash = lastBlockHash;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }
}
