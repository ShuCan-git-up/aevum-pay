// SPDX-License-Identifier: MIT
pragma solidity 0.8.24;

import {Test} from "forge-std/Test.sol";
import {ERC20} from "@openzeppelin/contracts/token/ERC20/ERC20.sol";
import {ReentrancyGuard} from "@openzeppelin/contracts/utils/ReentrancyGuard.sol";
import {InvoicePayment} from "../src/InvoicePayment.sol";

/// @notice 结算币在 transferFrom 里回调发票合约，用来证明 nonReentrant + CEI。
contract ReenteringToken is ERC20 {
    enum Mode {
        None,
        ObservePaid,
        AttackPay,
        AttackRefund
    }

    InvoicePayment public target;
    Mode public mode;
    uint256 public attackId;
    uint256 public paidSeen;

    constructor() ERC20("Bad USD", "BAD") {}

    function decimals() public pure override returns (uint8) {
        return 6;
    }

    function mint(address to, uint256 amount) external {
        _mint(to, amount);
    }

    function configure(InvoicePayment target_, Mode mode_, uint256 id) external {
        target = target_;
        mode = mode_;
        attackId = id;
    }

    function transferFrom(address from, address to, uint256 value) public override returns (bool) {
        Mode current = mode;
        mode = Mode.None;
        if (current == Mode.ObservePaid) {
            paidSeen = target.getInvoice(attackId).paidAmount;
        } else if (current == Mode.AttackPay) {
            target.pay(attackId, 1);
        } else if (current == Mode.AttackRefund) {
            target.refund(attackId, 1);
        }
        return super.transferFrom(from, to, value);
    }
}

contract ReentrancyTest is Test {
    ReenteringToken internal token;
    InvoicePayment internal payment;
    address internal merchant = makeAddr("merchant");
    address internal payer = makeAddr("payer");

    function setUp() public {
        token = new ReenteringToken();
        payment = new InvoicePayment(token, address(this));
        token.mint(payer, 1_000e6);
        token.mint(merchant, 1_000e6);
        vm.prank(payer);
        token.approve(address(payment), type(uint256).max);
        vm.prank(merchant);
        token.approve(address(payment), type(uint256).max);
    }

    function test_stateIsUpdatedBeforeTokenInteraction() public {
        vm.prank(merchant);
        uint256 id = payment.createInvoice(payer, 50e6, uint64(block.timestamp + 1 days), true, bytes32("obs"));
        token.configure(payment, ReenteringToken.Mode.ObservePaid, id);

        vm.prank(payer);
        payment.pay(id, 20e6);

        assertEq(token.paidSeen(), 20e6);
        assertEq(payment.getInvoice(id).paidAmount, 20e6);
        assertEq(token.balanceOf(address(payment)), 0);
    }

    function test_payReentrancyReverts() public {
        vm.prank(merchant);
        uint256 id = payment.createInvoice(payer, 50e6, uint64(block.timestamp + 1 days), true, bytes32("pay"));
        token.configure(payment, ReenteringToken.Mode.AttackPay, id);

        vm.prank(payer);
        vm.expectRevert(ReentrancyGuard.ReentrancyGuardReentrantCall.selector);
        payment.pay(id, 10e6);

        assertEq(payment.getInvoice(id).paidAmount, 0);
        assertEq(uint8(payment.statusOf(id)), uint8(InvoicePayment.Status.Pending));
        assertEq(token.balanceOf(merchant), 1_000e6);
    }

    function test_refundReentrancyReverts() public {
        vm.prank(merchant);
        uint256 id = payment.createInvoice(payer, 50e6, uint64(block.timestamp + 1 days), false, bytes32("ref"));

        vm.prank(payer);
        payment.pay(id, 50e6);

        token.configure(payment, ReenteringToken.Mode.AttackRefund, id);
        vm.prank(merchant);
        vm.expectRevert(ReentrancyGuard.ReentrancyGuardReentrantCall.selector);
        payment.refund(id, 10e6);

        assertEq(payment.getInvoice(id).refundedAmount, 0);
        assertEq(uint8(payment.statusOf(id)), uint8(InvoicePayment.Status.Paid));
    }
}
