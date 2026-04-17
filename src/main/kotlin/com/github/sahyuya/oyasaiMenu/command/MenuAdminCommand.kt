package com.github.sahyuya.oyasaiMenu.command

import com.github.sahyuya.oyasaiMenu.OyasaiMenu
import io.papermc.paper.command.brigadier.BasicCommand
import io.papermc.paper.command.brigadier.CommandSourceStack
import org.bukkit.entity.Player
import java.io.File

/**
 * MenuAdminCommand
 *
 * /menuedit コマンドのハンドラ。
 * paper-plugin.yml 方式のため BasicCommand を実装している。
 *
 * サブコマンド:
 *   /menuedit                    → デフォルトメニューを編集モードで開く
 *   /menuedit <id>               → 指定IDのメニューを編集モードで開く
 *   /menuedit create <id> [タイトル] → 新規YAMLを作成して編集モードで開く
 */
@Suppress("UnstableApiUsage")
class MenuAdminCommand(private val plugin: OyasaiMenu) : BasicCommand {

    override fun execute(source: CommandSourceStack, args: Array<out String>) {
        val player = source.sender as? Player
            ?: run { source.sender.sendMessage("§cこのコマンドはゲーム内から実行してください。"); return }

        if (!player.hasPermission("visualmenu.edit")) {
            player.sendMessage(plugin.menuEngine.colorize("&c編集権限がありません。"))
            return
        }

        when {
            args.isEmpty() -> {
                val id = plugin.config.getString("menu.default", "root") ?: "root"
                plugin.menuEngine.openMenuInEditMode(player, id)
                player.sendMessage(plugin.menuEngine.colorize(
                    "&a編集モード開始: &e$id  &7(右クリック=詳細編集 / 左クリック=選択)"
                ))
            }

            args[0].equals("create", ignoreCase = true) -> {
                if (args.size < 2) {
                    player.sendMessage(plugin.menuEngine.colorize("&c使い方: /menuedit create <id> [タイトル]"))
                    return
                }
                val newId = args[1].lowercase()
                val title = if (args.size > 2) args.drop(2).joinToString(" ") else "&8$newId"
                createMenuFile(player, newId, title)
            }

            else -> {
                val menuId = args[0].lowercase()
                if (plugin.menuConfigLoader.getMenu(menuId) == null) {
                    player.sendMessage(plugin.menuEngine.colorize(
                        "&c'&e$menuId&c' が見つかりません。\n" +
                                "&7作成: &f/menuedit create $menuId"
                    ))
                    return
                }
                plugin.menuEngine.openMenuInEditMode(player, menuId)
                player.sendMessage(plugin.menuEngine.colorize("&a編集モード開始: &e$menuId"))
            }
        }
    }

    override fun suggest(source: CommandSourceStack, args: Array<out String>): List<String> {
        if (args.size != 1) return emptyList()
        val candidates = mutableListOf("create")
        candidates += plugin.menuConfigLoader.getAllMenuIds()
        return candidates.filter { it.startsWith(args[0], ignoreCase = true) }
    }

    // ============================
    // 新規メニューファイル生成
    // ============================

    /**
     * 指定IDに対応する YAML ファイルを menus/ に新規作成する。
     * IDに "/" が含まれる場合はサブディレクトリを自動生成する。
     * 既存ファイルの上書きは行わない。
     */
    private fun createMenuFile(player: Player, menuId: String, title: String) {
        val file = File(plugin.dataFolder, "menus/${menuId}.yml")

        if (file.exists()) {
            player.sendMessage(plugin.menuEngine.colorize("&c'$menuId' は既に存在します。"))
            return
        }
        file.parentFile.mkdirs()

        // 最小限のテンプレートで YAML を生成する
        file.writeText(buildString {
            appendLine("menu:")
            appendLine("  id: $menuId")
            appendLine("  title: \"$title\"")
            appendLine("  size: 54")
            appendLine()
            appendLine("items:")
            appendLine("  back:")
            appendLine("    slot: 49")
            appendLine("    icon: ARROW")
            appendLine("    name: \"&c← 戻る\"")
            appendLine("    lore:")
            appendLine("      - \"&7前のメニューに戻ります\"")
            appendLine("    actions:")
            appendLine("      - type: close_menu")
        }, Charsets.UTF_8)

        // 作成したファイルをリロードして即座に認識させる
        plugin.menuConfigLoader.loadAll()
        player.sendMessage(plugin.menuEngine.colorize("&a新規メニュー作成: &e$menuId"))
        plugin.menuEngine.openMenuInEditMode(player, menuId)
    }
}