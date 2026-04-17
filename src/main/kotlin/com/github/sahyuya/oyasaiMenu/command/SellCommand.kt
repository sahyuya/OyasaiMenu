package com.github.sahyuya.oyasaiMenu.command

import com.github.sahyuya.oyasaiMenu.OyasaiMenu
import io.papermc.paper.command.brigadier.BasicCommand
import io.papermc.paper.command.brigadier.CommandSourceStack
import org.bukkit.entity.Player

/**
 * SellCommand
 *
 * /sell コマンドのハンドラ。
 * 一括売却GUIを開く。
 *
 * 使い方:
 *   /sell       → 一括売却GUIを開く
 *   /sell hand  → 手持ちアイテムをその場で即時売却 (GUIを開かず)
 *   /sell all   → インベントリ内の全登録アイテムをその場で即時売却
 */
@Suppress("UnstableApiUsage")
class SellCommand(private val plugin: OyasaiMenu) : BasicCommand {

    override fun execute(source: CommandSourceStack, args: Array<out String>) {
        val player = source.sender as? Player
            ?: run { source.sender.sendMessage("§cゲーム内から実行してください。"); return }

        if (!player.hasPermission("visualmenu.use")) {
            player.sendMessage(cc("&cこのコマンドを使う権限がありません。"))
            return
        }

        when (args.getOrNull(0)?.lowercase()) {
            // /sell hand: 手持ちを即時売却
            "hand" -> sellHand(player)
            // /sell all: インベントリ全体を即時売却
            "all"  -> sellAll(player)
            // /sell: GUI を開く
            else   -> plugin.sellMenuEngine.openSellMenu(player)
        }
    }

    override fun suggest(source: CommandSourceStack, args: Array<out String>): List<String> {
        if (args.size != 1) return emptyList()
        return listOf("hand", "all").filter { it.startsWith(args[0], ignoreCase = true) }
    }

    // ============================
    // 即時売却処理
    // ============================

    /** /sell hand: MainHandのアイテムをその場で売却 */
    private fun sellHand(player: Player) {
        val item = player.inventory.itemInMainHand
        if (item.type.isAir) {
            player.sendMessage(cc("&c手に何も持っていません。"))
            return
        }
        val price = plugin.shopConfigLoader.getSellPrice(item.type)
        if (price == null || price <= 0) {
            player.sendMessage(cc("&c${item.type.name.lowercase()} は売却登録されていません。"))
            return
        }
        val amount = item.amount
        val total  = price * amount
        plugin.sellMenuEngine  // SellMenuEngine の deposit を直接呼ばずに EconomyManager 経由
        com.github.sahyuya.oyasaiMenu.shop.EconomyManager.deposit(player, total)
        player.inventory.setItemInMainHand(null)
        player.sendMessage(cc(
            "&b売却: &f${item.type.name.lowercase()} ×${amount}\n" +
                    "&7+${com.github.sahyuya.oyasaiMenu.shop.EconomyManager.format(total)} → " +
                    "残高: &f${com.github.sahyuya.oyasaiMenu.shop.EconomyManager.format(com.github.sahyuya.oyasaiMenu.shop.EconomyManager.getBalance(player))}"
        ))
        player.playSound(player.location, org.bukkit.Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1f, 0.8f)
    }

    /** /sell all: インベントリ内の登録アイテムを全て売却 */
    private fun sellAll(player: Player) {
        var totalEarned = 0.0
        var totalCount  = 0

        player.inventory.contents.forEachIndexed { i, stack ->
            if (stack == null || stack.type.isAir) return@forEachIndexed
            val price = plugin.shopConfigLoader.getSellPrice(stack.type) ?: return@forEachIndexed
            if (price <= 0) return@forEachIndexed
            totalEarned += price * stack.amount
            totalCount  += stack.amount
            player.inventory.setItem(i, null)
        }

        if (totalCount == 0) {
            player.sendMessage(cc("&c売却できるアイテムがありませんでした。"))
            return
        }

        com.github.sahyuya.oyasaiMenu.shop.EconomyManager.deposit(player, totalEarned)
        player.sendMessage(cc(
            "&a一括売却 (全インベントリ)!\n" +
                    "&7売却数: &f${totalCount}個  獲得: &f${com.github.sahyuya.oyasaiMenu.shop.EconomyManager.format(totalEarned)}\n" +
                    "&7残高: &f${com.github.sahyuya.oyasaiMenu.shop.EconomyManager.format(com.github.sahyuya.oyasaiMenu.shop.EconomyManager.getBalance(player))}"
        ))
        player.playSound(player.location, org.bukkit.Sound.ENTITY_PLAYER_LEVELUP, 1f, 1f)
    }

    private fun cc(text: String) = text.replace('&', '\u00A7')
}