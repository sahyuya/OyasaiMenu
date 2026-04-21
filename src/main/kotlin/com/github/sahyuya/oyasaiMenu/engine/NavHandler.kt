package com.github.sahyuya.oyasaiMenu.engine

import com.github.sahyuya.oyasaiMenu.OyasaiMenu
import org.bukkit.entity.Player

/**
 * NavHandler
 *
 * 全ポップアップ型メニューから共有されるナビバークリック処理。
 * 各エンジンは onInventoryClick でナビゾーン (45〜53) のクリックを
 * plugin.navHandler.handleNavClick() に委譲する。
 *
 * ショップ系 (ShopEngine/PointShopEngine) はナビを持たないため対象外。
 */
class NavHandler(private val plugin: OyasaiMenu) {

    /**
     * スロット番号に応じた画面を開く。
     * 呼び出し元は event.isCancelled = true を設定済みであること。
     */
    fun handleNavClick(player: Player, slot: Int) {
        when (slot) {
            45 -> plugin.infoEngine.openInfo(player)
            46 -> plugin.channelEngine.openChannel(player)
            47 -> plugin.menuEngine.openMenu(player, "shop/index")
            48 -> plugin.sellEngine.openSellMenu(player)
            49 -> plugin.socialLikesEngine.openSocialLikes(player)
            50 -> plugin.carBuilderEngine.openCarBuilder(player)
            51 -> plugin.utilityEngine.openUtility(player)
            52 -> plugin.macroEngine.openMacroList(player)
            53 -> plugin.linksEngine.openLinks(player)
        }
    }
}