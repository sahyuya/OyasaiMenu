package com.github.sahyuya.oyasaiMenu.engine

import com.github.sahyuya.oyasaiMenu.OyasaiMenu
import com.github.sahyuya.oyasaiMenu.manager.EconomyManager
import com.github.sahyuya.oyasaiMenu.manager.TokenCurrencyManager
import com.github.sahyuya.oyasaiMenu.util.GuiUtil.comp
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.Registry
import org.bukkit.entity.Player
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.ItemFlag
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.SkullMeta

/**
 * NavBar
 *
 * 全ポップアップ型メニューの下1列 (スロット45〜53) を共通描画する。
 *
 * ■ ナビゲーションバー (新menu構成.txtの順序)
 *   [45] プレイヤーヘッド  — オンライン/TPS/経験値/残高/ポイント
 *   [46] RED_CONCRETE_POWDER    — チャンネルメニュー
 *   [47] ORANGE_CONCRETE_POWDER — 一括売却 (売却とショップを入替)
 *   [48] YELLOW_CONCRETE_POWDER — サーバーショップ
 *   [49] LIME_CONCRETE_POWDER   — SocialLikes
 *   [50] CYAN_CONCRETE_POWDER   — CarBuilder
 *   [51] BLUE_CONCRETE_POWDER   — ユーティリティ
 *   [52] PURPLE_CONCRETE_POWDER — マクロ
 *   [53] PINK_CONCRETE_POWDER   — リンク集
 */
object NavBar {

    data class NavEntry(val slot: Int, val material: Material, val name: String, val popupId: String)

    val entries = listOf(
        NavEntry(46, Material.RED_CONCRETE_POWDER,    "&cチャンネルメニュー", "channel"),
        NavEntry(47, Material.ORANGE_CONCRETE_POWDER, "&6一括売却",           "sellmenu"),
        NavEntry(48, Material.YELLOW_CONCRETE_POWDER, "&eサーバーショップ",   "shopindex"),
        NavEntry(49, Material.LIME_CONCRETE_POWDER,   "&aSocialLikes",        "sociallikes"),
        NavEntry(50, Material.CYAN_CONCRETE_POWDER,   "&bCarBuilder",         "carbuilder"),
        NavEntry(51, Material.BLUE_CONCRETE_POWDER,   "&9ユーティリティ",     "utility"),
        NavEntry(52, Material.PURPLE_CONCRETE_POWDER, "&5マクロ",             "macromenu"),
        NavEntry(53, Material.PINK_CONCRETE_POWDER,   "&dリンク集",           "links")
    )

    /** ポップアップIDからナビスロットを逆引きする */
    fun slotForPopup(popupId: String): Int =
        entries.find { it.popupId == popupId }?.slot ?: -1

    /**
     * インベントリの下1列 (45〜53) にナビバーを描画する。
     * スロット 45 はプレイヤーヘッド、46〜53 はナビボタン。
     * @param activeSlot 強調するスロット (46〜53)。-1 = 強調なし
     */
    fun apply(inv: Inventory, player: Player, plugin: OyasaiMenu, activeSlot: Int = -1) {
        inv.setItem(45, buildPlayerHead(player, plugin))

        val unbreaking = runCatching {
            Registry.ENCHANTMENT.get(NamespacedKey.minecraft("unbreaking"))
        }.getOrNull()

        entries.forEach { entry ->
            val item = ItemStack(entry.material)
            val meta = item.itemMeta!!
            meta.displayName(comp(entry.name))
            if (entry.slot == activeSlot && unbreaking != null) {
                meta.addEnchant(unbreaking, 1, true)
                meta.addItemFlags(ItemFlag.HIDE_ENCHANTS)
                meta.lore(listOf(comp("&a▶ 現在のメニュー")))
            }
            item.itemMeta = meta
            inv.setItem(entry.slot, item)
        }
    }

    /** スロット45用のプレイヤーヘッド: オンライン/TPS/経験値/残高/ポイントを表示 */
    fun buildPlayerHead(player: Player, plugin: OyasaiMenu): ItemStack {
        val skull = ItemStack(Material.PLAYER_HEAD)
        val meta  = skull.itemMeta as SkullMeta
        meta.owningPlayer = player
        meta.displayName(comp("&f${player.name}"))
        meta.lore(listOf(
            comp("&7オンライン: &f${Bukkit.getOnlinePlayers().size}人"),
            comp("&7TPS: &f${String.format("%.1f", Bukkit.getTPS()[0])}"),
            comp("&7経験値 Lv: &f${player.level}"),
            comp("&7所持金: &f${if (EconomyManager.isAvailable) EconomyManager.format(EconomyManager.getBalance(player)) else "---"}"),
            comp("&7ポイント: &f${if (TokenCurrencyManager.isAvailable) "${TokenCurrencyManager.format(TokenCurrencyManager.getTokens(player))}P" else "---"}"),
            comp(""),
            comp("&eクリックで更新")
        ))
        skull.itemMeta = meta
        return skull
    }

    /** スロット 0〜44 の空きを指定ガラスで埋める (既存アイテムは上書きしない) */
    fun fillGlass(inv: Inventory, glass: Material) {
        val item = ItemStack(glass)
        val meta = item.itemMeta!!
        meta.displayName(comp(" "))
        item.itemMeta = meta
        for (i in 0..44) { if (inv.getItem(i) == null) inv.setItem(i, item) }
    }
}