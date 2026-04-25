package com.github.sahyuya.oyasaiMenu.manager

import com.github.sahyuya.oyasaiMenu.OyasaiMenu
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.bukkit.Material
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerEditBookEvent
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.BookMeta
import java.io.File
import java.util.UUID

/**
 * AnnouncementManager
 *
 * ■ 編集方法 (2種類)
 *
 *   (A) 本と羽ペン (/menuedit announce book)
 *       - 渡された本に内容を書く
 *       - 署名あり → 署名名 = タイトル, 全ページ内容 = Lore
 *       - 署名なし (Done のみ) → Lore のみ更新 / タイトル変更なし
 *       - ページをまたいでも1つのお知らせとして結合される
 *
 *   (B) コマンド
 *       /menuedit announce title <テキスト|JSON>
 *       /menuedit announce line <番号> <テキスト|JSON>
 *       /menuedit announce remove-line <番号>
 *       → いずれも即時 YAML 保存 & 表示更新
 *
 * ■ データ
 *   announcements.yml の先頭1件を「現在のお知らせ」として使用。
 *   MenuEngine は先頭1件をスロット 0〜44 全体に同じ内容で表示する。
 */
class AnnouncementManager(private val plugin: OyasaiMenu) : Listener {

    data class Announcement(val title: String, val body: List<String>)

    // インメモリ (先頭1件を直接操作)
    private var currentTitle: String             = "&e✦ ようこそ！"
    private var currentBody: MutableList<String> = mutableListOf()

    // 本エディタ待機中の UUID
    private val pendingBookEditors: MutableSet<UUID> = mutableSetOf()

    private val plainText = PlainTextComponentSerializer.plainText()

    // ============================
    // ロード / リロード
    // ============================

    fun loadAll() {
        val file = resolveFile() ?: return
        runCatching { parseFile(file) }
            .onFailure { e -> plugin.logger.warning("AnnouncementManager: YAML 解析エラー — ${e.message}") }
        plugin.logger.info("お知らせ: ロード完了 title='$currentTitle' / ${currentBody.size}行")
    }

    fun reload() = loadAll()

    // ============================
    // 読み取り (MenuEngine から使用)
    // ============================

    fun getAnnouncements(): List<Announcement> =
        listOf(Announcement(currentTitle, currentBody.toList()))

    // ============================
    // コマンド編集 API
    // ============================

    fun setTitle(text: String) {
        currentTitle = text
        saveToFile()
        broadcastRefresh()
    }

    /**
     * 指定インデックスの行をセット / 追加する (1-indexed で渡してくること)。
     * index が範囲外なら末尾に追加する。
     */
    fun setLine(index: Int, text: String) {
        while (currentBody.size <= index) currentBody.add("")
        currentBody[index] = text
        saveToFile()
        broadcastRefresh()
    }

    /**
     * 指定インデックスの行を削除する。
     * @return エラーメッセージ (成功なら null)
     */
    fun removeLine(index: Int): String? {
        if (index !in currentBody.indices)
            return "${index + 1} 行目は存在しません (現在 ${currentBody.size} 行)。"
        currentBody.removeAt(index)
        saveToFile()
        broadcastRefresh()
        return null
    }

    // ============================
    // 本エディタ
    // ============================

    /**
     * 本と羽ペンを渡して編集モードに入る。
     * 既存の body を1ページ最大 256 文字でページ分割して事前入力する。
     */
    fun openBookEditor(player: Player) {
        val book = ItemStack(Material.WRITABLE_BOOK)
        val meta = book.itemMeta as BookMeta

        if (currentBody.isEmpty()) {
            meta.addPage(
                "ここにお知らせの内容を書いてください。\n" +
                "改行ごとに Lore の1行になります。\n" +
                "ページをまたいでも大丈夫です。\n" +
                "署名すると → 署名名がタイトルになります。"
            )
        } else {
            splitIntoPages(currentBody.joinToString("\n"), 254)
                .forEach { meta.addPage(it) }
        }

        book.itemMeta = meta

        // 空きスロットに渡す
        val slot = player.inventory.firstEmpty().takeIf { it >= 0 } ?: 8
        player.inventory.setItem(slot, book)
        player.inventory.heldItemSlot = slot

        pendingBookEditors.add(player.uniqueId)

        player.sendMessage(c("&b=== お知らせ本エディタ ==="))
        player.sendMessage(c("&7各行 → Lore 1行 (ページをまたいでOK)"))
        player.sendMessage(c("&7署名する → 署名名が &fタイトル&7 になります"))
        player.sendMessage(c("&7現在のタイトル: &f${currentTitle.replace('&', '\u00A7')}"))
    }

    // ============================
    // PlayerEditBookEvent
    // ============================

    @EventHandler
    fun onPlayerEditBook(event: PlayerEditBookEvent) {
        val uuid = event.player.uniqueId
        if (!pendingBookEditors.remove(uuid)) return   // このマネージャの本でなければ無視

        val meta = event.newBookMeta

        // 全ページのテキストを結合して行に分割
        val newBody: List<String> = meta.pages()
            .joinToString("\n") { plainText.serialize(it) }
            .split("\n")
            .map { it.trim() }
            .filter { it.isNotEmpty() }

        currentBody = newBody.toMutableList()

        // 署名あり → タイトルも更新
        if (event.isSigning) {
            val bookTitle = meta.title()?.let { plainText.serialize(it) }?.trim()
            if (!bookTitle.isNullOrEmpty()) {
                currentTitle = bookTitle
                event.player.sendMessage(c("&aタイトル更新: &f${currentTitle.replace('&', '\u00A7')}"))
            }
        }

        saveToFile()
        broadcastRefresh()

        event.player.sendMessage(c("&aお知らせを更新しました。Lore &f${currentBody.size}&a行"))

        // 署名しなかった場合は本をインベントリから除去
        if (!event.isSigning) {
            plugin.server.scheduler.runTaskLater(plugin, Runnable {
                val inv = event.player.inventory
                for (i in 0 until inv.size) {
                    if (inv.getItem(i)?.type == Material.WRITABLE_BOOK) {
                        inv.setItem(i, null)
                        break
                    }
                }
            }, 1L)
        }
    }

    // ============================
    // YAML 保存
    // ============================

    private fun saveToFile() {
        val file = File(plugin.dataFolder, "announcements.yml")
        file.parentFile?.mkdirs()

        val yaml = YamlConfiguration()
        // コメント付きヘッダーは再書き込みしないが、キー構造は維持する
        yaml.set("announcements", listOf(
            linkedMapOf(
                "title" to currentTitle,
                "body"  to currentBody.toList()
            )
        ))
        runCatching { yaml.save(file) }
            .onFailure { e -> plugin.logger.warning("announcements.yml 保存失敗: ${e.message}") }
    }

    /** root メニューを開いているプレイヤー全員を再描画する */
    private fun broadcastRefresh() {
        plugin.server.onlinePlayers.forEach { p ->
            if (plugin.menuEngine.getPlayerState(p)?.menuId == "root") {
                plugin.menuEngine.openMenu(p, "root")
            }
        }
    }

    // ============================
    // ファイル解決 / パース
    // ============================

    private fun resolveFile(): File? {
        val dir  = plugin.dataFolder.also { if (!it.exists()) it.mkdirs() }
        val file = File(dir, "announcements.yml")
        if (file.exists()) return file
        runCatching { plugin.saveResource("announcements.yml", false) }
            .onSuccess { if (file.exists()) return file }
        return runCatching { createDefault(file); file }
            .getOrElse { e -> plugin.logger.warning("announcements.yml 生成失敗: ${e.message}"); null }
    }

    @Suppress("UNCHECKED_CAST")
    private fun parseFile(file: File) {
        val yaml    = YamlConfiguration.loadConfiguration(file)
        val rawList = yaml.getList("announcements")
        if (rawList.isNullOrEmpty()) { plugin.logger.info("announcements.yml: 0 件"); return }
        val first = rawList.firstOrNull() as? Map<*, *> ?: return
        currentTitle = first["title"]?.toString() ?: "&7お知らせ"
        currentBody  = ((first["body"] as? List<*>)?.mapNotNull { it?.toString() } ?: emptyList())
            .toMutableList()
    }

    private fun createDefault(file: File) {
        file.parentFile?.mkdirs()
        file.writeText("""# announcements.yml
# 総合メニュー (root) 上部エリア (スロット 0〜44) 全体に表示するお知らせ
# title: タイトル (&カラーコード対応)
# body:  Lore行 (リスト)
announcements:
  - title: '&e✦ ようこそおやさい鯖へ！'
    body:
      - '&7サーバー情報はWikiやリンク集からご確認ください。'
      - '&7Discordでも各種お知らせを発信しています。'
""", Charsets.UTF_8)
        currentTitle = "&e✦ ようこそおやさい鯖へ！"
        currentBody  = mutableListOf(
            "&7サーバー情報はWikiやリンク集からご確認ください。",
            "&7Discordでも各種お知らせを発信しています。"
        )
        plugin.logger.info("announcements.yml をデフォルト内容で作成しました。")
    }

    // ============================
    // ユーティリティ
    // ============================

    /** 長い文字列を maxChars 文字以内のページに分割する */
    private fun splitIntoPages(text: String, maxChars: Int): List<String> {
        if (text.length <= maxChars) return listOf(text)
        val pages = mutableListOf<String>()
        var remaining = text
        while (remaining.isNotEmpty()) {
            if (remaining.length <= maxChars) { pages.add(remaining); break }
            val cutAt = remaining.lastIndexOf('\n', maxChars - 1).takeIf { it > 0 } ?: maxChars
            pages.add(remaining.substring(0, cutAt))
            remaining = remaining.substring(cutAt).trimStart('\n')
        }
        return pages
    }

    private fun c(text: String) = text.replace('&', '\u00A7')
}