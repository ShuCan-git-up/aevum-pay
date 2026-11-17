// SPDX-License-Identifier: MIT
pragma solidity 0.8.24;

import {Test} from "forge-std/Test.sol";
import {Ownable} from "@openzeppelin/contracts/access/Ownable.sol";
import {Pausable} from "@openzeppelin/contracts/utils/Pausable.sol";
import {InvoicePayment} from "../src/InvoicePayment.sol";
import {MockTUSD8} from "../src/mocks/MockTUSD8.sol";

contract InvoicePaymentTest is Test {
    InvoicePayment internal payment;
    MockTUSD8 internal token;

    address internal merchant = makeAddr("merchant");
    address internal payer = makeAddr("payer");
    address internal other = makeAddr("other");
    address internal operator = makeAddr("operator");
    address internal feeTo = makeAddr("fee");

    uint256 internal seq;

    function setUp() public {
        token = new MockTUSD8();
        payment = new InvoicePayment(token, address(this));
        token.mint(payer, 1_000_000e6);
        token.mint(merchant, 1_000_000e6);
        token.mint(other, 1_000_000e6);
        vm.prank(payer);
        token.approve(address(payment), type(uint256).max);
        vm.prank(merchant);
        token.approve(address(payment), type(uint256).max);
        vm.prank(other);
        token.approve(address(payment), type(uint256).max);
    }

    function test_createAndPayFull_transfersToMerchant_contractHoldsZero() public {
        uint256 id = _create(merchant, payer, 10e6, false);
        uint256 beforeMerchant = token.balanceOf(merchant);

        vm.prank(payer);
        payment.pay(id, 10e6);

        assertEq(token.balanceOf(merchant) - beforeMerchant, 10e6);
        assertEq(token.balanceOf(address(payment)), 0);
        assertEq(uint8(payment.statusOf(id)), uint8(InvoicePayment.Status.Paid));
        assertEq(payment.remainingOf(id), 0);

        InvoicePayment.Invoice memory inv = payment.getInvoice(id);
        assertEq(inv.payer, payer);
        assertEq(inv.paidAmount, 10e6);
        assertEq(inv.merchant, merchant);
    }

    function test_partialPayments_thenComplete() public {
        uint256 id = _create(merchant, payer, 100e6, true);

        vm.prank(payer);
        payment.pay(id, 40e6);
        assertEq(uint8(payment.statusOf(id)), uint8(InvoicePayment.Status.PartiallyPaid));
        assertEq(payment.remainingOf(id), 60e6);

        vm.prank(payer);
        payment.pay(id, 60e6);
        assertEq(uint8(payment.statusOf(id)), uint8(InvoicePayment.Status.Paid));
        assertEq(token.balanceOf(address(payment)), 0);
    }

    function test_partialNotAllowed_mustPayExact() public {
        uint256 id = _create(merchant, payer, 10e6, false);
        vm.prank(payer);
        vm.expectRevert(abi.encodeWithSelector(InvoicePayment.PartialNotAllowed.selector, id));
        payment.pay(id, 9e6);
    }

    function test_overpaymentReverts() public {
        uint256 id = _create(merchant, payer, 10e6, true);
        vm.prank(payer);
        vm.expectRevert(abi.encodeWithSelector(InvoicePayment.Overpayment.selector, id, 11e6, 10e6));
        payment.pay(id, 11e6);
    }

    function test_payZeroReverts() public {
        uint256 id = _create(merchant, payer, 10e6, true);
        vm.prank(payer);
        vm.expectRevert(InvoicePayment.InvalidAmount.selector);
        payment.pay(id, 0);
    }

    function test_doublePayAfterPaidReverts() public {
        uint256 id = _create(merchant, payer, 10e6, false);
        vm.prank(payer);
        payment.pay(id, 10e6);
        vm.prank(payer);
        vm.expectRevert(abi.encodeWithSelector(InvoicePayment.InvoiceNotPayable.selector, id));
        payment.pay(id, 1);
    }

    function test_wrongPayerReverts_andOpenInvoiceLocksFirstPayer() public {
        uint256 specified = _create(merchant, payer, 10e6, false);
        vm.prank(other);
        vm.expectRevert(abi.encodeWithSelector(InvoicePayment.NotPayer.selector, specified, other));
        payment.pay(specified, 10e6);

        uint256 openId = _create(merchant, address(0), 10e6, true);
        vm.prank(payer);
        payment.pay(openId, 4e6);
        assertEq(payment.getInvoice(openId).payer, payer);

        vm.prank(other);
        vm.expectRevert(abi.encodeWithSelector(InvoicePayment.NotPayer.selector, openId, other));
        payment.pay(openId, 1e6);
    }

    function test_expiryBoundary_andCancelAfterExpiry() public {
        bytes32 ref = _ref();
        uint64 due = uint64(block.timestamp + 1 days);
        vm.prank(merchant);
        uint256 id = payment.createInvoice(payer, 10e6, due, false, ref);

        vm.warp(due);
        vm.prank(payer);
        payment.pay(id, 10e6);
        assertEq(uint8(payment.statusOf(id)), uint8(InvoicePayment.Status.Paid));

        uint256 unpaid = _create(merchant, payer, 5e6, false);
        uint64 unpaidDue = payment.getInvoice(unpaid).dueAt;
        vm.warp(unpaidDue + 1);
        assertEq(uint8(payment.statusOf(unpaid)), uint8(InvoicePayment.Status.Expired));

        vm.prank(payer);
        vm.expectRevert(abi.encodeWithSelector(InvoicePayment.InvoiceExpired.selector, unpaid, unpaidDue));
        payment.pay(unpaid, 5e6);

        vm.prank(merchant);
        payment.cancelInvoice(unpaid);
        assertEq(uint8(payment.statusOf(unpaid)), uint8(InvoicePayment.Status.Cancelled));
    }

    function test_cancelUnpaid_andRejectAfterPayment() public {
        uint256 id = _create(merchant, payer, 10e6, true);
        vm.prank(other);
        vm.expectRevert(abi.encodeWithSelector(InvoicePayment.NotMerchantOrOperator.selector, id));
        payment.cancelInvoice(id);

        vm.prank(merchant);
        payment.cancelInvoice(id);
        assertEq(uint8(payment.statusOf(id)), uint8(InvoicePayment.Status.Cancelled));

        uint256 paid = _create(merchant, payer, 10e6, true);
        vm.prank(payer);
        payment.pay(paid, 1e6);
        vm.prank(merchant);
        vm.expectRevert(abi.encodeWithSelector(InvoicePayment.InvoiceNotCancellable.selector, paid));
        payment.cancelInvoice(paid);
    }

    function test_orderRefDedup_andZeroRefReusable() public {
        bytes32 ref = _ref();
        vm.prank(merchant);
        payment.createInvoice(payer, 1e6, uint64(block.timestamp + 1 days), false, ref);
        vm.prank(merchant);
        vm.expectRevert(abi.encodeWithSelector(InvoicePayment.DuplicateOrderRef.selector, merchant, ref));
        payment.createInvoice(payer, 1e6, uint64(block.timestamp + 1 days), false, ref);

        vm.prank(merchant);
        payment.createInvoice(address(0), 1e6, uint64(block.timestamp + 1 days), false, bytes32(0));
        vm.prank(merchant);
        payment.createInvoice(address(0), 2e6, uint64(block.timestamp + 1 days), false, bytes32(0));
        assertEq(payment.invoiceIdByOrderRef(merchant, bytes32(0)), 0);
    }

    function test_operatorCanCreateAndCancel_butCannotRefund() public {
        vm.prank(merchant);
        payment.setOperator(operator, true);

        bytes32 ref = _ref();
        vm.prank(operator);
        uint256 id = payment.createInvoiceFor(merchant, payer, 8e6, uint64(block.timestamp + 2 days), false, ref);
        assertEq(payment.getInvoice(id).merchant, merchant);

        vm.prank(other);
        vm.expectRevert(abi.encodeWithSelector(InvoicePayment.NotOperator.selector, merchant));
        payment.createInvoiceFor(merchant, payer, 1e6, uint64(block.timestamp + 2 days), false, _ref());

        vm.prank(operator);
        payment.cancelInvoice(id);

        uint256 live = _create(merchant, payer, 5e6, false);
        vm.prank(payer);
        payment.pay(live, 5e6);
        vm.prank(operator);
        vm.expectRevert(abi.encodeWithSelector(InvoicePayment.NotMerchant.selector, live));
        payment.refund(live, 1e6);

        vm.prank(merchant);
        payment.setOperator(operator, false);
        vm.prank(operator);
        vm.expectRevert(abi.encodeWithSelector(InvoicePayment.NotOperator.selector, merchant));
        payment.createInvoiceFor(merchant, payer, 1e6, uint64(block.timestamp + 2 days), false, _ref());
    }

    function test_refundPartialThenFull_expiredPartialStillRefundable() public {
        uint256 id = _create(merchant, payer, 10e6, true);
        vm.prank(payer);
        payment.pay(id, 10e6);

        uint256 payerBefore = token.balanceOf(payer);
        vm.prank(merchant);
        payment.refund(id, 4e6);
        assertEq(token.balanceOf(payer) - payerBefore, 4e6);
        assertEq(uint8(payment.statusOf(id)), uint8(InvoicePayment.Status.Paid));
        assertEq(token.balanceOf(address(payment)), 0);

        vm.prank(merchant);
        payment.refund(id, 6e6);
        assertEq(uint8(payment.statusOf(id)), uint8(InvoicePayment.Status.Refunded));

        vm.prank(payer);
        vm.expectRevert(abi.encodeWithSelector(InvoicePayment.InvoiceNotPayable.selector, id));
        payment.pay(id, 1);

        uint256 partialId = _create(merchant, payer, 9e6, true);
        vm.prank(payer);
        payment.pay(partialId, 3e6);
        vm.warp(payment.getInvoice(partialId).dueAt + 1);
        assertEq(uint8(payment.statusOf(partialId)), uint8(InvoicePayment.Status.Expired));
        vm.prank(merchant);
        payment.refund(partialId, 3e6);
        assertEq(uint8(payment.statusOf(partialId)), uint8(InvoicePayment.Status.Refunded));
    }

    function test_overRefundAndEmptyRefundRevert() public {
        uint256 id = _create(merchant, payer, 5e6, false);
        vm.prank(merchant);
        vm.expectRevert(abi.encodeWithSelector(InvoicePayment.NotRefundable.selector, id));
        payment.refund(id, 1);

        vm.prank(payer);
        payment.pay(id, 5e6);
        vm.prank(merchant);
        vm.expectRevert(abi.encodeWithSelector(InvoicePayment.OverRefund.selector, id, 6e6, 5e6));
        payment.refund(id, 6e6);
    }

    function test_pauseBlocksPayAndCreate_refundStillWorks() public {
        uint256 id = _create(merchant, payer, 7e6, false);
        payment.pause();

        vm.prank(merchant);
        vm.expectRevert(Pausable.EnforcedPause.selector);
        payment.createInvoice(payer, 1e6, uint64(block.timestamp + 1 days), false, _ref());

        vm.prank(payer);
        vm.expectRevert(Pausable.EnforcedPause.selector);
        payment.pay(id, 7e6);

        payment.unpause();
        vm.prank(payer);
        payment.pay(id, 7e6);

        payment.pause();
        vm.prank(merchant);
        payment.refund(id, 7e6);
        assertEq(uint8(payment.statusOf(id)), uint8(InvoicePayment.Status.Refunded));
    }

    function test_feeCappedAndTakenFromPayment() public {
        payment.setFee(100, feeTo);
        uint256 id = _create(merchant, payer, 10_000e6, false);
        uint256 merchantBefore = token.balanceOf(merchant);

        vm.prank(payer);
        payment.pay(id, 10_000e6);

        assertEq(token.balanceOf(feeTo), 100e6);
        assertEq(token.balanceOf(merchant) - merchantBefore, 9_900e6);
        assertEq(token.balanceOf(address(payment)), 0);
        assertEq(payment.getInvoice(id).paidAmount, 10_000e6);
    }

    function test_setFeeGuards() public {
        vm.expectRevert(abi.encodeWithSelector(InvoicePayment.FeeTooHigh.selector, uint16(101), uint16(100)));
        payment.setFee(101, feeTo);

        vm.expectRevert(InvoicePayment.FeeRecipientRequired.selector);
        payment.setFee(1, address(0));

        vm.prank(other);
        vm.expectRevert(abi.encodeWithSelector(Ownable.OwnableUnauthorizedAccount.selector, other));
        payment.setFee(1, feeTo);

        vm.prank(other);
        vm.expectRevert(abi.encodeWithSelector(Ownable.OwnableUnauthorizedAccount.selector, other));
        payment.pause();
    }

    function test_invalidCreate() public {
        vm.prank(merchant);
        vm.expectRevert(InvoicePayment.InvalidAmount.selector);
        payment.createInvoice(payer, 0, uint64(block.timestamp + 1 days), false, _ref());

        vm.prank(merchant);
        vm.expectRevert(abi.encodeWithSelector(InvoicePayment.InvalidDue.selector, uint64(block.timestamp)));
        payment.createInvoice(payer, 1, uint64(block.timestamp), false, _ref());

        uint64 tooFar = uint64(block.timestamp + payment.MAX_INVOICE_WINDOW() + 1);
        vm.prank(merchant);
        vm.expectRevert(abi.encodeWithSelector(InvoicePayment.InvalidDue.selector, tooFar));
        payment.createInvoice(payer, 1, tooFar, false, _ref());

        vm.expectRevert(abi.encodeWithSelector(InvoicePayment.InvoiceNotFound.selector, uint256(0)));
        payment.getInvoice(0);

        assertEq(uint8(payment.statusOf(0)), uint8(InvoicePayment.Status.None));
    }

    function test_nativeTransferReverts() public {
        (bool ok,) = address(payment).call{value: 1 ether}("");
        assertFalse(ok);
        assertEq(address(payment).balance, 0);
    }

    function test_missingInvoicePayReverts() public {
        vm.prank(payer);
        vm.expectRevert(abi.encodeWithSelector(InvoicePayment.InvoiceNotFound.selector, uint256(99)));
        payment.pay(99, 1);
    }

    function _create(address who, address invoicePayer, uint256 amount, bool allowPartial) internal returns (uint256 id) {
        vm.prank(who);
        id = payment.createInvoice(invoicePayer, amount, uint64(block.timestamp + 7 days), allowPartial, _ref());
    }

    function _ref() internal returns (bytes32) {
        return keccak256(abi.encodePacked("order", ++seq));
    }
}
