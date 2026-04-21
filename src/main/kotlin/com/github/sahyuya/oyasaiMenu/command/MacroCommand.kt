package com.github.sahyuya.oyasaiMenu.command

import com.github.sahyuya.oyasaiMenu.OyasaiMenu
import io.papermc.paper.command.brigadier.BasicCommand
import io.papermc.paper.command.brigadier.CommandSourceStack
import org.bukkit.entity.Player

/**
 * MacroCommand
 *
 * /macro コマンドのハンドラ。
 *
 * 使い方:
 *   /macro          → マクロ一覧GUIを開く
 *   /macro <id>     → 指定IDのマクロを直接実行
 *   /macro run <id> → 同上 (明示的)
 */
@Suppress("UnstableApiUsage")
class MacroCommand(private val plugin: OyasaiMenu) : BasicCommand {

    override fun execute(source: CommandSourceStack, args: Array<out String>) {
        val player = source.sender as? Player
            ?: run { source.sender.sendMessage("§cゲーム内から実行してください。"); return }

        if (!player.hasPermission("visualmenu.use")) {
            player.sendMessage("§cこのコマンドを使う権限がありません。"); return
        }

        when {
            args.isEmpty() -> {
                plugin.macroEngine.openMacroList(player)
            }
            args[0].equals("run", ignoreCase = true) && args.size >= 2 -> {
                val id = args[1]
                val err = plugin.macroManager.executeMacro(player, id)
                if (err != null) player.sendMessage("§c$err")
            }
            else -> {
                // /macro <id> で直接実行
                val id = args[0]
                val err = plugin.macroManager.executeMacro(player, id)
                if (err != null) {
                    player.sendMessage("§c$err")
                    player.sendMessage("§7GUIから管理: §e/macro")
                }
            }
        }
    }

    override fun suggest(source: CommandSourceStack, args: Array<out String>): List<String> {
        val player = source.sender as? Player ?: return emptyList()
        if (args.size != 1) return emptyList()
        return (listOf("run") + plugin.macroManager.getMacros(player.uniqueId).map { it.id })
            .filter { it.startsWith(args[0], ignoreCase = true) }
    }
}