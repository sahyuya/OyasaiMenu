package com.github.sahyuya.oyasaiMenu.manager

import com.github.sahyuya.oyasaiMenu.OyasaiMenu
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.Damageable
import org.bukkit.inventory.meta.EnchantmentStorageMeta
import java.io.File
import java.util.Base64

/**
 * SellWhitelistManager
 *
 * カスタム名付きアイテムの売却を許可するホワイトリストを管理する。
 *
 * ■ 売却ルール
 *   - バニラ名 (hasDisplayName() == false) かつマテリアルがショップ登録済み
 *     → ショップ売値で売れる (ゆるいチェック、SellEngine側で判定)
 *   - カスタム名ありアイテム
 *     → ホワイトリストにデータコンポーネントが一致するエントリのみ売れる
 *
 * ■ データコンポーネント比較
 *   - type / custom_name / enchantments / stored_enchantments を比較
 *   - 耐久値 (damage) は無視 (使用済みアイテムでも売れる)
 *   - lore は比較しない
 */
class SellWhitelistManager(private val plugin: OyasaiMenu) {

    data class WhitelistEntry(
        val id: String,
        val displayName: String,
        val materialName: String,
        val sellPrice: Double,
        val itemDataB64: String
    )

    private val entries: MutableList<WhitelistEntry> = mutableListOf()
    private val plain = PlainTextComponentSerializer.plainText()

    private val file: File get() = File(plugin.dataFolder, "sell-whitelist.yml")

    // ============================
    // ロード
    // ============================

    fun loadAll() {
        entries.clear()

        if (!file.exists()) {
            file.parentFile.mkdirs()
            file.writeText("""# sell-whitelist.yml
# カスタム名付きアイテムの売却を許可するホワイトリスト。
# /menuedit whitelist add hand <売値> でインゲームから追加できます。
# バニラ名のアイテムはショップ登録だけで売れるため、ここへの登録は不要です。
entries: []
""", Charsets.UTF_8)
        }

        val yaml = YamlConfiguration.loadConfiguration(file)

        @Suppress("UNCHECKED_CAST")
        val rawList = yaml.getList("entries") ?: emptyList<Any>()
        rawList.filterIsInstance<Map<*, *>>().forEach { map ->
            val id          = map["id"]?.toString()           ?: return@forEach
            val displayName = map["display_name"]?.toString() ?: ""
            val material    = map["material"]?.toString()     ?: ""
            val sellPrice   = (map["sell_price"] as? Number)?.toDouble() ?: return@forEach
            val itemData    = map["item_data"]?.toString()    ?: return@forEach
            entries.add(WhitelistEntry(id, displayName, material, sellPrice, itemData))
        }
        plugin.logger.info("売却ホワイトリスト: ${entries.size} 件")
    }

    fun reload() = loadAll()

    // ============================
    // 価格照会
    // ============================

    /**
     * アイテムがホワイトリストに一致する場合の売却価格を返す。
     * 一致しない場合は null。耐久値は無視して比較する。
     */
    fun getPrice(item: ItemStack): Double? {
        val normalized = normalizeForComparison(item)
        return entries.firstOrNull { entry ->
            runCatching {
                val templateRaw = Base64.getDecoder().decode(entry.itemDataB64)
                val template = normalizeForComparison(ItemStack.deserializeBytes(templateRaw))
                matchesComponents(template, normalized)
            }.getOrElse { false }
        }?.sellPrice
    }

    // ============================
    // 編集 API
    // ============================

    /**
     * 手持ちアイテムをホワイトリストに追加する。
     * @return エラーメッセージ (成功なら null)
     */
    fun addEntry(item: ItemStack, sellPrice: Double): String? {
        if (item.type.isAir) return "AIR はホワイトリストに追加できません。"
        if (sellPrice <= 0)  return "売値は 1 以上で指定してください。"

        val normalized = normalizeForComparison(item)

        // 重複チェック
        val alreadyExists = entries.any { entry ->
            runCatching {
                val bytes = Base64.getDecoder().decode(entry.itemDataB64)
                matchesComponents(normalizeForComparison(ItemStack.deserializeBytes(bytes)), normalized)
            }.getOrElse { false }
        }
        if (alreadyExists) return "同じデータコンポーネントのアイテムは既に登録済みです。"

        val meta = item.itemMeta
        val displayName = if (meta != null && meta.hasDisplayName())
            plain.serialize(meta.displayName()!!)
        else item.type.name.lowercase()

        val itemDataB64 = runCatching {
            Base64.getEncoder().encodeToString(normalized.serializeAsBytes())
        }.getOrElse { return "アイテムのシリアライズに失敗しました: ${it.message}" }

        val baseId = "${item.type.name.lowercase()}_wl"
        val newId  = generateUniqueId(baseId, entries.map { it.id }.toSet())

        entries.add(WhitelistEntry(
            id           = newId,
            displayName  = displayName,
            materialName = item.type.name,
            sellPrice    = sellPrice,
            itemDataB64  = itemDataB64
        ))
        saveToFile()
        return null
    }

    /**
     * 指定インデックス (1-indexed) のエントリを削除する。
     * @return 削除したエントリの表示名 (失敗なら null)
     */
    fun removeEntry(index: Int): String? {
        val zeroIdx = index - 1
        if (zeroIdx !in entries.indices) return null
        val removed = entries.removeAt(zeroIdx)
        saveToFile()
        return removed.displayName
    }

    fun getEntries(): List<WhitelistEntry> = entries.toList()

    // ============================
    // 保存
    // ============================

    private fun saveToFile() {
        val yaml = YamlConfiguration()
        yaml.set("entries", entries.map { e ->
            linkedMapOf(
                "id"           to e.id,
                "display_name" to e.displayName,
                "material"     to e.materialName,
                "sell_price"   to e.sellPrice,
                "item_data"    to e.itemDataB64
            )
        })
        runCatching { yaml.save(file) }
            .onFailure { plugin.logger.warning("sell-whitelist.yml 保存失敗: ${it.message}") }
    }

    // ============================
    // データコンポーネント比較
    // ============================

    /** 耐久値リセット・個数1・loreクリアした比較用コピーを返す */
    private fun normalizeForComparison(item: ItemStack): ItemStack {
        val copy = item.clone()
        copy.amount = 1
        val meta = copy.itemMeta ?: return copy
        if (meta is Damageable) meta.damage = 0
        meta.lore(emptyList())
        copy.itemMeta = meta
        return copy
    }

    /**
     * type / custom_name / enchantments / stored_enchantments が一致するか比較する。
     * normalizeForComparison() を通したアイテムを渡すこと。
     */
    private fun matchesComponents(a: ItemStack, b: ItemStack): Boolean {
        if (a.type != b.type) return false
        val ameta = a.itemMeta
        val bmeta = b.itemMeta
        if (ameta == null && bmeta == null) return true
        if (ameta == null || bmeta == null) return false

        // カスタム名
        val aHasName = ameta.hasDisplayName()
        val bHasName = bmeta.hasDisplayName()
        if (aHasName != bHasName) return false
        if (aHasName) {
            if (plain.serialize(ameta.displayName()!!) != plain.serialize(bmeta.displayName()!!)) return false
        }

        // エンチャント
        if (ameta.enchants != bmeta.enchants) return false

        // stored enchantments (エンチャント本)
        val aStored = ameta as? EnchantmentStorageMeta
        val bStored = bmeta as? EnchantmentStorageMeta
        if ((aStored == null) != (bStored == null)) return false
        if (aStored != null && bStored != null) {
            if (aStored.storedEnchants != bStored.storedEnchants) return false
        }

        return true
    }

    private fun generateUniqueId(baseId: String, existing: Set<String>): String {
        if (!existing.contains(baseId)) return baseId
        var counter = 2
        while (existing.contains("${baseId}_$counter")) counter++
        return "${baseId}_$counter"
    }
}