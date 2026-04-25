package com.github.sahyuya.oyasaiMenu.command

import com.github.sahyuya.oyasaiMenu.OyasaiMenu
import io.papermc.paper.command.brigadier.BasicCommand
import io.papermc.paper.command.brigadier.CommandSourceStack
import org.bukkit.entity.Player

@Suppress("UnstableApiUsage")
class MenuCommand(private val plugin: OyasaiMenu) : BasicCommand {
    override fun execute(source: CommandSourceStack, args: Array<out String>) {
        val player = source.sender as? Player
            ?: run { source.sender.sendMessage("§cゲーム内から実行してください。"); return }
        if (!player.hasPermission("oyasaimenu.use")) {
            player.sendMessage(plugin.menuEngine.colorize("&cこのコマンドを使う権限がありません。")); return
        }
        when {
            args.isEmpty() -> {
                val defaultId = plugin.config.getString("menu.default", "root") ?: "root"
                plugin.menuEngine.openMenu(player, defaultId)
            }
            args[0].equals("reload", ignoreCase = true) -> {
                if (!player.hasPermission("oyasaimenu.admin")) {
                    player.sendMessage(plugin.menuEngine.colorize("&cリロード権限がありません。")); return
                }
                plugin.reload()
                player.sendMessage(plugin.menuEngine.colorize("&aリロードしました。(${plugin.menuLoader.getMenuCount()} 個)"))
            }
            args[0].equals("list", ignoreCase = true) -> {
                if (!player.hasPermission("oyasaimenu.admin")) {
                    player.sendMessage(plugin.menuEngine.colorize("&cこのサブコマンドは管理者のみ使用できます。")); return
                }
                val ids = plugin.menuLoader.getAllMenuIds().sorted()
                player.sendMessage(plugin.menuEngine.colorize("&b--- ロード済みメニュー (${ids.size}) ---"))
                ids.forEach { player.sendMessage(plugin.menuEngine.colorize("&7- $it")) }
            }
            else -> plugin.menuEngine.openMenu(player, args[0].lowercase())
        }
    }
    override fun suggest(source: CommandSourceStack, args: Array<out String>): List<String> {
        source.sender as? Player ?: return emptyList()
        if (args.size > 1) return emptyList()
        val prefix = args.firstOrNull() ?: ""
        return (listOf("reload", "list") + plugin.menuLoader.getAllMenuIds())
            .filter { it.startsWith(prefix, ignoreCase = true) }
    }
}