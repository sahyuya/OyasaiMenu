package com.github.sahyuya.oyasaiMenu.command

import com.github.sahyuya.oyasaiMenu.OyasaiMenu
import io.papermc.paper.command.brigadier.BasicCommand
import io.papermc.paper.command.brigadier.CommandSourceStack
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import java.io.File

/**
 * /menuedit                              → デフォルトメニューを編集モードで開く
 * /menuedit <id>                         → 指定IDのメニューを編集モードで開く
 * /menuedit create <id> [タイトル]       → 新規YAML作成
 * /menuedit announce title <text|JSON>   → お知らせタイトルをセット
 * /menuedit announce line <番号> <text>  → お知らせloreの指定行をセット
 * /menuedit announce remove-line <番号>  → お知らせloreの指定行を削除
 * /menuedit announce book                → 本と羽ペンでloreを編集
 * /menuedit announce show                → 現在の内容を確認
 */
@Suppress("UnstableApiUsage")
class MenuAdminCommand(private val plugin: OyasaiMenu) : BasicCommand {

    override fun execute(source: CommandSourceStack, args: Array<out String>) {
        val player = source.sender as? Player
            ?: run { source.sender.sendMessage("§cこのコマンドはゲーム内から実行してください。"); return }
        if (!player.hasPermission("oyasaimenu.admin")) {
            player.sendMessage(plugin.menuEngine.colorize("&c編集権限がありません。")); return
        }
        when {
            args.isNotEmpty() && args[0].equals("announce", ignoreCase = true) ->
                handleAnnounce(player, args.drop(1).toTypedArray())
            args.isNotEmpty() && args[0].equals("create", ignoreCase = true) -> {
                if (args.size < 2) { player.sendMessage(plugin.menuEngine.colorize("&c使い方: /menuedit create <id> [タイトル]")); return }
                val newId = args[1].lowercase()
                val title = if (args.size > 2) args.drop(2).joinToString(" ") else "&8$newId"
                createMenuFile(player, newId, title)
            }
            args.isEmpty() -> {
                val id = plugin.config.getString("menu.default", "root") ?: "root"
                plugin.menuEngine.openMenuInEditMode(player, id)
                player.sendMessage(plugin.menuEngine.colorize("&a編集モード開始: &e$id"))
            }
            else -> {
                val menuId = args[0].lowercase()
                if (plugin.menuLoader.getMenu(menuId) == null) {
                    player.sendMessage(plugin.menuEngine.colorize("&c'&e$menuId&c' が見つかりません。\n&7作成: &f/menuedit create $menuId")); return
                }
                plugin.menuEngine.openMenuInEditMode(player, menuId)
                player.sendMessage(plugin.menuEngine.colorize("&a編集モード開始: &e$menuId"))
            }
        }
    }

    private fun handleAnnounce(player: Player, args: Array<String>) {
        if (!player.hasPermission("oyasaimenu.admin")) {
            player.sendMessage(plugin.menuEngine.colorize("&c権限 (oyasaimenu.admin) が必要です。")); return
        }
        when (args.getOrNull(0)?.lowercase()) {
            "title" -> {
                if (args.size < 2) { player.sendMessage(plugin.menuEngine.colorize("&c使い方: /menuedit announce title <テキスト or JSON>")); return }
                val text = parseTextInput(args.drop(1).joinToString(" "))
                plugin.announcementManager.setTitle(text)
                player.sendMessage(plugin.menuEngine.colorize("&aタイトルを更新しました。"))
            }
            "line" -> {
                if (args.size < 3) { player.sendMessage(plugin.menuEngine.colorize("&c使い方: /menuedit announce line <番号> <テキスト or JSON>")); return }
                val lineNum = args[1].toIntOrNull()?.minus(1)
                if (lineNum == null || lineNum < 0) { player.sendMessage(plugin.menuEngine.colorize("&c番号は 1 以上の整数で指定してください。")); return }
                val text = parseTextInput(args.drop(2).joinToString(" "))
                plugin.announcementManager.setLine(lineNum, text)
                player.sendMessage(plugin.menuEngine.colorize("&a${lineNum + 1} 行目を更新しました。"))
            }
            "remove-line" -> {
                val lineNum = args.getOrNull(1)?.toIntOrNull()?.minus(1)
                if (lineNum == null || lineNum < 0) { player.sendMessage(plugin.menuEngine.colorize("&c使い方: /menuedit announce remove-line <番号>")); return }
                val err = plugin.announcementManager.removeLine(lineNum)
                if (err != null) player.sendMessage(plugin.menuEngine.colorize("&c$err"))
                else player.sendMessage(plugin.menuEngine.colorize("&a${lineNum + 1} 行目を削除しました。"))
            }
            "book" -> {
                player.closeInventory()
                Bukkit.getScheduler().runTaskLater(plugin, Runnable {
                    plugin.announcementManager.openBookEditor(player)
                }, 1L)
            }
            "show" -> {
                val ann = plugin.announcementManager.getAnnouncements().firstOrNull()
                if (ann == null) { player.sendMessage(plugin.menuEngine.colorize("&7お知らせが設定されていません。")); return }
                player.sendMessage(plugin.menuEngine.colorize("&b=== 現在のお知らせ ==="))
                player.sendMessage(plugin.menuEngine.colorize("&7タイトル: ${ann.title}"))
                ann.body.forEachIndexed { i, line -> player.sendMessage(plugin.menuEngine.colorize("&7${i+1}行目: $line")) }
            }
            else -> {
                player.sendMessage(plugin.menuEngine.colorize("&b--- /menuedit announce ---"))
                player.sendMessage(plugin.menuEngine.colorize("&f/menuedit announce title &7<テキスト|JSON>"))
                player.sendMessage(plugin.menuEngine.colorize("&f/menuedit announce line &7<番号> <テキスト|JSON>"))
                player.sendMessage(plugin.menuEngine.colorize("&f/menuedit announce remove-line &7<番号>"))
                player.sendMessage(plugin.menuEngine.colorize("&f/menuedit announce book &7— 本と羽ペンで編集"))
                player.sendMessage(plugin.menuEngine.colorize("&f/menuedit announce show &7— 現在の内容を確認"))
            }
        }
    }

    private fun parseTextInput(raw: String): String {
        val trimmed = raw.trim()
        if (!trimmed.startsWith("{")) return trimmed
        return runCatching {
            LegacyComponentSerializer.legacyAmpersand().serialize(GsonComponentSerializer.gson().deserialize(trimmed))
        }.getOrElse { trimmed }
    }

    private fun createMenuFile(player: Player, menuId: String, title: String) {
        val file = File(plugin.dataFolder, "menus/${menuId}.yml")
        if (file.exists()) { player.sendMessage(plugin.menuEngine.colorize("&c'$menuId' は既に存在します。")); return }
        file.parentFile.mkdirs()
        file.writeText(buildString {
            appendLine("menu:"); appendLine("  id: $menuId"); appendLine("  title: \"$title\""); appendLine("  size: 54")
            appendLine(); appendLine("items:"); appendLine("  back:"); appendLine("    slot: 49"); appendLine("    icon: ARROW")
            appendLine("    name: \"&c← 戻る\""); appendLine("    lore:"); appendLine("      - \"&7前のメニューに戻ります\"")
            appendLine("    actions:"); appendLine("      - type: close_menu")
        }, Charsets.UTF_8)
        plugin.menuLoader.loadAll()
        player.sendMessage(plugin.menuEngine.colorize("&a新規メニュー作成: &e$menuId"))
        plugin.menuEngine.openMenuInEditMode(player, menuId)
    }

    override fun suggest(source: CommandSourceStack, args: Array<out String>): List<String> {
        val prefix = args.lastOrNull() ?: ""
        return when {
            args.size <= 1 -> (listOf("create","announce") + plugin.menuLoader.getAllMenuIds()).filter { it.startsWith(prefix, ignoreCase = true) }
            args.size == 2 && args[0].equals("announce", ignoreCase = true) ->
                listOf("title","line","remove-line","book","show").filter { it.startsWith(prefix, ignoreCase = true) }
            args.size == 3 && args[0].equals("announce", ignoreCase = true) && args[1].equals("line", ignoreCase = true) -> {
                val maxLine = (plugin.announcementManager.getAnnouncements().firstOrNull()?.body?.size ?: 0) + 1
                (1..maxLine).map { it.toString() }.filter { it.startsWith(prefix) }
            }
            args.size == 3 && args[0].equals("announce", ignoreCase = true) && args[1].equals("remove-line", ignoreCase = true) -> {
                val size = plugin.announcementManager.getAnnouncements().firstOrNull()?.body?.size ?: 0
                (1..size).map { it.toString() }.filter { it.startsWith(prefix) }
            }
            else -> emptyList()
        }
    }
}