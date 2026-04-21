package com.github.sahyuya.oyasaiMenu.command

import com.github.sahyuya.oyasaiMenu.OyasaiMenu
import io.papermc.paper.command.brigadier.BasicCommand
import io.papermc.paper.command.brigadier.CommandSourceStack
import org.bukkit.entity.Player

@Suppress("UnstableApiUsage")
class ShopCommand(private val plugin: OyasaiMenu) : BasicCommand {

    override fun execute(source: CommandSourceStack, args: Array<out String>) {
        val player = source.sender as? Player
            ?: run { source.sender.sendMessage("§cゲーム内から実行してください。"); return }
        if (!player.hasPermission("visualmenu.use")) {
            player.sendMessage("§cこのコマンドを使う権限がありません。"); return
        }
        if (player.gameMode == org.bukkit.GameMode.CREATIVE) {
            player.sendMessage("§cクリエイティブモードではショップを使用できません。"); return
        }
        if (args.isEmpty()) { plugin.menuEngine.openMenu(player, "shop/index"); return }

        val input = args[0].lowercase()
        val category = plugin.shopLoader.getAllCategories().values.find { cat ->
            cat.id.equals(input, ignoreCase = true) ||
                    cat.command?.equals(input, ignoreCase = true) == true
        }
        if (category == null) {
            player.sendMessage("§c'$input' が見つかりません。利用可能: §f${getNames().joinToString(", ")}")
            return
        }
        plugin.shopEngine.openShop(player, category.id)
    }

    override fun suggest(source: CommandSourceStack, args: Array<out String>): List<String> {
        if (args.size != 1) return emptyList()
        return getNames().filter { it.startsWith(args[0], ignoreCase = true) }
    }

    private fun getNames(): List<String> =
        plugin.shopLoader.getAllCategories().values
            .flatMap { listOfNotNull(it.id, it.command) }.distinct().sorted()
}