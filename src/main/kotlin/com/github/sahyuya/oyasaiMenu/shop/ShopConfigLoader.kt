package com.github.sahyuya.oyasaiMenu.shop

import com.github.sahyuya.oyasaiMenu.OyasaiMenu
import com.github.sahyuya.oyasaiMenu.model.ShopCategory
import com.github.sahyuya.oyasaiMenu.model.ShopItem
import org.bukkit.Material
import org.bukkit.configuration.file.YamlConfiguration
import java.io.File

/**
 * ShopConfigLoader
 *
 * plugins/OyasaiMenu/shops.yml を読み込み、ShopCategory のマップを構築する。
 * InventoryShop プラグインの shops.yml と完全互換の形式を維持する:
 *
 *   blocks:
 *     name: '&1ブロック'
 *     rows: 6            # 無視 (自動ページング)
 *     command: 'blocks'  # オプション
 *     items:
 *       - oak_log 5 1    # material buyPrice sellPrice
 *       - ''             # 旧プラグインのページ区切り → スキップ
 *
 * マテリアル名の解決に失敗した行は警告を出してスキップする。
 * $プレフィックス付きの特殊アイテム参照 ($pickaxe_normal など) も
 * 現時点では警告のみでスキップ (将来的に別ファイルで定義可能)。
 */
class ShopConfigLoader(private val plugin: OyasaiMenu) {

    /** カテゴリID → ShopCategory のマップ */
    private val categories: MutableMap<String, ShopCategory> = mutableMapOf()

    /** マテリアルID → ShopItem の逆引きマップ (一括売却で使用) */
    private val sellPriceMap: MutableMap<Material, Double> = mutableMapOf()

    // ============================
    // 公開API
    // ============================

    fun loadAll() {
        categories.clear()
        sellPriceMap.clear()

        // shops.yml を menus/shop/ ディレクトリから読み込む
        // (menus/shop/index.yml と同じ場所にまとめることで管理しやすくする)
        val shopDir = File(plugin.dataFolder, "menus/shop")
        shopDir.mkdirs()
        val file = File(shopDir, "shops.yml")
        if (!file.exists()) {
            // 初回: リソース内の shops.yml を menus/shop/shops.yml に展開
            plugin.saveResource("menus/shop/shops.yml", false)
            plugin.logger.info("menus/shop/shops.yml を初期配置しました。")
        }

        val yaml = YamlConfiguration.loadConfiguration(file)
        var loaded = 0
        var skipped = 0

        yaml.getKeys(false).forEach { categoryId ->
            val section = yaml.getConfigurationSection(categoryId) ?: return@forEach
            val name    = section.getString("name", "&7$categoryId") ?: "&7$categoryId"
            val command = section.getString("command")   // null なら categoryId を使う

            val rawItems = section.getStringList("items")
            val items = mutableListOf<ShopItem>()

            rawItems.forEach { line ->
                val trimmed = line.trim()
                // 空行 or コメント行 (ページ区切り) はスキップ
                if (trimmed.isEmpty() || trimmed.startsWith("#")) return@forEach
                // $ プレフィックスの特殊参照は現時点でスキップ
                if (trimmed.startsWith("$")) {
                    plugin.logger.fine("特殊アイテム参照をスキップ: $trimmed (カテゴリ: $categoryId)")
                    skipped++
                    return@forEach
                }

                val shopItem = parseItemLine(trimmed, categoryId) ?: run { skipped++; return@forEach }
                items.add(shopItem)

                // 売却可能アイテムを逆引きマップに登録 (複数カテゴリに同じ素材があれば上書き)
                if (shopItem.canSell && shopItem.material != null) {
                    sellPriceMap[shopItem.material] = shopItem.sellPrice
                }
                loaded++
            }

            categories[categoryId] = ShopCategory(
                id = categoryId,
                displayName = name,
                command = command,
                items = items
            )
        }

        plugin.logger.info("ショップをロード: ${categories.size} カテゴリ / $loaded アイテム ($skipped スキップ)")
    }

    fun getCategory(id: String): ShopCategory? = categories[id]
    fun getAllCategories(): Map<String, ShopCategory> = categories.toMap()

    /**
     * 売却価格の逆引き。一括売却 GUI で使用する。
     * @return 売却価格。登録されていなければ null
     */
    fun getSellPrice(material: Material): Double? = sellPriceMap[material]

    /** 全カテゴリを再読み込みする */
    fun reload() = loadAll()

    // ============================
    // パース
    // ============================

    /**
     * "material buyPrice sellPrice" の1行をパースして ShopItem を返す。
     * フォーマットエラーの場合は警告を出して null を返す。
     *
     * 価格には整数と小数どちらも対応 (例: "1.7", "5", "50000")。
     * sellPrice を 0 にすると売却不可。buyPrice を 0 にすると購入不可。
     */
    private fun parseItemLine(line: String, categoryId: String): ShopItem? {
        val parts = line.split("\\s+".toRegex())
        if (parts.size < 3) {
            plugin.logger.warning("不正な行フォーマット (カテゴリ: $categoryId): '$line'  → 'material buyPrice sellPrice' が必要です")
            return null
        }

        val materialId = parts[0].uppercase()
        val buyPrice   = parts[1].toDoubleOrNull()
        val sellPrice  = parts[2].toDoubleOrNull()

        if (buyPrice == null || sellPrice == null) {
            plugin.logger.warning("価格の解析に失敗 (カテゴリ: $categoryId): '$line'")
            return null
        }

        // Bukkit の Material Enum に変換
        val material = runCatching { Material.valueOf(materialId) }.getOrElse {
            plugin.logger.warning("不明なマテリアル: $materialId (カテゴリ: $categoryId) — スキップします")
            null
        }

        return ShopItem(
            material  = material,
            materialId = parts[0].lowercase(),
            buyPrice  = buyPrice,
            sellPrice = sellPrice
        )
    }
}