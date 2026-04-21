package com.github.sahyuya.oyasaiMenu.engine

import com.github.sahyuya.oyasaiMenu.OyasaiMenu
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryCloseEvent
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.ItemStack

/**
 * SocialLikesEngine
 *
 * SocialLikes プラグインのショートカットGUI。
 * ポップアップ型: 下1列ナビ、上5列にボタン配置。
 */
class SocialLikesEngine(private val plugin: OyasaiMenu) : Listener {

    private val activePlayers: MutableSet<String> = mutableSetOf()

    private data class CmdItem(
        val slot: Int, val material: Material, val name: String,
        val lore: List<String>, val cmd: String, val isConsole: Boolean = false
    )

    private val items: List<CmdItem> = listOf(
        CmdItem(2,  Material.OAK_SIGN,          "&a建築一覧",
            listOf("&7/slbuild で建築一覧を表示"), "slbuild"),
        CmdItem(11, Material.BIRCH_SIGN,         "&b自分の建築を見る",
            listOf("&7/sluser で自分の建築一覧"), "sluser %player%"),
        CmdItem(20, Material.ENCHANTED_BOOK.also { },
            "&eSocialLikesメニュー",
            listOf("&7/slmenu でメインメニュー"), "slmenu"),
        CmdItem(29, Material.SPYGLASS,           "&d近くの建築を探す",
            listOf("&7/slnear で周囲の建築を探す"), "slnear"),
        CmdItem(38, Material.COMPASS,            "&6空き地へテレポート",
            listOf("&7/vtp でランダムな空き地へ"), "vtp"),
        CmdItem(4,  Material.RECOVERY_COMPASS,   "&5バイオーム指定でテレポート",
            listOf("&7バイオームを指定して空き地へ", "", "&eクリックでメニューを開く"),
            "vtp", isConsole = false) // バイオーム選択は後日実装
    )

    fun openSocialLikes(player: Player) {
        val inv = buildInventory(player)
        player.openInventory(inv)
        activePlayers.add(player.uniqueId.toString())
    }

    private fun buildInventory(player: Player): Inventory {
        val inv = Bukkit.createInventory(null, 54, comp("&aSocialLikes"))
        items.forEach { it2 ->
            inv.setItem(it2.slot, makeItem(it2.material, it2.name, it2.lore))
        }
        NavBar.apply(inv, activeSlot = 49)
        NavBar.fillTop(inv, Material.LIME_STAINED_GLASS_PANE)
        return inv
    }

    @EventHandler
    fun onInventoryClick(event: InventoryClickEvent) {
        val player = event.whoClicked as? Player ?: return
        if (!activePlayers.contains(player.uniqueId.toString())) return
        if (event.clickedInventory == player.inventory) {
            if (event.isShiftClick) event.isCancelled = true; return
        }
        event.isCancelled = true
        val slot = event.rawSlot
        if (slot in 45..53) { plugin.navHandler.handleNavClick(player, slot); return }
        val it2 = items.find { it.slot == slot } ?: return
        player.performCommand(it2.cmd.replace("%player%", player.name))
        player.closeInventory()
    }

    @EventHandler
    fun onInventoryClose(event: InventoryCloseEvent) {
        activePlayers.remove((event.player as? Player)?.uniqueId?.toString() ?: return)
    }

    private fun makeItem(mat: Material, name: String, lore: List<String>): ItemStack {
        val item = ItemStack(mat); val meta = item.itemMeta!!
        meta.displayName(comp(name)); meta.lore(lore.map { comp(it) })
        item.itemMeta = meta; return item
    }

    private fun comp(t: String): Component = LegacyComponentSerializer.legacyAmpersand().deserialize(t)
}