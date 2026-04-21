package com.github.sahyuya.oyasaiMenu.engine

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.ItemStack

/**
 * NavBar
 *
 * 全ポップアップ型メニューの下1列 (スロット 45〜53) を共通描画するユーティリティ。
 *
 * ■ ナビゲーションバー配置
 *   [45] サーバー/個人情報  GRAY_STAINED_GLASS_PANE (→ InfoEngine)
 *   [46] チャンネルメニュー  RED_CONCRETE_POWDER
 *   [47] サーバーショップ    ORANGE_CONCRETE_POWDER
 *   [48] 一括売却           YELLOW_CONCRETE_POWDER
 *   [49] SocialLikes        LIME_CONCRETE_POWDER
 *   [50] CarBuilder          CYAN_CONCRETE_POWDER
 *   [51] ユーティリティ      BLUE_CONCRETE_POWDER
 *   [52] マクロ             PURPLE_CONCRETE_POWDER
 *   [53] リンク集            PINK_CONCRETE_POWDER
 *
 * 「現在開いているメニュー」に対応するスロットはグローを模した
 * エンチャントグリマーなどで強調表示する (activeSlot で指定)。
 */
object NavBar {

    /** ナビゲーション定義 */
    data class NavEntry(
        val slot: Int,
        val material: Material,
        val name: String,
        val lore: List<String>
    )

    val entries: List<NavEntry> = listOf(
        NavEntry(45, Material.GRAY_STAINED_GLASS_PANE, "&f⚙ サーバー/個人情報",
            listOf("&7TPS・残高・ポイントを確認")),
        NavEntry(46, Material.RED_CONCRETE_POWDER,    "&cチャンネルメニュー",
            listOf("&7チャットチャンネルを切り替え")),
        NavEntry(47, Material.ORANGE_CONCRETE_POWDER, "&6サーバーショップ",
            listOf("&7アイテムの購入・売却")),
        NavEntry(48, Material.YELLOW_CONCRETE_POWDER, "&e一括売却",
            listOf("&7アイテムをまとめて売却", "&7(クリエイティブ不可)")),
        NavEntry(49, Material.LIME_CONCRETE_POWDER,   "&aSocialLikes",
            listOf("&7建築の評価・探索")),
        NavEntry(50, Material.CYAN_CONCRETE_POWDER,   "&bCarBuilder",
            listOf("&7カービルダーメニュー")),
        NavEntry(51, Material.BLUE_CONCRETE_POWDER,   "&9ユーティリティ",
            listOf("&7コマンドショートカット集")),
        NavEntry(52, Material.PURPLE_CONCRETE_POWDER, "&5マクロ",
            listOf("&7自分専用コマンドマクロ")),
        NavEntry(53, Material.PINK_CONCRETE_POWDER,   "&dリンク集",
            listOf("&7Wiki・Discord・WebMAP など"))
    )

    /**
     * インベントリの下1列 (45〜53) にナビゲーションバーを描画する。
     * @param inv         対象インベントリ (54スロット)
     * @param activeSlot  現在開いているメニューのスロット番号。
     *                    対応エントリを視覚的に強調する。
     */
    fun apply(inv: Inventory, activeSlot: Int = -1) {
        entries.forEach { entry ->
            val item = ItemStack(entry.material)
            val meta = item.itemMeta!!
            meta.displayName(comp(entry.name))
            val loreFull = entry.lore.map { comp(it) }.toMutableList()
            if (entry.slot == activeSlot) {
                loreFull.add(comp(""))
                loreFull.add(comp("&a▶ 現在のメニュー"))
            }
            meta.lore(loreFull)
            // 現在のメニューはエンチャントグリマーで光らせる
            if (entry.slot == activeSlot) {
                meta.addEnchant(
                    org.bukkit.Registry.ENCHANTMENT.get(
                        org.bukkit.NamespacedKey.minecraft("unbreaking")
                    )!!, 1, true
                )
                meta.addItemFlags(org.bukkit.inventory.ItemFlag.HIDE_ENCHANTS)
            }
            item.itemMeta = meta
            inv.setItem(entry.slot, item)
        }
    }

    /**
     * 色付きガラスパネルで上部スロット (0〜44) を埋める。
     * @param glass  使用するガラスパネルのマテリアル
     */
    fun fillTop(inv: Inventory, glass: Material, name: String = " ") {
        val item = ItemStack(glass)
        val meta = item.itemMeta!!
        meta.displayName(comp(name))
        item.itemMeta = meta
        for (i in 0..44) {
            if (inv.getItem(i) == null) inv.setItem(i, item)
        }
    }

    private fun comp(text: String): Component =
        LegacyComponentSerializer.legacyAmpersand().deserialize(text)
}