package io.aevumpay.invoice;

import io.aevumpay.merchant.MerchantEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;
import java.math.BigInteger;
import java.time.Instant;

@Entity
@Table(
        name = "invoice",
        uniqueConstraints = @UniqueConstraint(name = "uk_merchant_order", columnNames = {"merchant_id", "order_ref"}))
public class InvoiceEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "merchant_id", nullable = false)
    private MerchantEntity merchant;

    @Column(unique = true)
    private Long chainInvoiceId;

    @Column(nullable = false, length = 120)
    private String orderId = "";

    /** 非空才参与去重。bytes32(0) 存 null，这样多张开放发票不会互相撞唯一索引。 */
    @Column(length = 66)
    private String orderRef;

    @Column(length = 42)
    private String payerAddress;

    @Column(nullable = false, precision = 38, scale = 0)
    private BigInteger amount;

    @Column(nullable = false, precision = 38, scale = 0)
    private BigInteger paidAmount = BigInteger.ZERO;

    @Column(nullable = false, precision = 38, scale = 0)
    private BigInteger refundedAmount = BigInteger.ZERO;

    @Column(nullable = false)
    private boolean allowPartial;

    @Column(nullable = false)
    private Instant dueAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private InvoiceStatus status = InvoiceStatus.CREATING;

    @Column(length = 66)
    private String createTxHash;

    @Column(length = 66)
    private String lastTxHash;

    @Column(length = 300)
    private String failureReason;

    @Version
    private int version;

    @Column(nullable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    @PrePersist
    void prePersist() {
        Instant now = Instant.now();
        if (createdAt == null) {
            createdAt = now;
        }
        if (updatedAt == null) {
            updatedAt = now;
        }
        if (paidAmount == null) {
            paidAmount = BigInteger.ZERO;
        }
        if (refundedAmount == null) {
            refundedAmount = BigInteger.ZERO;
        }
        if (orderId == null) {
            orderId = "";
        }
    }

    @PreUpdate
    void preUpdate() {
        updatedAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public MerchantEntity getMerchant() {
        return merchant;
    }

    public void setMerchant(MerchantEntity merchant) {
        this.merchant = merchant;
    }

    public Long getChainInvoiceId() {
        return chainInvoiceId;
    }

    public void setChainInvoiceId(Long chainInvoiceId) {
        this.chainInvoiceId = chainInvoiceId;
    }

    public String getOrderId() {
        return orderId;
    }

    public void setOrderId(String orderId) {
        this.orderId = orderId;
    }

    public String getOrderRef() {
        return orderRef;
    }

    public void setOrderRef(String orderRef) {
        this.orderRef = orderRef;
    }

    public String getPayerAddress() {
        return payerAddress;
    }

    public void setPayerAddress(String payerAddress) {
        this.payerAddress = payerAddress;
    }

    public BigInteger getAmount() {
        return amount;
    }

    public void setAmount(BigInteger amount) {
        this.amount = amount;
    }

    public BigInteger getPaidAmount() {
        return paidAmount;
    }

    public void setPaidAmount(BigInteger paidAmount) {
        this.paidAmount = paidAmount;
    }

    public BigInteger getRefundedAmount() {
        return refundedAmount;
    }

    public void setRefundedAmount(BigInteger refundedAmount) {
        this.refundedAmount = refundedAmount;
    }

    public boolean isAllowPartial() {
        return allowPartial;
    }

    public void setAllowPartial(boolean allowPartial) {
        this.allowPartial = allowPartial;
    }

    public Instant getDueAt() {
        return dueAt;
    }

    public void setDueAt(Instant dueAt) {
        this.dueAt = dueAt;
    }

    public InvoiceStatus getStatus() {
        return status;
    }

    public void setStatus(InvoiceStatus status) {
        this.status = status;
    }

    public String getCreateTxHash() {
        return createTxHash;
    }

    public void setCreateTxHash(String createTxHash) {
        this.createTxHash = createTxHash;
    }

    public String getLastTxHash() {
        return lastTxHash;
    }

    public void setLastTxHash(String lastTxHash) {
        this.lastTxHash = lastTxHash;
    }

    public String getFailureReason() {
        return failureReason;
    }

    public void setFailureReason(String failureReason) {
        this.failureReason = failureReason;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }
}
