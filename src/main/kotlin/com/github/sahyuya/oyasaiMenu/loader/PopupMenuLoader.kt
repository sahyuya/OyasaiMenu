package com.github.sahyuya.oyasaiMenu.loader

import com.github.sahyuya.oyasaiMenu.OyasaiMenu
import com.github.sahyuya.oyasaiMenu.model.*
import org.bukkit.Material
import org.bukkit.configuration.file.YamlConfiguration
import java.io.File

/**
 * PopupMenuLoader
 *
 * plugins/OyasaiMenu/menus/popup/ ディレクトリの全 .yml を読み込み、
 * PopupMenuDef のマップを構築する。
 *
 * ファイル名 (拡張子なし) がそのまま popup ID になる。
 * 例: menus/popup/channel.yml → ID "channel"
 */
class PopupMenuLoader(private val plugin: OyasaiMenu) {

    private val popups: MutableMap<String, PopupMenuDef> = mutableMapOf()

    fun loadAll() {
        popups.clear()
        val dir = File(plugin.dataFolder, "menus/popup").also { it.mkdirs() }

        // デフォルトリソースを展開 (JAR に存在する場合のみ saveResource を試みる)
        listOf(
            "channel", "sellmenu", "shopindex",
            "sociallikes", "carbuilder", "utility",
            "macromenu", "links", "vtpbiome"
        ).forEach { name ->
            val f = File(dir, "$name.yml")
            if (!f.exists()) {
                runCatching {
                    plugin.saveResource("menus/popup/$name.yml", false)
                }.onFailure {
                    plugin.logger.warning("menus/popup/$name.yml のデフォルト展開に失敗しました。手動で配置してください。")
                }
            }
        }

        dir.listFiles()?.filter { it.extension == "yml" }?.forEach { file ->
            val id = file.nameWithoutExtension
            runCatching {
                popups[id] = loadFile(file, id)
                plugin.logger.fine("Popup ロード: $id")
            }.onFailure {
                plugin.logger.warning("Popup ロード失敗: $id → ${it.message}")
            }
        }
        plugin.logger.info("Popup メニュー: ${popups.size} 件ロード")
    }

    fun getPopup(id: String): PopupMenuDef? = popups[id]
    fun reload() = loadAll()

    // ============================
    // パース
    // ============================

    private fun loadFile(file: File, id: String): PopupMenuDef {
        val yaml = YamlConfiguration.loadConfiguration(file)

        val title     = yaml.getString("title", "&7$id") ?: "&7$id"
        val glassName = yaml.getString("glass", "GRAY_STAINED_GLASS_PANE") ?: "GRAY_STAINED_GLASS_PANE"
        val glass     = runCatching { Material.valueOf(glassName.uppercase()) }.getOrDefault(Material.GRAY_STAINED_GLASS_PANE)
        val navActive = yaml.getInt("nav_active", -1)

        val items = mutableListOf<PopupItem>()
        val itemsSec = yaml.getConfigurationSection("items")
        itemsSec?.getKeys(false)?.forEach { key ->
            val sec = itemsSec.getConfigurationSection(key) ?: return@forEach
            val slot = sec.getInt("slot", 0)
            if (slot in 45..53) {
                plugin.logger.warning("Popup $id: アイテム '$key' のスロット $slot はナビバー専用です。スキップします。")
                return@forEach
            }

            val iconName = sec.getString("icon", "AIR")?.uppercase() ?: "AIR"
            val icon = runCatching { Material.valueOf(iconName) }.getOrDefault(Material.AIR)
            val texture = sec.getString("texture")

            val enchanted = sec.getBoolean("enchanted", false)
            val name      = sec.getString("name", "") ?: ""
            val lore      = sec.getStringList("lore")

            // アクションパース
            val actions = mutableListOf<PopupAction>()
            @Suppress("UNCHECKED_CAST")
            val rawActions = sec.getList("actions") ?: emptyList<Any>()
            rawActions.filterIsInstance<Map<String, Any>>().forEach { actionMap ->
                parseAction(actionMap)?.let { actions.add(it) }
            }

            items.add(PopupItem(
                key           = key,
                slot          = slot,
                icon          = icon,
                customTexture = texture,
                name          = name,
                lore          = lore,
                enchanted     = enchanted,
                actions       = actions
            ))
        }

        return PopupMenuDef(id = id, title = title, glass = glass, navActive = navActive, items = items)
    }

    /**
     * アクションマップをパースする。
     *
     * YAML 例:
     *   - player_cmd: "spawn"
     *   - console_cmd: "give %player% item_frame 1"
     *   - url: "https://..."
     *   - chat_paste: "/annnai "
     *   - open_popup: "channel"
     *   - open_shop: "blocks"
     *   - open_sell: true
     *   - open_macro: true
     *   - open_menu: "root"
     *   - close: true
     */
    private fun parseAction(map: Map<String, Any>): PopupAction? {
        return when {
            map.containsKey("player_cmd")  -> PopupAction(PopupActionType.PLAYER_CMD, map["player_cmd"].toString())
            map.containsKey("console_cmd") -> PopupAction(PopupActionType.CONSOLE_CMD, map["console_cmd"].toString())
            map.containsKey("url")         -> PopupAction(PopupActionType.URL, map["url"].toString())
            map.containsKey("chat_paste")  -> PopupAction(PopupActionType.CHAT_PASTE, map["chat_paste"].toString())
            map.containsKey("open_popup")  -> PopupAction(PopupActionType.OPEN_POPUP, map["open_popup"].toString())
            map.containsKey("open_shop")   -> PopupAction(PopupActionType.OPEN_SHOP, map["open_shop"].toString())
            map.containsKey("open_sell")   -> PopupAction(PopupActionType.OPEN_SELL, "")
            map.containsKey("open_macro")        -> PopupAction(PopupActionType.OPEN_MACRO, "")
            map.containsKey("open_point_shop")   -> PopupAction(PopupActionType.OPEN_POINT_SHOP, map["open_point_shop"].toString())
            map.containsKey("open_menu")   -> PopupAction(PopupActionType.OPEN_MENU, map["open_menu"].toString())
            map.containsKey("close")       -> PopupAction(PopupActionType.CLOSE, "")
            else -> null
        }
    }
}