// SPDX-License-Identifier: MIT
pragma solidity 0.8.24;

import {Test} from "forge-std/Test.sol";
import {InvoicePayment} from "../src/InvoicePayment.sol";
import {MockTUSD8} from "../src/mocks/MockTUSD8.sol";

contract InvoicePaymentFuzzTest is Test {
    InvoicePayment internal payment;
    MockTUSD8 internal token;
    address internal merchant = makeAddr("merchant");
    address internal payer = makeAddr("payer");
    address internal feeTo = makeAddr("fee");

    function setUp() public {
        token = new MockTUSD8();
        payment = new InvoicePayment(token, address(this));
    }

    function testFuzz_partialPaymentsReachExactPaidAndZeroBalance(uint128 amount, uint8 parts) public {
        amount = uint128(bound(amount, 1, 1_000_000e6));
        parts = uint8(bound(parts, 1, 12));

        token.mint(payer, amount);
        vm.prank(payer);
        token.approve(address(payment), amount);

        vm.prank(merchant);
        uint256 id = payment.createInvoice(
            payer, amount, uint64(block.timestamp + 30 days), true, keccak256(abi.encodePacked(amount, parts))
        );

        uint256 left = amount;
        uint256 paid;
        for (uint256 i = 0; i < parts && left > 0; i++) {
            uint256 chunk = (i + 1 == parts) ? left : left / (parts - i);
            if (chunk == 0) chunk = 1;
            vm.prank(payer);
            payment.pay(id, chunk);
            left -= chunk;
            paid += chunk;
            assertEq(payment.getInvoice(id).paidAmount, paid);
            assertLe(paid, amount);
        }

        assertEq(left, 0);
        assertEq(payment.remainingOf(id), 0);
        assertEq(uint8(payment.statusOf(id)), uint8(InvoicePayment.Status.Paid));
        assertEq(token.balanceOf(merchant), amount);
        assertEq(token.balanceOf(address(payment)), 0);
        assertEq(token.balanceOf(payer), 0);
    }

    function testFuzz_feeNeverExceedsOnePercent(uint128 amount, uint16 bps) public {
        amount = uint128(bound(amount, 1, 5_000_000e6));
        bps = uint16(bound(bps, 0, payment.MAX_FEE_BPS()));
        if (bps > 0) payment.setFee(bps, feeTo);

        token.mint(payer, amount);
        vm.prank(payer);
        token.approve(address(payment), amount);

        vm.prank(merchant);
        uint256 id = payment.createInvoice(payer, amount, uint64(block.timestamp + 1 days), false, bytes32(uint256(amount)));

        vm.prank(payer);
        payment.pay(id, amount);

        uint256 fee = token.balanceOf(feeTo);
        uint256 net = token.balanceOf(merchant);
        assertEq(fee + net, amount);
        assertLe(fee * 10_000, uint256(amount) * payment.MAX_FEE_BPS());
        assertEq(fee, (uint256(amount) * bps) / 10_000);
        assertEq(token.balanceOf(address(payment)), 0);
        assertEq(payment.getInvoice(id).paidAmount, amount);
    }

    function testFuzz_refundNeverExceedsPaid(uint128 amount, uint128 refundPart) public {
        amount = uint128(bound(amount, 1, 1_000_000e6));
        refundPart = uint128(bound(refundPart, 1, amount));

        token.mint(payer, amount);
        token.mint(merchant, amount);
        vm.prank(payer);
        token.approve(address(payment), amount);
        vm.prank(merchant);
        token.approve(address(payment), amount);

        vm.prank(merchant);
        uint256 id = payment.createInvoice(payer, amount, uint64(block.timestamp + 3 days), true, bytes32(uint256(uint256(amount) << 1 | 1)));

        vm.prank(payer);
        payment.pay(id, amount);
        vm.prank(merchant);
        payment.refund(id, refundPart);

        InvoicePayment.Invoice memory inv = payment.getInvoice(id);
        assertEq(inv.refundedAmount, refundPart);
        assertLe(inv.refundedAmount, inv.paidAmount);
        if (refundPart == amount) {
            assertEq(uint8(inv.status), uint8(InvoicePayment.Status.Refunded));
        } else {
            assertEq(uint8(inv.status), uint8(InvoicePayment.Status.Paid));
        }
        assertEq(token.balanceOf(address(payment)), 0);
        assertEq(token.balanceOf(payer), refundPart);
    }
}
