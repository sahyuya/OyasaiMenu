package com.github.sahyuya.oyasaiMenu.config

import com.github.sahyuya.oyasaiMenu.OyasaiMenu
import com.github.sahyuya.oyasaiMenu.model.*
import org.bukkit.Material
import org.bukkit.configuration.ConfigurationSection
import org.bukkit.configuration.file.YamlConfiguration
import java.io.File

/**
 * MenuConfigLoader
 *
 * plugins/OyasaiMenu/menus/ ディレクトリを再帰的に走査し、
 * 全 .yml ファイルを MenuDefinition としてロードする。
 *
 * ファイルパス (menus/ からの相対パス、拡張子除く) が
 * そのままメニューIDになる。
 *   例: menus/shop/blocks.yml → ID "shop/blocks"
 *
 * テンプレート (items/templates.yml) は全メニューより先に
 * 読み込むことで、他ファイルからの "extends:" を正しく解決できる。
 */
class MenuConfigLoader(private val plugin: OyasaiMenu) {

    private val menus: MutableMap<String, MenuDefinition> = mutableMapOf()
    private val templates: MutableMap<String, MenuItemDefinition> = mutableMapOf()

    // ============================
    // 公開API
    // ============================

    fun loadAll() {
        menus.clear()
        templates.clear()

        // ① テンプレートを先にロード
        val templateFile = File(plugin.dataFolder, "items/templates.yml")
        if (templateFile.exists()) loadTemplates(templateFile)

        // ② menus/ を再帰スキャン
        val menusDir = File(plugin.dataFolder, "menus")
        if (!menusDir.exists()) {
            menusDir.mkdirs()
            plugin.saveResource("menus/root.yml", false)
            plugin.saveResource("menus/shop/index.yml", false)
            plugin.logger.info("menus/ を作成しデフォルトファイルを配置しました。")
        }
        scanDirectory(menusDir, "")
        plugin.logger.info("${menus.size} 個のメニューをロードしました。")
    }

    fun getMenu(id: String): MenuDefinition? = menus[id]
    fun getMenuCount(): Int = menus.size
    fun getAllMenuIds(): List<String> = menus.keys.toList()

    // ============================
    // 内部実装
    // ============================

    private fun scanDirectory(dir: File, prefix: String) {
        dir.listFiles()?.sortedBy { it.name }?.forEach { file ->
            if (file.isDirectory) {
                scanDirectory(file, "$prefix${file.name}/")
            } else if (file.extension == "yml") {
                val menuId = "$prefix${file.nameWithoutExtension}"
                runCatching {
                    menus[menuId] = loadMenuFile(file, menuId)
                    plugin.logger.fine("ロード完了: $menuId")
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
        // サイズを 9 の倍数に丸める (9〜54)
        val rawSize = menuSec.getInt("size", 54).coerceIn(9, 54)
        val size = ((rawSize + 8) / 9) * 9

        val itemsSec = yaml.getConfigurationSection("items")
        val items = buildMap<String, MenuItemDefinition> {
            itemsSec?.getKeys(false)?.forEach { key ->
                itemsSec.getConfigurationSection(key)?.let { sec ->
                    put(key, parseItemDefinition(sec, key))
                }
            }
        }
        return MenuDefinition(id = menuId, title = title, size = size, items = items)
    }

    private fun parseItemDefinition(section: ConfigurationSection, key: String): MenuItemDefinition {
        // テンプレート継承: "extends: back_button" があれば base としてコピー
        val base: MenuItemDefinition = section.getString("extends")?.let { tid ->
            templates[tid] ?: MenuItemDefinition(slot = 0).also {
                plugin.logger.warning("テンプレート未定義: $tid (アイテム: $key)")
            }
        } ?: MenuItemDefinition(slot = 0)

        val slot = section.getInt("slot", base.slot)
        val iconName = section.getString("icon", base.icon.name) ?: base.icon.name
        val icon = runCatching { Material.valueOf(iconName.uppercase()) }.getOrElse {
            plugin.logger.warning("不明なマテリアル: $iconName → STONE で代替 (アイテム: $key)")
            Material.STONE
        }
        val name = section.getString("name", base.name) ?: base.name
        val lore = section.getStringList("lore").ifEmpty { base.lore }
        val permission = section.getString("permission", base.permission)
        val actions = if (section.contains("actions")) parseActions(section, "actions")
        else base.actions

        return MenuItemDefinition(
            slot = slot, icon = icon, name = name, lore = lore,
            actions = actions, permission = permission,
            template = section.getString("extends")
        )
    }

    @Suppress("UNCHECKED_CAST")
    fun parseActions(section: ConfigurationSection, key: String): List<MenuAction> {
        return (section.getList(key) ?: return emptyList())
            .filterIsInstance<Map<String, Any>>()
            .map { parseActionMap(it) }
    }

    @Suppress("UNCHECKED_CAST")
    private fun parseActionMap(map: Map<String, Any>): MenuAction {
        val typeName = map["type"]?.toString()?.uppercase()?.replace("-", "_") ?: "UNKNOWN"
        val type = runCatching { ActionType.valueOf(typeName) }
            .getOrElse {
                plugin.logger.warning("不明なアクションタイプ: ${map["type"]}")
                ActionType.UNKNOWN
            }
        val params = map.filterKeys { it != "type" && it != "success" && it != "fail" }
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