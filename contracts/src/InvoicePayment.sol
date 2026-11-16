// SPDX-License-Identifier: MIT
pragma solidity 0.8.24;

import {IERC20} from "@openzeppelin/contracts/token/ERC20/IERC20.sol";
import {SafeERC20} from "@openzeppelin/contracts/token/ERC20/utils/SafeERC20.sol";
import {ReentrancyGuard} from "@openzeppelin/contracts/utils/ReentrancyGuard.sol";
import {Pausable} from "@openzeppelin/contracts/utils/Pausable.sol";
import {Ownable} from "@openzeppelin/contracts/access/Ownable.sol";

/// @title InvoicePayment
/// @notice 非托管发票支付。tUSD8 在 `pay` 里从付款人直接转到商家，合约余额应始终为 0。
///
/// 像一张只追加的发票表（Java 类比：`HashMap<Long, Invoice>` + 唯一索引
/// `UNIQUE(merchant, orderRef)`）。没有定时任务：过期是 `statusOf()` 算出来的，
/// 不写进 storage，类似查询时用 `dueAt < now` 派生出的视图状态。
///
/// 权限：
/// - owner 只能暂停、以及把手续费设到不超过 1%。不能换币，不能动别人的钱。
/// - 商家开票、取消未收款发票、退款、授权 operator。
/// - operator 只能替已授权的商家开票和取消未收款发票。
contract InvoicePayment is ReentrancyGuard, Pausable, Ownable {
    using SafeERC20 for IERC20;

    /// @dev 结算币小数位。官方 tUSD8 是 6 位；合约不读 `decimals()`，避免非标准代币。
    uint8 public constant TOKEN_DECIMALS = 6;

    /// @dev 1% = 100 bps。硬顶，owner 也改不了这个上限。
    uint16 public constant MAX_FEE_BPS = 100;
    uint256 public constant BPS_DENOMINATOR = 10_000;

    /// @dev 发票最长有效期。挡住把毫秒当成秒传进来、导致发票实际上永不过期。
    uint64 public constant MAX_INVOICE_WINDOW = 3650 days;

    enum Status {
        None,
        Pending,
        PartiallyPaid,
        Paid,
        Cancelled,
        Refunded,
        Expired
    }

    /// @dev 字段按槽打包。merchant+dueAt+allowPartial+status 挤在一个 slot，
    /// 类似 Java 对象头之外能塞进同一缓存行的紧凑字段。
    struct Invoice {
        address merchant;
        uint64 dueAt;
        bool allowPartial;
        Status status;
        address payer;
        uint64 createdAt;
        uint128 amount;
        uint128 paidAmount;
        uint128 refundedAmount;
        bytes32 orderRef;
    }

    /// @dev 部署时定死，类似 Java 的 `final`。管理员换不了结算币。
    IERC20 public immutable token;

    /// @dev 下一张发票的 id，从 1 开始，类似 AUTO_INCREMENT。0 表示“不存在”。
    uint256 public nextInvoiceId = 1;

    mapping(uint256 id => Invoice invoice) private _invoices;

    /// @dev `orderRef == 0` 表示不参与去重（支付链接可以不带订单号）。
    mapping(address merchant => mapping(bytes32 orderRef => uint256 invoiceId)) public invoiceIdByOrderRef;

    /// @dev 商家授权的代开票地址。operator 碰不到资金。
    mapping(address merchant => mapping(address operator => bool approved)) public isOperator;

    uint16 public feeBps;
    address public feeRecipient;

    event InvoiceCreated(
        uint256 indexed id,
        address indexed merchant,
        address indexed payer,
        uint256 amount,
        uint64 dueAt,
        bool allowPartial,
        bytes32 orderRef
    );

    event InvoicePaid(
        uint256 indexed id,
        address indexed payer,
        address indexed merchant,
        uint256 amount,
        uint256 fee,
        uint256 totalPaid,
        bool fullyPaid
    );

    event InvoiceCancelled(uint256 indexed id, address indexed merchant);

    event InvoiceRefunded(uint256 indexed id, address indexed to, uint256 amount, uint256 totalRefunded);

    event OperatorSet(address indexed merchant, address indexed operator, bool approved);

    event FeeUpdated(uint16 feeBps, address feeRecipient);

    error ZeroAddress();
    error InvalidAmount();
    error InvalidDue(uint64 dueAt);
    error InvoiceNotFound(uint256 id);
    error DuplicateOrderRef(address merchant, bytes32 orderRef);
    error InvoiceExpired(uint256 id, uint64 dueAt);
    error InvoiceNotPayable(uint256 id);
    error NotPayer(uint256 id, address caller);
    error Overpayment(uint256 id, uint256 amount, uint256 remaining);
    error PartialNotAllowed(uint256 id);
    error NotMerchant(uint256 id);
    error NotMerchantOrOperator(uint256 id);
    error NotOperator(address merchant);
    error InvoiceNotCancellable(uint256 id);
    error NotRefundable(uint256 id);
    error OverRefund(uint256 id, uint256 amount, uint256 refundable);
    error FeeTooHigh(uint16 bps, uint16 maxBps);
    error FeeRecipientRequired();
    error NativeNotAccepted();

    constructor(IERC20 token_, address initialOwner) Ownable(initialOwner) {
        if (address(token_) == address(0) || initialOwner == address(0)) revert ZeroAddress();
        token = token_;
    }

    /// @notice 拒绝误转进来的 tQDAY。合约不保管原生币。
    receive() external payable {
        revert NativeNotAccepted();
    }

    function createInvoice(address payer, uint256 amount, uint64 dueAt, bool allowPartial, bytes32 orderRef)
        external
        whenNotPaused
        returns (uint256 id)
    {
        return _create(msg.sender, payer, amount, dueAt, allowPartial, orderRef);
    }

    /// @notice 已授权的 operator 代商家开票。收款人仍然是 merchant。
    function createInvoiceFor(
        address merchant,
        address payer,
        uint256 amount,
        uint64 dueAt,
        bool allowPartial,
        bytes32 orderRef
    ) external whenNotPaused returns (uint256 id) {
        if (merchant == address(0)) revert ZeroAddress();
        if (!isOperator[merchant][msg.sender]) revert NotOperator(merchant);
        return _create(merchant, payer, amount, dueAt, allowPartial, orderRef);
    }

    function setOperator(address operator, bool approved) external {
        if (operator == address(0)) revert ZeroAddress();
        isOperator[msg.sender][operator] = approved;
        emit OperatorSet(msg.sender, operator, approved);
    }

    /// @notice 付款。`amount` 是本次结算额（含手续费），记入发票已付。
    /// 手续费从这笔金额里切给 `feeRecipient`，商家收到 `amount - fee`。
    /// 开放发票（payer = 0）的第一笔付款会把付款人锁死，后面的分期只能由这个地址付。
    function pay(uint256 id, uint256 amount) external nonReentrant whenNotPaused {
        Invoice storage inv = _invoices[id];
        if (inv.merchant == address(0)) revert InvoiceNotFound(id);
        Status stored = inv.status;
        if (stored != Status.Pending && stored != Status.PartiallyPaid) revert InvoiceNotPayable(id);
        if (block.timestamp > inv.dueAt) revert InvoiceExpired(id, inv.dueAt);
        if (amount == 0) revert InvalidAmount();
        if (inv.payer != address(0) && inv.payer != msg.sender) revert NotPayer(id, msg.sender);

        uint256 remaining = uint256(inv.amount) - uint256(inv.paidAmount);
        if (amount > remaining) revert Overpayment(id, amount, remaining);
        if (!inv.allowPartial && amount != remaining) revert PartialNotAllowed(id);

        // Checks-Effects-Interactions：先改状态，再转账。
        // 加上 nonReentrant，恶意 ERC-20 在 transferFrom 里回调也改不了发票。
        if (inv.payer == address(0)) inv.payer = msg.sender;

        uint256 fee = (amount * feeBps) / BPS_DENOMINATOR;
        uint256 newPaid = uint256(inv.paidAmount) + amount;
        inv.paidAmount = uint128(newPaid);
        bool fullyPaid = newPaid == inv.amount;
        inv.status = fullyPaid ? Status.Paid : Status.PartiallyPaid;

        address merchant = inv.merchant;
        address recipient = feeRecipient;
        if (fee > 0) {
            token.safeTransferFrom(msg.sender, recipient, fee);
            token.safeTransferFrom(msg.sender, merchant, amount - fee);
        } else {
            token.safeTransferFrom(msg.sender, merchant, amount);
        }

        emit InvoicePaid(id, msg.sender, merchant, amount, fee, newPaid, fullyPaid);
    }

    /// @notice 取消还没收到钱的发票。过期但未收款的也可以取消。暂停期间仍允许。
    function cancelInvoice(uint256 id) external {
        Invoice storage inv = _invoices[id];
        if (inv.merchant == address(0)) revert InvoiceNotFound(id);
        if (msg.sender != inv.merchant && !isOperator[inv.merchant][msg.sender]) {
            revert NotMerchantOrOperator(id);
        }
        if (inv.paidAmount != 0 || inv.status != Status.Pending) revert InvoiceNotCancellable(id);
        inv.status = Status.Cancelled;
        emit InvoiceCancelled(id, inv.merchant);
    }

    /// @notice 商家把 tUSD8 退回付款人。需要商家事先 `approve` 本合约。
    /// 暂停时仍然可以退，避免熔断把已经付掉的钱卡住。
    function refund(uint256 id, uint256 amount) external nonReentrant {
        Invoice storage inv = _invoices[id];
        if (inv.merchant == address(0)) revert InvoiceNotFound(id);
        if (msg.sender != inv.merchant) revert NotMerchant(id);
        if (amount == 0) revert InvalidAmount();
        if (inv.payer == address(0) || inv.paidAmount == 0) revert NotRefundable(id);

        uint256 refundable = uint256(inv.paidAmount) - uint256(inv.refundedAmount);
        if (amount > refundable) revert OverRefund(id, amount, refundable);

        uint256 newRefunded = uint256(inv.refundedAmount) + amount;
        inv.refundedAmount = uint128(newRefunded);
        if (newRefunded == inv.paidAmount) inv.status = Status.Refunded;

        address payer = inv.payer;
        token.safeTransferFrom(msg.sender, payer, amount);
        emit InvoiceRefunded(id, payer, amount, newRefunded);
    }

    function pause() external onlyOwner {
        _pause();
    }

    function unpause() external onlyOwner {
        _unpause();
    }

    function setFee(uint16 bps, address recipient) external onlyOwner {
        if (bps > MAX_FEE_BPS) revert FeeTooHigh(bps, MAX_FEE_BPS);
        if (bps > 0 && recipient == address(0)) revert FeeRecipientRequired();
        feeBps = bps;
        feeRecipient = recipient;
        emit FeeUpdated(bps, recipient);
    }

    function getInvoice(uint256 id) external view returns (Invoice memory invoice) {
        invoice = _invoices[id];
        if (invoice.merchant == address(0)) revert InvoiceNotFound(id);
        invoice.status = statusOf(id);
    }

    /// @notice 动态状态。Pending / PartiallyPaid 过了 dueAt 就显示 Expired，storage 里仍是原状态。
    function statusOf(uint256 id) public view returns (Status) {
        Invoice storage inv = _invoices[id];
        if (inv.merchant == address(0)) return Status.None;
        Status stored = inv.status;
        if ((stored == Status.Pending || stored == Status.PartiallyPaid) && block.timestamp > inv.dueAt) {
            return Status.Expired;
        }
        return stored;
    }

    function remainingOf(uint256 id) public view returns (uint256) {
        Invoice storage inv = _invoices[id];
        if (inv.merchant == address(0)) revert InvoiceNotFound(id);
        if (inv.paidAmount >= inv.amount) return 0;
        return uint256(inv.amount) - uint256(inv.paidAmount);
    }

    function _create(
        address merchant,
        address payer,
        uint256 amount,
        uint64 dueAt,
        bool allowPartial,
        bytes32 orderRef
    ) private returns (uint256 id) {
        if (amount == 0 || amount > type(uint128).max) revert InvalidAmount();
        if (dueAt <= block.timestamp || dueAt > block.timestamp + MAX_INVOICE_WINDOW) revert InvalidDue(dueAt);

        if (orderRef != bytes32(0)) {
            if (invoiceIdByOrderRef[merchant][orderRef] != 0) revert DuplicateOrderRef(merchant, orderRef);
        }

        id = nextInvoiceId++;
        if (orderRef != bytes32(0)) invoiceIdByOrderRef[merchant][orderRef] = id;

        Invoice storage inv = _invoices[id];
        inv.merchant = merchant;
        inv.dueAt = dueAt;
        inv.allowPartial = allowPartial;
        inv.status = Status.Pending;
        inv.payer = payer;
        inv.createdAt = uint64(block.timestamp);
        inv.amount = uint128(amount);
        inv.orderRef = orderRef;

        emit InvoiceCreated(id, merchant, payer, amount, dueAt, allowPartial, orderRef);
    }
}
