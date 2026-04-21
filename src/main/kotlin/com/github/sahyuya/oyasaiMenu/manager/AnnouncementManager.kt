package com.github.sahyuya.oyasaiMenu.manager

import com.github.sahyuya.oyasaiMenu.OyasaiMenu
import org.bukkit.configuration.file.YamlConfiguration
import java.io.File

/**
 * AnnouncementManager
 *
 * plugins/OyasaiMenu/announcements.yml からお知らせを読み込み、
 * 総合メニューの灰色ガラスパネル (スロット 0〜44) に表示する。
 *
 * announcements.yml 形式:
 *   announcements:
 *     - title: '&eお知らせ1'
 *       body:
 *         - '&7内容1行目'
 *         - '&7内容2行目'
 *     - title: '&aお知らせ2'
 *       body:
 *         - '&7内容'
 *
 * スロット 0 から順番に並べる。45個まで表示可能。
 * お知らせが未設定のスロットは無記名の灰色ガラスになる。
 */
class AnnouncementManager(private val plugin: OyasaiMenu) {

    data class Announcement(val title: String, val body: List<String>)

    private val announcements: MutableList<Announcement> = mutableListOf()

    fun loadAll() {
        announcements.clear()
        val file = File(plugin.dataFolder, "announcements.yml").also {
            if (!it.exists()) {
                plugin.saveResource("announcements.yml", false)
                plugin.logger.info("announcements.yml を初期配置しました。")
            }
        }
        val yaml = YamlConfiguration.loadConfiguration(file)
        val list = yaml.getList("announcements") ?: return

        @Suppress("UNCHECKED_CAST")
        list.filterIsInstance<Map<String, Any>>().forEach { map ->
            val title = map["title"]?.toString() ?: return@forEach
            val body  = (map["body"] as? List<*>)?.map { it.toString() } ?: emptyList()
            announcements.add(Announcement(title, body))
        }
        plugin.logger.info("お知らせ: ${announcements.size} 件をロード")
    }

    fun getAnnouncements(): List<Announcement> = announcements.toList()
    fun reload() = loadAll()
}