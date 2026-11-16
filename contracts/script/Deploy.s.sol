// SPDX-License-Identifier: MIT
pragma solidity 0.8.24;

import {Script, console2} from "forge-std/Script.sol";
import {IERC20} from "@openzeppelin/contracts/token/ERC20/IERC20.sol";
import {InvoicePayment} from "../src/InvoicePayment.sol";

/// @notice 部署到 Aevum 测试网（或任何已经有 tUSD8 的链）。
/// 私钥来自环境变量 DEPLOYER_PRIVATE_KEY，不写进仓库。
/// 也可以不设这个变量，改用 `forge script --account <keystore>`，脚本会走默认广播账户。
contract Deploy is Script {
    address internal constant AEVUM_TUSD8 = 0x456569838f1d43009A91ebA4263147Afd7233483;

    function run() external returns (InvoicePayment payment) {
        address token = vm.envOr("TUSD8_ADDRESS", AEVUM_TUSD8);
        // 广播交易的发送者是私钥对应的地址。脚本里的 msg.sender 是 Foundry 的默认调用者，
        // 两者不一样，所以 owner 用 vm.addr(pk)，不要用 msg.sender。
        uint256 pk = vm.envUint("DEPLOYER_PRIVATE_KEY");
        address owner = vm.addr(pk);
        vm.startBroadcast(pk);
        payment = new InvoicePayment(IERC20(token), owner);
        console2.log("INVOICE_PAYMENT", address(payment));
        console2.log("TOKEN", token);
        console2.log("OWNER", owner);
        console2.log("CHAIN_ID", block.chainid);
        vm.stopBroadcast();
    }
}
