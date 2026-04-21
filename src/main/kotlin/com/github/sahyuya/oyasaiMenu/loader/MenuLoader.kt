package com.github.sahyuya.oyasaiMenu.loader

import com.github.sahyuya.oyasaiMenu.OyasaiMenu
import com.github.sahyuya.oyasaiMenu.model.*
import org.bukkit.Material
import org.bukkit.configuration.ConfigurationSection
import org.bukkit.configuration.file.YamlConfiguration
import java.io.File

/**
 * MenuLoader (旧: MenuConfigLoader)
 *
 * plugins/OyasaiMenu/menus/ を再帰スキャンし、
 * 全 .yml を MenuDefinition としてロードする。
 * ファイルパス (menus/ からの相対パス、拡張子除く) がメニューID。
 *   例: menus/shop/index.yml → ID "shop/index"
 */
class MenuLoader(private val plugin: OyasaiMenu) {

    private val menus: MutableMap<String, MenuDefinition> = mutableMapOf()
    private val templates: MutableMap<String, MenuItemDefinition> = mutableMapOf()

    fun loadAll() {
        menus.clear()
        templates.clear()

        val templateFile = File(plugin.dataFolder, "items/templates.yml")
        if (templateFile.exists()) loadTemplates(templateFile)

        val menusDir = File(plugin.dataFolder, "menus")
        if (!menusDir.exists()) {
            menusDir.mkdirs()
            plugin.saveResource("menus/root.yml", false)
            plugin.saveResource("menus/shop/index.yml", false)
            plugin.logger.info("menus/ を作成しデフォルトファイルを配置しました。")
        }
        // shops.yml / pointshop.yml はメニューではないのでスキップ
        scanDirectory(menusDir, "", setOf("shops.yml", "pointshop.yml"))
        plugin.logger.info("${menus.size} 個のメニューをロードしました。")
    }

    fun getMenu(id: String): MenuDefinition? = menus[id]
    fun getMenuCount(): Int = menus.size
    fun getAllMenuIds(): List<String> = menus.keys.toList()

    private fun scanDirectory(dir: File, prefix: String, skipFiles: Set<String> = emptySet()) {
        dir.listFiles()?.sortedBy { it.name }?.forEach { file ->
            if (file.isDirectory) {
                // popup/ ディレクトリは PopupMenuLoader が管理するのでスキップ
                if (file.name == "popup") return@forEach
                scanDirectory(file, "$prefix${file.name}/", skipFiles)
            } else if (file.extension == "yml" && file.name !in skipFiles) {
                val menuId = "$prefix${file.nameWithoutExtension}"
                runCatching {
                    menus[menuId] = loadMenuFile(file, menuId)
                }.onFailure {
                    plugin.logger.warning("メニューロード失敗: $menuId → ${it.message}")
                }
            }
        }
    }

    private fun loadMenuFile(file: File, menuId: String): MenuDefinition {
        val yaml = YamlConfiguration.loadConfiguration(file)
        val menuSec = yaml.getConfigurationSection("menu")
            ?: throw IllegalArgumentException("'menu:' セクションがありません: ${file.name}")

        val title = menuSec.getString("title", "&8メニュー")!!
        val rawSize = menuSec.getInt("size", 54).coerceIn(9, 54)
        val size = ((rawSize + 8) / 9) * 9

        val items = buildMap<String, MenuItemDefinition> {
            yaml.getConfigurationSection("items")?.getKeys(false)?.forEach { key ->
                yaml.getConfigurationSection("items.$key")?.let { sec ->
                    put(key, parseItemDefinition(sec, key))
                }
            }
        }
        return MenuDefinition(id = menuId, title = title, size = size, items = items)
    }

    private fun parseItemDefinition(section: ConfigurationSection, key: String): MenuItemDefinition {
        val base: MenuItemDefinition = section.getString("extends")?.let { tid ->
            templates[tid] ?: MenuItemDefinition(slot = 0).also {
                plugin.logger.warning("テンプレート未定義: $tid (アイテム: $key)")
            }
        } ?: MenuItemDefinition(slot = 0)

        val slot = section.getInt("slot", base.slot)
        val icon = section.getString("icon", base.icon.name)?.uppercase()
            ?.let { runCatching { Material.valueOf(it) }.getOrElse { Material.STONE } }
            ?: base.icon
        val name = section.getString("name", base.name) ?: base.name
        val lore = section.getStringList("lore").ifEmpty { base.lore }
        val permission = section.getString("permission", base.permission)
        val actions = if (section.contains("actions")) parseActions(section, "actions") else base.actions

        return MenuItemDefinition(
            slot = slot, icon = icon, name = name, lore = lore,
            actions = actions, permission = permission,
            template = section.getString("extends")
        )
    }

    @Suppress("UNCHECKED_CAST")
    fun parseActions(section: ConfigurationSection, key: String): List<MenuAction> =
        (section.getList(key) ?: return emptyList())
            .filterIsInstance<Map<String, Any>>()
            .map { parseActionMap(it) }

    @Suppress("UNCHECKED_CAST")
    private fun parseActionMap(map: Map<String, Any>): MenuAction {
        val typeName = map["type"]?.toString()?.uppercase()?.replace("-", "_") ?: "UNKNOWN"
        val type = runCatching { ActionType.valueOf(typeName) }.getOrElse {
            plugin.logger.warning("不明なアクションタイプ: ${map["type"]}")
            ActionType.UNKNOWN
        }
        val params  = map.filterKeys { it !in setOf("type", "success", "fail") }
        val success = (map["success"] as? List<Map<String, Any>>)?.map { parseActionMap(it) } ?: emptyList()
        val fail    = (map["fail"]    as? List<Map<String, Any>>)?.map { parseActionMap(it) } ?: emptyList()
        return MenuAction(type = type, params = params, success = success, fail = fail)
    }

    private fun loadTemplates(file: File) {
        val yaml = YamlConfiguration.loadConfiguration(file)
        yaml.getConfigurationSection("templates")?.getKeys(false)?.forEach { key ->
            yaml.getConfigurationSection("templates.$key")?.let { sec ->
                templates[key] = parseItemDefinition(sec, key)
            }
        }
        plugin.logger.info("${templates.size} 個のテンプレートをロードしました。")
    }
}