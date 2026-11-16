// SPDX-License-Identifier: MIT
pragma solidity 0.8.24;

import {ERC20} from "@openzeppelin/contracts/token/ERC20/ERC20.sol";

/// @notice 本地 Anvil 和单元测试用的 tUSD8。任何人都能 mint。
/// 不要把它部署到 Aevum。正式结算币是官方 tUSD8。
contract MockTUSD8 is ERC20 {
    constructor() ERC20("Test USD 8", "tUSD8") {}

    function decimals() public pure override returns (uint8) {
        return 6;
    }

    function mint(address to, uint256 amount) external {
        _mint(to, amount);
    }
}
