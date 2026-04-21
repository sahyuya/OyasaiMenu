package com.github.sahyuya.oyasaiMenu.command

import com.github.sahyuya.oyasaiMenu.OyasaiMenu
import io.papermc.paper.command.brigadier.BasicCommand
import io.papermc.paper.command.brigadier.CommandSourceStack
import org.bukkit.entity.Player

/**
 * MenuCommand
 *
 * /menu コマンドのハンドラ。
 *
 * ★ paper-plugin.yml を使う場合のコマンド登録について
 *   旧来の plugin.yml では "commands:" セクションに書いて
 *   getCommand("menu")?.setExecutor(...) で登録していた。
 *   しかし paper-plugin.yml では getCommand() は常に null を返す。
 *   そのため OyasaiMenu.kt の lifecycleManager.registerEventHandler(LifecycleEvents.COMMANDS)
 *   で BasicCommand を登録する Paper 1.21 の新方式を採用している。
 *
 * サブコマンド:
 *   /menu           → デフォルトメニューを開く (config.yml の menu.default)
 *   /menu <id>      → 指定IDのメニューを開く
 *   /menu reload    → 全YAMLをリロード (visualmenu.reload 権限が必要)
 *   /menu list      → ロード済みメニューIDを一覧表示 (管理者向けデバッグ)
 */
@Suppress("UnstableApiUsage") // BasicCommand は Paper の experimental API
class MenuCommand(private val plugin: OyasaiMenu) : BasicCommand {

    /**
     * コマンドが実行されたときに呼ばれる。
     * CommandSourceStack からプレイヤーを取得し、
     * プレイヤー以外 (コンソールなど) からの実行は拒否する。
     */
    override fun execute(source: CommandSourceStack, args: Array<out String>) {
        val player = source.sender as? Player
            ?: run { source.sender.sendMessage("§cこのコマンドはゲーム内から実行してください。"); return }

        if (!player.hasPermission("visualmenu.use")) {
            player.sendMessage(plugin.menuEngine.colorize("&cこのコマンドを使う権限がありません。"))
            return
        }

        when {
            args.isEmpty() -> {
                val defaultId = plugin.config.getString("menu.default", "root") ?: "root"
                plugin.menuEngine.openMenu(player, defaultId)
            }

            args[0].equals("reload", ignoreCase = true) -> {
                if (!player.hasPermission("visualmenu.reload")) {
                    player.sendMessage(plugin.menuEngine.colorize("&cリロード権限がありません。"))
                    return
                }
                plugin.reload()
                player.sendMessage(plugin.menuEngine.colorize(
                    "&aリロードしました。(${plugin.menuLoader.getMenuCount()} 個)"
                ))
            }

            args[0].equals("list", ignoreCase = true) -> {
                if (!player.hasPermission("visualmenu.admin")) {
                    player.sendMessage(plugin.menuEngine.colorize("&cこのサブコマンドは管理者のみ使用できます。"))
                    return
                }
                val ids = plugin.menuLoader.getAllMenuIds().sorted()
                player.sendMessage(plugin.menuEngine.colorize("&b--- ロード済みメニュー (${ids.size}) ---"))
                ids.forEach { player.sendMessage(plugin.menuEngine.colorize("&7- $it")) }
            }

            else -> plugin.menuEngine.openMenu(player, args[0].lowercase())
        }
    }

    /**
     * タブ補完。第1引数にサブコマンド名とメニューIDの候補を返す。
     * BasicCommand では suggest() をオーバーライドすることで実装する。
     */
    override fun suggest(source: CommandSourceStack, args: Array<out String>): List<String> {
        val player = source.sender as? Player ?: return emptyList()
        if (args.size != 1) return emptyList()

        val candidates = mutableListOf("reload", "list")
        candidates += plugin.menuLoader.getAllMenuIds()
        return candidates.filter { it.startsWith(args[0], ignoreCase = true) }
    }
}