package com.github.sahyuya.oyasaiMenu.manager

import com.github.sahyuya.oyasaiMenu.OyasaiMenu
import org.bukkit.Material
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.inventory.ItemStack
import java.io.File

/**
 * SellBlacklistManager
 *
 * 売却を禁止するアイテムのブラックリストを管理する。
 *
 * sell-blacklist.yml:
 *   materials:
 *     - DIAMOND_PICKAXE
 *     - NETHERITE_SWORD
 *
 * config.yml:
 *   sell-blacklist:
 *     block-renamed: true   # カスタム名のあるアイテムを売却不可にする
 */
class SellBlacklistManager(private val plugin: OyasaiMenu) {

    private val blacklistedMaterials: MutableSet<Material> = mutableSetOf()
    private var blockRenamed: Boolean = true

    private val file: File get() = File(plugin.dataFolder, "sell-blacklist.yml")

    // ============================
    // 読み込み
    // ============================

    fun loadAll() {
        blacklistedMaterials.clear()
        blockRenamed = plugin.config.getBoolean("sell-blacklist.block-renamed", true)

        if (!file.exists()) {
            file.parentFile.mkdirs()
            file.writeText("""# sell-blacklist.yml
# 売却を禁止するマテリアルのリスト
# カスタム名のついたアイテムを売却不可にするには config.yml の sell-blacklist.block-renamed: true を設定
materials: []
""", Charsets.UTF_8)
        }

        val yaml = YamlConfiguration.loadConfiguration(file)
        yaml.getStringList("materials").forEach { name ->
            runCatching { Material.valueOf(name.uppercase()) }
                .onSuccess { blacklistedMaterials.add(it) }
                .onFailure { plugin.logger.warning("sell-blacklist.yml: 不明なマテリアル '$name'") }
        }
        plugin.logger.info("販売ブラックリスト: ${blacklistedMaterials.size} 種, ブラックリスト対象リネーム: $blockRenamed")
    }

    fun reload() = loadAll()

    // ============================
    // チェック
    // ============================

    /**
     * このアイテムが売却禁止かどうかを返す。
     * true = 売却禁止
     */
    fun isBlacklisted(item: ItemStack): Boolean {
        if (item.type.isAir) return false
        if (blacklistedMaterials.contains(item.type)) return true
        if (blockRenamed) {
            val meta = item.itemMeta
            if (meta != null && meta.hasDisplayName()) return true
        }
        return false
    }

    // ============================
    // 編集 API (/menuedit blacklist)
    // ============================

    fun getMaterials(): Set<Material> = blacklistedMaterials.toSet()

    fun addMaterial(material: Material): Boolean {
        val added = blacklistedMaterials.add(material)
        if (added) saveToFile()
        return added
    }

    fun removeMaterial(material: Material): Boolean {
        val removed = blacklistedMaterials.remove(material)
        if (removed) saveToFile()
        return removed
    }

    // ============================
    // 保存
    // ============================

    private fun saveToFile() {
        val yaml = YamlConfiguration()
        yaml.set("materials", blacklistedMaterials.map { it.name }.sorted())
        runCatching { yaml.save(file) }
            .onFailure { e -> plugin.logger.warning("sell-blacklist.yml 保存失敗: ${e.message}") }
    }
}