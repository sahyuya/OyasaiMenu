package com.github.sahyuya.oyasaiMenu.loader

import com.github.sahyuya.oyasaiMenu.OyasaiMenu
import com.github.sahyuya.oyasaiMenu.model.ShopCategory
import com.github.sahyuya.oyasaiMenu.model.ShopItem
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.Registry
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.enchantments.Enchantment
import java.io.File

/**
 * ShopLoader
 *
 * menus/shop/shops.yml を読み込む。
 * InventoryShop 互換形式: "material buyPrice sellPrice"
 *
 * カスタムアイテム定義 (items/custom_items.yml) のフォーマット:
 *   キー名:
 *     id: diamond_pickaxe       ← マテリアル名 ('id' キー, 'material' キーにも対応)
 *     name: '&a表示名'
 *     lore: ['&7説明文']
 *     enchantments:
 *       DIG_SPEED: 5            ← 旧来の Bukkit レガシー名でも
 *       efficiency: 5           ← minecraft: キー名でも可
 */
class ShopLoader(private val plugin: OyasaiMenu) {

    private val categories: MutableMap<String, ShopCategory> = mutableMapOf()
    private val sellPriceMap: MutableMap<Material, Double> = mutableMapOf()

    private data class CustomItemDef(
        val material: Material,
        val name: String,
        val lore: List<String>,
        val enchantments: Map<Enchantment, Int>
    )
    private val customItems: MutableMap<String, CustomItemDef> = mutableMapOf()

    // ============================
    // 旧来の Bukkit レガシーエンチャント名 → minecraft キー名 変換表
    // ============================
    private val legacyEnchantNames: Map<String, String> = mapOf(
        "DIG_SPEED"                   to "efficiency",
        "DURABILITY"                  to "unbreaking",
        "UNBREAKING"                  to "unbreaking",
        "FORTUNE"                     to "fortune",
        "SILK_TOUCH"                  to "silk_touch",
        "MENDING"                     to "mending",
        "LURE"                        to "lure",
        "LUCK"                        to "luck_of_the_sea",
        "LUCK_OF_THE_SEA"             to "luck_of_the_sea",
        "DEPTH_STRIDER"               to "depth_strider",
        "AQUA_AFFINITY"               to "aqua_affinity",
        "RESPIRATION"                 to "respiration",
        "PROTECTION_ENVIRONMENTAL"    to "protection",
        "PROTECTION_FIRE"             to "fire_protection",
        "PROTECTION_FALL"             to "feather_falling",
        "PROTECTION_EXPLOSIONS"       to "blast_protection",
        "PROTECTION_PROJECTILE"       to "projectile_protection",
        "THORNS"                      to "thorns",
        "DAMAGE_ALL"                  to "sharpness",
        "DAMAGE_UNDEAD"               to "smite",
        "DAMAGE_ARTHROPODS"           to "bane_of_arthropods",
        "KNOCKBACK"                   to "knockback",
        "FIRE_ASPECT"                 to "fire_aspect",
        "LOOTING"                     to "looting",
        "SWEEPING_EDGE"               to "sweeping_edge",
        "ARROW_DAMAGE"                to "power",
        "ARROW_KNOCKBACK"             to "punch",
        "ARROW_FIRE"                  to "flame",
        "ARROW_INFINITE"              to "infinity",
        "SWIFT_SNEAK"                 to "swift_sneak",
        "SOUL_SPEED"                  to "soul_speed",
        "FROST_WALKER"                to "frost_walker",
    )

    // ============================
    // ロード
    // ============================

    fun loadAll() {
        categories.clear()
        sellPriceMap.clear()
        loadCustomItems()

        val file = File(plugin.dataFolder, "menus/shop/shops.yml").also {
            it.parentFile.mkdirs()
            if (!it.exists()) {
                plugin.saveResource("menus/shop/shops.yml", false)
                plugin.logger.info("menus/shop/shops.yml を初期配置しました。")
            }
        }

        val yaml = YamlConfiguration.loadConfiguration(file)
        var loaded = 0; var skipped = 0

        yaml.getKeys(false).forEach { catId ->
            val sec = yaml.getConfigurationSection(catId) ?: return@forEach
            val items = mutableListOf<ShopItem>()

            sec.getStringList("items").forEach { line ->
                val t = line.trim()
                when {
                    t.isEmpty() || t.startsWith("#") -> { skipped++; return@forEach }
                    t.startsWith("\$") -> {
                        val item = parseCustomItemLine(t, catId)
                        if (item != null) {
                            items.add(item)
                            if (item.canSell && item.material != null)
                                sellPriceMap[item.material] = item.sellPrice
                            loaded++
                        } else skipped++
                    }
                    else -> {
                        val item = parseItemLine(t, catId)
                        if (item != null) {
                            items.add(item)
                            if (item.canSell && item.material != null)
                                sellPriceMap[item.material] = item.sellPrice
                            loaded++
                        } else skipped++
                    }
                }
            }

            categories[catId] = ShopCategory(
                id = catId,
                displayName = sec.getString("name", "&7$catId") ?: "&7$catId",
                command = sec.getString("command"),
                items = items
            )
        }
        plugin.logger.info("ショップ: ${categories.size} カテゴリ / $loaded アイテム ($skipped スキップ)")
    }

    fun getCategory(id: String): ShopCategory? = categories[id]
    fun getAllCategories(): Map<String, ShopCategory> = categories.toMap()
    fun getSellPrice(material: Material): Double? = sellPriceMap[material]
    fun reload() = loadAll()

    // ============================
    // カスタムアイテム定義の読み込み
    // ============================

    private fun loadCustomItems() {
        customItems.clear()
        val file = File(plugin.dataFolder, "items/custom_items.yml").also {
            it.parentFile.mkdirs()
            if (!it.exists()) {
                plugin.saveResource("items/custom_items.yml", false)
                plugin.logger.info("items/custom_items.yml を初期配置しました。")
            }
        }

        val yaml = YamlConfiguration.loadConfiguration(file)
        yaml.getKeys(false).forEach { key ->
            val sec = yaml.getConfigurationSection(key) ?: return@forEach

            // 'id' キーと 'material' キーの両方を受け付ける (items.yml 互換)
            val matName = (sec.getString("id") ?: sec.getString("material") ?: "STONE").uppercase()
            val mat = runCatching { Material.valueOf(matName) }.getOrElse {
                plugin.logger.warning("custom_items.yml: 不明なマテリアル '$matName' (key: $key)")
                Material.STONE
            }

            // エンチャントをパース
            val enchantMap = mutableMapOf<Enchantment, Int>()
            sec.getConfigurationSection("enchantments")?.getKeys(false)?.forEach { enchKey ->
                val level = sec.getInt("enchantments.$enchKey", 1)
                val enchant = resolveEnchantment(enchKey)
                if (enchant != null) {
                    enchantMap[enchant] = level
                } else {
                    plugin.logger.warning("custom_items.yml: 不明なエンチャント '$enchKey' (key: $key)")
                }
            }

            customItems[key] = CustomItemDef(
                material     = mat,
                name         = sec.getString("name", "") ?: "",
                lore         = sec.getStringList("lore"),
                enchantments = enchantMap
            )
        }
        plugin.logger.info("カスタムアイテム: ${customItems.size} 件をロード")
    }

    /**
     * エンチャント名を Bukkit の Enchantment オブジェクトに解決する。
     *
     * 以下の順で試みる:
     *   1. legacyEnchantNames マップ経由で minecraft: キー名に変換してから Registry 検索
     *   2. そのまま lowercase にして Registry 検索 (例: "efficiency" → minecraft:efficiency)
     */
    private fun resolveEnchantment(name: String): Enchantment? {
        // レガシー名 → minecraft キー名 に変換
        val key = legacyEnchantNames[name.uppercase()] ?: name.lowercase()
        return runCatching {
            Registry.ENCHANTMENT.get(NamespacedKey.minecraft(key))
        }.getOrElse {
            // Registry API が使えない古いバージョンのフォールバック
            @Suppress("DEPRECATION")
            Enchantment.getByName(name.uppercase())
        }
    }

    // ============================
    // パース
    // ============================

    /** "$キー buyPrice sellPrice" 形式のパース */
    private fun parseCustomItemLine(line: String, catId: String): ShopItem? {
        val parts = line.split("\\s+".toRegex())
        if (parts.size < 3) {
            plugin.logger.warning("カスタムアイテム フォーマットエラー ($catId): '$line'"); return null
        }
        val key       = parts[0].removePrefix("\$")
        val buyPrice  = parts[1].toDoubleOrNull() ?: return null
        val sellPrice = parts[2].toDoubleOrNull() ?: return null
        val def = customItems[key] ?: run {
            plugin.logger.warning("カスタムアイテム未定義: $key ($catId) — items/custom_items.yml に追加してください")
            return null
        }
        return ShopItem(
            material     = def.material,
            materialId   = key,
            buyPrice     = buyPrice,
            sellPrice    = sellPrice,
            customName   = def.name.takeIf { it.isNotEmpty() },
            customLore   = def.lore,
            enchantments = def.enchantments
        )
    }

    /** "material buyPrice sellPrice" 通常行のパース */
    private fun parseItemLine(line: String, catId: String): ShopItem? {
        val parts = line.split("\\s+".toRegex())
        if (parts.size < 3) {
            plugin.logger.warning("フォーマットエラー ($catId): '$line'"); return null
        }
        val buyPrice  = parts[1].toDoubleOrNull() ?: return null
        val sellPrice = parts[2].toDoubleOrNull() ?: return null
        val material  = runCatching { Material.valueOf(parts[0].uppercase()) }.getOrElse {
            plugin.logger.warning("不明なマテリアル: ${parts[0]} ($catId)"); null
        }
        return ShopItem(
            material   = material,
            materialId = parts[0].lowercase(),
            buyPrice   = buyPrice,
            sellPrice  = sellPrice
        )
    }
}