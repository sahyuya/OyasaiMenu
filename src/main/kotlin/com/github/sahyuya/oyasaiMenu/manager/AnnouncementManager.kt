package com.github.sahyuya.oyasaiMenu.manager

import com.github.sahyuya.oyasaiMenu.OyasaiMenu
import org.bukkit.configuration.file.YamlConfiguration
import java.io.File

/**
 * AnnouncementManager
 *
 * plugins/OyasaiMenu/announcements.yml からお知らせを読み込む。
 * 総合メニュー上部 (スロット 0〜44) の灰色ガラスに表示する。
 *
 * ■ ファイル展開の優先順位
 *   1. JAR リソースに announcements.yml があれば saveResource() で展開
 *   2. なければ createDefault() でハードコードされたデフォルトを生成
 *   3. ファイルが壊れていても例外をスローせず、0 件として扱う
 *
 * announcements.yml 形式:
 *   announcements:
 *     - title: '&eお知らせタイトル'
 *       body:
 *         - '&7本文1行目'
 */
class AnnouncementManager(private val plugin: OyasaiMenu) {

    data class Announcement(val title: String, val body: List<String>)

    private val announcements: MutableList<Announcement> = mutableListOf()

    fun loadAll() {
        announcements.clear()

        val file = resolveFile()
        if (file == null) {
            plugin.logger.warning("AnnouncementManager: announcements.yml を作成できませんでした。")
            return
        }

        runCatching { parseFile(file) }
            .onFailure { e ->
                plugin.logger.warning("AnnouncementManager: YAML 解析エラー — ${e.message}")
            }

        plugin.logger.info("お知らせ: ${announcements.size} 件をロード")
    }

    fun getAnnouncements(): List<Announcement> = announcements.toList()

    fun reload() = loadAll()

    // ============================
    // ファイル解決
    // ============================

    /**
     * announcements.yml を返す。
     *   - 既存ファイルがあればそのまま返す
     *   - なければ JAR リソースから展開、失敗したらデフォルトを生成
     */
    private fun resolveFile(): File? {
        val dir  = plugin.dataFolder.also { if (!it.exists()) it.mkdirs() }
        val file = File(dir, "announcements.yml")

        if (file.exists()) return file

        // JAR に含まれていれば saveResource で展開
        runCatching { plugin.saveResource("announcements.yml", false) }
            .onSuccess { if (file.exists()) return file }

        // JAR になければデフォルトを生成
        return runCatching { createDefault(file); file }
            .getOrElse { e ->
                plugin.logger.warning("announcements.yml のデフォルト生成に失敗: ${e.message}")
                null
            }
    }

    // ============================
    // YAML 解析
    // ============================

    @Suppress("UNCHECKED_CAST")
    private fun parseFile(file: File) {
        val yaml    = YamlConfiguration.loadConfiguration(file)
        val rawList = yaml.getList("announcements")

        if (rawList == null) {
            plugin.logger.warning(
                "announcements.yml: 'announcements:' キーが見つかりません。" +
                "ファイル内容を確認してください: ${file.absolutePath}"
            )
            return
        }

        if (rawList.isEmpty()) {
            plugin.logger.info("announcements.yml: お知らせが 0 件です。")
            return
        }

        rawList.forEach { element ->
            val map   = element as? Map<*, *> ?: return@forEach
            val title = map["title"]?.toString()
                ?: run { plugin.logger.warning("announcements.yml: 'title' がない行をスキップ"); return@forEach }
            val body  = (map["body"] as? List<*>)?.mapNotNull { it?.toString() } ?: emptyList()
            announcements.add(Announcement(title, body))
        }
    }

    // ============================
    // デフォルトファイル生成
    // ============================

    private fun createDefault(file: File) {
        file.parentFile?.mkdirs()
        file.writeText(
            """# announcements.yml
# 総合メニュー (root) の上部エリア (スロット 0〜44) に表示するお知らせ
# スロット 0 から順番に表示。最大 45 件。
# title: 表示名 (&カラーコード対応)
# body:  Loreテキスト (リスト形式)
announcements:
  - title: '&e✦ ようこそおやさい鯖へ！'
    body:
      - '&7サーバー情報はWikiやリンク集からご確認ください。'
  - title: '&aDiscordに参加しよう'
    body:
      - '&7各種お知らせはDiscordで発信しています。'
""",
            Charsets.UTF_8
        )
        plugin.logger.info("announcements.yml をデフォルト内容で作成しました。")
    }
}