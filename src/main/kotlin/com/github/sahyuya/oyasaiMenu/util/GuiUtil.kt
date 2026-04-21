package com.github.sahyuya.oyasaiMenu.util

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
import org.bukkit.Material
import org.bukkit.inventory.ItemStack

/**
 * GuiUtil
 *
 * 全 Engine / Manager に重複していた GUI 関連ユーティリティを一箇所に集約する。
 *
 * ■ 重複していた実装
 *   AdminEngine, MacroEngine, PointShopEngine, PopupMenuEngine,
 *   SellEngine, ShopEngine — それぞれが同一の makeItem / comp / c を持っていた。
 *
 * ■ 移行方法
 *   各エンジンの private fun makeItem / comp / c を削除し、
 *   GuiUtil.makeItem / GuiUtil.comp / GuiUtil.c に置き換える。
 *   または import static 風に:
 *     private fun makeItem(m: Material, n: String, l: List<String> = emptyList()) =
 *         GuiUtil.makeItem(m, n, l)
 *   として薄いラッパーを残すだけでも OK。
 */
object GuiUtil {

    /**
     * GUI 用の ItemStack を生成する。
     * displayName と Lore に & カラーコードを適用する。
     */
    fun makeItem(mat: Material, name: String, lore: List<String> = emptyList()): ItemStack {
        val item = ItemStack(mat)
        val meta = item.itemMeta ?: return item
        meta.displayName(comp(name))
        if (lore.isNotEmpty()) meta.lore(lore.map { comp(it) })
        item.itemMeta = meta
        return item
    }

    /**
     * & カラーコードを Adventure Component に変換する。
     * タイトルやアイテム名に使用。
     */
    fun comp(text: String): Component =
        LegacyComponentSerializer.legacyAmpersand().deserialize(text)

    /**
     * & カラーコードを § (レガシー形式) に変換する。
     * sendMessage など文字列を直接渡す API に使用。
     */
    fun c(text: String): String = text.replace('&', '\u00A7')

    /**
     * 装飾用の無名スペーサーガラスを生成する。
     */
    fun spacer(mat: Material = Material.GRAY_STAINED_GLASS_PANE): ItemStack =
        makeItem(mat, " ")
}