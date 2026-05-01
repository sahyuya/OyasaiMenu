package com.github.sahyuya.oyasaiMenu.command

import com.github.sahyuya.oyasaiMenu.OyasaiMenu
import com.github.sahyuya.oyasaiMenu.util.GuiUtil.c
import io.papermc.paper.command.brigadier.BasicCommand
import io.papermc.paper.command.brigadier.CommandSourceStack
import org.bukkit.entity.Player

/**
 * /macro                         → マクロ一覧GUIを開く
 * /macro <id>                    → 指定IDのマクロを直接実行
 * /macro run <id>                → 同上 (明示的)
 * /macro <id> <番号> <コマンド>  → 指定インデックスにコマンドをセット/追加
 * /macro <id> <番号> remove      → 指定インデックスのコマンドを削除
 */
@Suppress("UnstableApiUsage")
class MacroCommand(private val plugin: OyasaiMenu) : BasicCommand {
    override fun execute(source: CommandSourceStack, args: Array<out String>) {
        val player = source.sender as? Player ?: run { source.sender.sendMessage(c("&cゲーム内から実行してください。")); return }
        if (!player.hasPermission("oyasaimenu.macro")) { player.sendMessage(c("&cこのコマンドを使う権限がありません。")); return }
        when {
            args.isEmpty() -> plugin.macroEngine.openMacroList(player)
            args[0].equals("run", ignoreCase = true) && args.size >= 2 -> {
                val err = plugin.macroManager.executeMacro(player, args[1])
                if (err != null) player.sendMessage(c("&c$err"))
            }
            args.size >= 3 && args[1].toIntOrNull() != null -> {
                val macroId = args[0]; val index = args[1].toInt() - 1
                val value = args.drop(2).joinToString(" ")
                if (plugin.macroManager.getMacro(player.uniqueId, macroId) == null) { player.sendMessage(c("&cマクロ '&e$macroId&c' が見つかりません。")); return }
                if (value.equals("remove", ignoreCase = true)) {
                    val err = plugin.macroManager.removeCommandAtIndex(player.uniqueId, macroId, index)
                    if (err != null) player.sendMessage(c("&c$err")) else player.sendMessage(c("&a'$macroId' の ${index+1} 番目を削除しました。"))
                } else {
                    val err = plugin.macroManager.setCommandAtIndex(player.uniqueId, macroId, index, value)
                    if (err != null) player.sendMessage(c("&c$err")) else player.sendMessage(c("&a'$macroId' の ${index+1} 番目を設定: &f$value"))
                }
            }
            args.size == 2 -> { player.sendMessage(c("&7使い方: &f/macro &e<id> &b<番号> &f<コマンド>")); player.sendMessage(c("&7例: &f/macro home 1 //wand")) }
            else -> {
                val err = plugin.macroManager.executeMacro(player, args[0])
                if (err != null) { player.sendMessage(c("&c$err")); player.sendMessage(c("&7GUIから管理: §e/macro")) }
            }
        }
    }
    override fun suggest(source: CommandSourceStack, args: Array<out String>): List<String> {
        val player = source.sender as? Player ?: return emptyList()
        val prefix = args.lastOrNull() ?: ""
        return when (args.size) {
            0, 1 -> (listOf("run") + plugin.macroManager.getMacros(player.uniqueId).map { it.id }).filter { it.startsWith(prefix, ignoreCase = true) }
            2 -> when {
                args[0].equals("run", ignoreCase = true) -> plugin.macroManager.getMacros(player.uniqueId).map { it.id }.filter { it.startsWith(prefix, ignoreCase = true) }
                plugin.macroManager.getMacro(player.uniqueId, args[0]) != null -> {
                    val macro = plugin.macroManager.getMacro(player.uniqueId, args[0])!!
                    ((1..maxOf(macro.commands.size + 1, 1)).map { it.toString() } + listOf("remove")).filter { it.startsWith(prefix, ignoreCase = true) }
                }
                else -> emptyList()
            }
            3 -> when {
                args[1].toIntOrNull() != null -> listOf("wait 1s","wait 0.5s","remove").filter { it.startsWith(prefix, ignoreCase = true) }
                else -> emptyList()
            }
            else -> emptyList()
        }
    }
}