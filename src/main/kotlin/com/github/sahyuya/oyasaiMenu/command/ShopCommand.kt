package com.github.sahyuya.oyasaiMenu.command

import com.github.sahyuya.oyasaiMenu.OyasaiMenu
import io.papermc.paper.command.brigadier.BasicCommand
import io.papermc.paper.command.brigadier.CommandSourceStack
import org.bukkit.entity.Player

/**
 * ShopCommand
 *
 * /shop と /sh のハンドラ。
 * Paper BasicCommand で実装。
 *
 * 使い方:
 *   /shop            → ショップ一覧メニュー (shop/index) を開く
 *   /shop <category> → 指定カテゴリを開く (例: /shop blocks, /shop ores)
 *   /sh <category>   → 同上 (短縮エイリアス)
 *
 * カテゴリ名は shops.yml のトップレベルキー、または
 * "command:" フィールドで指定したショートカット名に対応する。
 *
 * 例 (shops.yml):
 *   blocks:
 *     command: 'bl'   → /shop bl でもアクセス可能
 */
@Suppress("UnstableApiUsage")
class ShopCommand(private val plugin: OyasaiMenu) : BasicCommand {

    override fun execute(source: CommandSourceStack, args: Array<out String>) {
        val player = source.sender as? Player
            ?: run { source.sender.sendMessage("§cゲーム内から実行してください。"); return }

        if (!player.hasPermission("visualmenu.use")) {
            player.sendMessage(cc("&cこのコマンドを使う権限がありません。"))
            return
        }

        if (args.isEmpty()) {
            // 引数なし → ショップ一覧メニューを開く
            plugin.menuEngine.openMenu(player, "shop/index")
            return
        }

        val input = args[0].lowercase()
        // カテゴリIDまたはショートカットコマンドで検索
        val category = plugin.shopConfigLoader.getAllCategories().values.find { cat ->
            cat.id.equals(input, ignoreCase = true) ||
                    cat.command?.equals(input, ignoreCase = true) == true
        }

        if (category == null) {
            player.sendMessage(cc("&cカテゴリ '&e$input&c' が見つかりません。"))
            player.sendMessage(cc("&7利用可能: &f${getAvailableNames().joinToString(", ")}"))
            return
        }

        plugin.shopEngine.openShop(player, category.id)
    }

    override fun suggest(source: CommandSourceStack, args: Array<out String>): List<String> {
        if (args.size != 1) return emptyList()
        return getAvailableNames().filter { it.startsWith(args[0], ignoreCase = true) }
    }

    /** ID + command フィールドの両方を候補として返す */
    private fun getAvailableNames(): List<String> {
        val names = mutableListOf<String>()
        plugin.shopConfigLoader.getAllCategories().values.forEach { cat ->
            names.add(cat.id)
            cat.command?.let { names.add(it) }
        }
        return names.distinct().sorted()
    }

    private fun cc(text: String) = text.replace('&', '\u00A7')
}