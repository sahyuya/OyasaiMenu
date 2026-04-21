package com.github.sahyuya.oyasaiMenu.model

import org.bukkit.Material

// ============================================================
// ポイントショップ定義モデル
// menus/shop/pointshop.yml の形式に対応
// ============================================================

/**
 * ポイントショップの1カテゴリ。
 *
 * YAML 例:
 *   utilities:
 *     name: '&cポイントショップ'
 *     items:
 *       '0':
 *         icon: GLASS
 *         name: '&c&l%player%の酸素ボンベ'
 *         lore: [...]
 *         cost: 30
 *         message: '&bTM &8» ...'
 *         commands: [...]
 */
data class PointShopCategory(
    val id: String,
    val displayName: String,
    /** スロット番号文字列 → アイテム定義の順序付きマップ */
    val items: Map<String, PointShopItem>
) {
    val itemsPerPage: Int = 45
    val itemList: List<PointShopItem> = items.entries
        .sortedBy { it.key.toIntOrNull() ?: Int.MAX_VALUE }
        .map { it.value }
    val pageCount: Int get() = maxOf(1, (itemList.size + itemsPerPage - 1) / itemsPerPage)

    fun getPage(page: Int): List<PointShopItem> {
        val start = page * itemsPerPage
        val end   = minOf(start + itemsPerPage, itemList.size)
        return if (start >= itemList.size) emptyList() else itemList.subList(start, end)
    }
}

/**
 * ポイントショップの1商品。
 *
 * @param key        YAML キー (例: "0", "1")
 * @param icon       表示マテリアル
 * @param name       アイテム表示名 (%player% 等プレースホルダ対応)
 * @param lore       説明文 (%tokens%, %price% 等対応)
 * @param cost       必要ポイント数 (Long)
 * @param message    購入時にプレイヤーへ送るメッセージ
 * @param commands   購入時に実行するコマンドリスト (%player%, %price% 等対応)
 */
data class PointShopItem(
    val key: String,
    val icon: Material,
    val name: String,
    val lore: List<String>,
    val cost: Long,
    val message: String,
    val commands: List<String>,
    /**
     * true = 購入後にGUIを閉じる (例: シュルカーボックスを開くアイテムなど)。
     * pointshop.yml の各アイテムに "close-on-purchase: true" を追加することで設定。
     * デフォルト false = 購入後もGUIを開いたままにする。
     */
    val closeOnPurchase: Boolean = false
)

/** ポイントショップのプレイヤー状態 */
data class PlayerPointShopState(
    val categoryId: String,
    val page: Int = 0
)