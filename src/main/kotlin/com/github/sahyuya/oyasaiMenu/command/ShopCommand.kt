package com.github.sahyuya.oyasaiMenu.command

import com.github.sahyuya.oyasaiMenu.OyasaiMenu
import io.papermc.paper.command.brigadier.BasicCommand
import io.papermc.paper.command.brigadier.CommandSourceStack
import org.bukkit.entity.Player

/**
 * ShopCommand
 *
 * /shop (/sh) コマンド。
 * 引数なし → shop_index ポップアップを開く
 * 引数あり → カテゴリ or ショートカット別名で直接ショップを開く
 *
 * ショートカット別名:
 *   bl   / blocks      → ブロックショップ
 *   deco / decorations → 装飾ショップ
 *   ore  / ores        → 鉱石ショップ
 *   tool / tools       → ツールショップ
 *   p    / pshop       → ポイントショップ (PointShopEngine)
 */
@Suppress("UnstableApiUsage")
class ShopCommand(private val plugin: OyasaiMenu) : BasicCommand {

    // ショートカット別名 → 実際のカテゴリID または "pshop"
    private val shortcuts: Map<String, String> = mapOf(
        "bl"   to "blocks",
        "deco" to "decorations",
        "ore"  to "ores",
        "tool" to "tools",
        "p"    to "pshop"
    )

    override fun execute(source: CommandSourceStack, args: Array<out String>) {
        val player = source.sender as? Player
            ?: run { source.sender.sendMessage("§cゲーム内から実行してください。"); return }

        if (!player.hasPermission("oyasaimenu.use")) {
            player.sendMessage("§cこのコマンドを使う権限がありません。"); return
        }

        // 引数なし → shop_index ポップアップ
        if (args.isEmpty()) {
            plugin.popupMenuEngine.open(player, "shopindex")
            return
        }

        val input = args[0].lowercase()

        // ポイントショップ (p / pshop) は別エンジン
        if (input == "p" || input == "pshop") {
            if (plugin.pointShopLoader.getAllCategories().isEmpty()) {
                player.sendMessage("§cポイントショップが設定されていません。"); return
            }
            val firstCat = plugin.pointShopLoader.getAllCategories().keys.first()
            plugin.pointShopEngine.openShop(player, firstCat)
            return
        }

        // ショートカット別名を解決
        val resolved = shortcuts[input] ?: input

        // クリエイティブ制限
        if (player.gameMode == org.bukkit.GameMode.CREATIVE) {
            player.sendMessage("§cクリエイティブモードではショップを使用できません。"); return
        }

        // カテゴリ検索 (ID or shops.yml の command フィールド)
        val category = plugin.shopLoader.getAllCategories().values.find { cat ->
            cat.id.equals(resolved, ignoreCase = true) ||
                    cat.command?.equals(resolved, ignoreCase = true) == true
        }

        if (category == null) {
            player.sendMessage("§c'$input' が見つかりません。利用可能: §f${getSuggestions().joinToString(", ")}")
            return
        }
        plugin.shopEngine.openShop(player, category.id)
    }

    override fun suggest(source: CommandSourceStack, args: Array<out String>): List<String> {
        if (args.size != 1) return emptyList()
        return getSuggestions().filter { it.startsWith(args[0], ignoreCase = true) }
    }

    private fun getSuggestions(): List<String> {
        val names = mutableListOf<String>()
        names += shortcuts.keys           // bl, deco, ore, tool, p
        names += plugin.shopLoader.getAllCategories().values.flatMap {
            listOfNotNull(it.id, it.command)
        }
        names += "pshop"
        return names.distinct().sorted()
    }
}