// SPDX-License-Identifier: MIT
pragma solidity 0.8.24;

import {Script, console2} from "forge-std/Script.sol";
import {InvoicePayment} from "../src/InvoicePayment.sol";
import {MockTUSD8} from "../src/mocks/MockTUSD8.sol";

/// @notice 本地 Anvil：部署可增发的 tUSD8，再部署 InvoicePayment。
/// 拒绝 chainId 44005，避免把 mock 代币部署到 Aevum。
contract DeployLocal is Script {
    address internal constant DEFAULT_MERCHANT = 0x70997970C51812dc3A010C7d01b50e0d17dc79C8;
    address internal constant DEFAULT_PAYER = 0x3C44CdDdB6a900fa2b585dd299e03d12FA4293BC;

    function run() external returns (InvoicePayment payment) {
        if (block.chainid == 44005) {
            revert("DeployLocal refuses Aevum testnet; use script/Deploy.s.sol");
        }

        address merchant = vm.envOr("MERCHANT_ADDRESS", DEFAULT_MERCHANT);
        address payer = vm.envOr("PAYER_ADDRESS", DEFAULT_PAYER);
        uint256 mintAmount = vm.envOr("MINT_AMOUNT", uint256(1_000_000e6));
        uint256 pk = vm.envUint("DEPLOYER_PRIVATE_KEY");
        address owner = vm.addr(pk);

        vm.startBroadcast(pk);
        MockTUSD8 token = new MockTUSD8();
        token.mint(merchant, mintAmount);
        token.mint(payer, mintAmount);
        payment = new InvoicePayment(token, owner);
        console2.log("MOCK_TUSD8", address(token));
        console2.log("INVOICE_PAYMENT", address(payment));
        console2.log("OWNER", owner);
        console2.log("MERCHANT", merchant);
        console2.log("PAYER", payer);
        console2.log("CHAIN_ID", block.chainid);
        vm.stopBroadcast();
    }
}
