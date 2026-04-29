package com.github.sahyuya.oyasaiMenu.command

import com.github.sahyuya.oyasaiMenu.OyasaiMenu
import io.papermc.paper.command.brigadier.BasicCommand
import io.papermc.paper.command.brigadier.CommandSourceStack
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.entity.Player

/**
 * /menuedit — 管理用コマンド
 *
 * サブコマンド:
 *   /menuedit announce title <text|JSON>
 *   /menuedit announce line <番号> <text|JSON>
 *   /menuedit announce remove-line <番号>
 *   /menuedit announce book
 *   /menuedit announce show
 *
 *   /menuedit shop <category> list
 *   /menuedit shop <category> add <material> <buy> <sell>
 *   /menuedit shop <category> remove <index>
 *
 *   /menuedit blacklist list
 *   /menuedit blacklist add <material>
 *   /menuedit blacklist remove <material>
 */
@Suppress("UnstableApiUsage")
class MenuAdminCommand(private val plugin: OyasaiMenu) : BasicCommand {

    override fun execute(source: CommandSourceStack, args: Array<out String>) {
        val player = source.sender as? Player
            ?: run { source.sender.sendMessage("§cこのコマンドはゲーム内から実行してください。"); return }
        if (!player.hasPermission("oyasaimenu.admin")) {
            player.sendMessage(c("&c編集権限がありません。")); return
        }
        when (args.getOrNull(0)?.lowercase()) {
            "announce"  -> handleAnnounce(player, args.drop(1).toTypedArray())
            "shop"      -> handleShop(player, args.drop(1).toTypedArray())
            "blacklist" -> handleBlacklist(player, args.drop(1).toTypedArray())
            else        -> sendHelp(player)
        }
    }

    // ============================
    // announce
    // ============================

    private fun handleAnnounce(player: Player, args: Array<String>) {
        when (args.getOrNull(0)?.lowercase()) {
            "title" -> {
                if (args.size < 2) { player.sendMessage(c("&c使い方: /menuedit announce title <テキスト|JSON>")); return }
                val text = parseTextInput(args.drop(1).joinToString(" "))
                plugin.announcementManager.setTitle(text)
                player.sendMessage(c("&aタイトルを更新しました。"))
            }
            "line" -> {
                if (args.size < 3) { player.sendMessage(c("&c使い方: /menuedit announce line <番号> <テキスト|JSON>")); return }
                val lineNum = args[1].toIntOrNull()?.minus(1)
                if (lineNum == null || lineNum < 0) { player.sendMessage(c("&c番号は 1 以上の整数で指定してください。")); return }
                val text = parseTextInput(args.drop(2).joinToString(" "))
                plugin.announcementManager.setLine(lineNum, text)
                player.sendMessage(c("&a${lineNum + 1} 行目を更新しました。"))
            }
            "remove-line" -> {
                val lineNum = args.getOrNull(1)?.toIntOrNull()?.minus(1)
                if (lineNum == null || lineNum < 0) { player.sendMessage(c("&c使い方: /menuedit announce remove-line <番号>")); return }
                val err = plugin.announcementManager.removeLine(lineNum)
                if (err != null) player.sendMessage(c("&c$err"))
                else player.sendMessage(c("&a${lineNum + 1} 行目を削除しました。"))
            }
            "book" -> {
                player.closeInventory()
                Bukkit.getScheduler().runTaskLater(plugin, Runnable {
                    plugin.announcementManager.openBookEditor(player)
                }, 1L)
            }
            "show" -> {
                val ann = plugin.announcementManager.getAnnouncements().firstOrNull()
                if (ann == null) { player.sendMessage(c("&7お知らせが設定されていません。")); return }
                player.sendMessage(c("&b=== 現在のお知らせ ==="))
                player.sendMessage(c("&7タイトル: ${ann.title}"))
                ann.body.forEachIndexed { i, line -> player.sendMessage(c("&7${i+1}行目: $line")) }
            }
            else -> {
                player.sendMessage(c("&b--- /menuedit announce ---"))
                player.sendMessage(c("&f/menuedit announce title &7<テキスト|JSON>"))
                player.sendMessage(c("&f/menuedit announce line &7<番号> <テキスト|JSON>"))
                player.sendMessage(c("&f/menuedit announce remove-line &7<番号>"))
                player.sendMessage(c("&f/menuedit announce book &7— 本と羽ペンで編集"))
                player.sendMessage(c("&f/menuedit announce show &7— 現在の内容を確認"))
            }
        }
    }

    // ============================
    // shop
    // ============================

    private fun handleShop(player: Player, args: Array<String>) {
        val categoryId = args.getOrNull(0)?.lowercase()
        if (categoryId == null) {
            player.sendMessage(c("&b--- /menuedit shop ---"))
            player.sendMessage(c("&f/menuedit shop &7<category> list"))
            player.sendMessage(c("&f/menuedit shop &7<category> add <material> <buy> <sell>"))
            player.sendMessage(c("&f/menuedit shop &7<category> remove <index>"))
            player.sendMessage(c("&7カテゴリ一覧: ${plugin.shopLoader.getAllCategories().keys.joinToString(", ")}"))
            return
        }
        val category = plugin.shopLoader.getCategory(categoryId)
        when (args.getOrNull(1)?.lowercase()) {
            "list" -> {
                if (category == null) { player.sendMessage(c("&cカテゴリ '$categoryId' が見つかりません。")); return }
                player.sendMessage(c("&b--- ショップ: $categoryId (${category.items.size}種) ---"))
                category.items.forEachIndexed { i, item ->
                    val name = item.customName ?: item.materialId
                    player.sendMessage(c("&7${i+1}. &f$name &7buy:&a${item.buyPrice} &7sell:&b${item.sellPrice}"))
                }
            }
            "add" -> {
                if (args.size < 5) { player.sendMessage(c("&c使い方: /menuedit shop <category> add <material> <buy> <sell>")); return }
                val matName = args[2].uppercase()
                val buyPrice  = args[3].toDoubleOrNull() ?: run { player.sendMessage(c("&c買値が不正です。")); return }
                val sellPrice = args[4].toDoubleOrNull() ?: run { player.sendMessage(c("&c売値が不正です。")); return }
                val mat = runCatching { Material.valueOf(matName) }.getOrElse {
                    player.sendMessage(c("&c不明なマテリアル: $matName")); return
                }
                val shopFile = plugin.shopLoader.addItem(categoryId, matName.lowercase(), buyPrice, sellPrice)
                if (shopFile != null) {
                    plugin.shopLoader.reload()
                    player.sendMessage(c("&aショップ '$categoryId' に ${mat.name.lowercase()} を追加しました。(buy:$buyPrice / sell:$sellPrice)"))
                } else {
                    player.sendMessage(c("&cカテゴリ '$categoryId' が見つかりません。"))
                }
            }
            "remove" -> {
                if (category == null) { player.sendMessage(c("&cカテゴリ '$categoryId' が見つかりません。")); return }
                val index = args.getOrNull(2)?.toIntOrNull()?.minus(1)
                if (index == null || index < 0 || index >= category.items.size) {
                    player.sendMessage(c("&c番号は 1〜${category.items.size} で指定してください。")); return
                }
                val removed = plugin.shopLoader.removeItem(categoryId, index)
                if (removed != null) {
                    plugin.shopLoader.reload()
                    player.sendMessage(c("&a${index+1}番目のアイテム ($removed) を削除しました。"))
                } else {
                    player.sendMessage(c("&c削除に失敗しました。"))
                }
            }
            else -> {
                player.sendMessage(c("&c使い方: /menuedit shop <category> [list/add/remove]"))
            }
        }
    }

    // ============================
    // blacklist
    // ============================

    private fun handleBlacklist(player: Player, args: Array<String>) {
        when (args.getOrNull(0)?.lowercase()) {
            "list" -> {
                val mats = plugin.sellBlacklistManager.getMaterials()
                if (mats.isEmpty()) { player.sendMessage(c("&7販売ブラックリストは空です。")); return }
                player.sendMessage(c("&b--- 販売ブラックリスト (${mats.size}種) ---"))
                mats.sortedBy { it.name }.forEach { player.sendMessage(c("&7- ${it.name.lowercase()}")) }
            }
            "add" -> {
                val matName = args.getOrNull(1)?.uppercase() ?: run { player.sendMessage(c("&c使い方: /menuedit blacklist add <material>")); return }
                val mat = runCatching { Material.valueOf(matName) }.getOrElse {
                    player.sendMessage(c("&c不明なマテリアル: $matName")); return
                }
                if (plugin.sellBlacklistManager.addMaterial(mat)) {
                    player.sendMessage(c("&a${mat.name.lowercase()} をブラックリストに追加しました。"))
                } else {
                    player.sendMessage(c("&7${mat.name.lowercase()} は既にブラックリストに登録済みです。"))
                }
            }
            "remove" -> {
                val matName = args.getOrNull(1)?.uppercase() ?: run { player.sendMessage(c("&c使い方: /menuedit blacklist remove <material>")); return }
                val mat = runCatching { Material.valueOf(matName) }.getOrElse {
                    player.sendMessage(c("&c不明なマテリアル: $matName")); return
                }
                if (plugin.sellBlacklistManager.removeMaterial(mat)) {
                    player.sendMessage(c("&a${mat.name.lowercase()} をブラックリストから削除しました。"))
                } else {
                    player.sendMessage(c("&7${mat.name.lowercase()} はブラックリストに登録されていません。"))
                }
            }
            else -> {
                player.sendMessage(c("&b--- /menuedit blacklist ---"))
                player.sendMessage(c("&f/menuedit blacklist list &7— 一覧表示"))
                player.sendMessage(c("&f/menuedit blacklist add &7<material> — 追加"))
                player.sendMessage(c("&f/menuedit blacklist remove &7<material> — 削除"))
                player.sendMessage(c("&7カスタム名チェック: config.yml の sell-blacklist.block-renamed"))
            }
        }
    }

    // ============================
    // ヘルプ
    // ============================

    private fun sendHelp(player: Player) {
        player.sendMessage(c("&b&l/menuedit &7— 管理コマンド"))
        player.sendMessage(c("&f/menuedit announce &7— お知らせの編集"))
        player.sendMessage(c("&f/menuedit shop &7— ショップの商品管理"))
        player.sendMessage(c("&f/menuedit blacklist &7— 販売ブラックリスト管理"))
    }

    // ============================
    // ユーティリティ
    // ============================

    private fun parseTextInput(raw: String): String {
        val trimmed = raw.trim()
        if (!trimmed.startsWith("{")) return trimmed
        return runCatching {
            LegacyComponentSerializer.legacyAmpersand().serialize(GsonComponentSerializer.gson().deserialize(trimmed))
        }.getOrElse { trimmed }
    }

    override fun suggest(source: CommandSourceStack, args: Array<out String>): List<String> {
        val prefix = args.lastOrNull() ?: ""
        return when {
            args.size <= 1 ->
                listOf("announce", "shop", "blacklist").filter { it.startsWith(prefix, ignoreCase = true) }
            args.size == 2 -> when (args[0].lowercase()) {
                "announce"  -> listOf("title","line","remove-line","book","show").filter { it.startsWith(prefix, ignoreCase = true) }
                "shop"      -> plugin.shopLoader.getAllCategories().keys.filter { it.startsWith(prefix, ignoreCase = true) }
                "blacklist" -> listOf("list","add","remove").filter { it.startsWith(prefix, ignoreCase = true) }
                else -> emptyList()
            }
            args.size == 3 -> when {
                args[0].equals("announce", ignoreCase = true) && args[1].equals("line", ignoreCase = true) -> {
                    val max = (plugin.announcementManager.getAnnouncements().firstOrNull()?.body?.size ?: 0) + 1
                    (1..max).map { it.toString() }.filter { it.startsWith(prefix) }
                }
                args[0].equals("announce", ignoreCase = true) && args[1].equals("remove-line", ignoreCase = true) -> {
                    val size = plugin.announcementManager.getAnnouncements().firstOrNull()?.body?.size ?: 0
                    (1..size).map { it.toString() }.filter { it.startsWith(prefix) }
                }
                args[0].equals("shop", ignoreCase = true) ->
                    listOf("list","add","remove").filter { it.startsWith(prefix, ignoreCase = true) }
                args[0].equals("blacklist", ignoreCase = true) && (args[1].equals("add", ignoreCase = true) || args[1].equals("remove", ignoreCase = true)) ->
                    plugin.sellBlacklistManager.getMaterials().map { it.name.lowercase() }.filter { it.startsWith(prefix, ignoreCase = true) }
                else -> emptyList()
            }
            else -> emptyList()
        }
    }

    private fun c(t: String) = t.replace('&', '\u00A7')
}