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
 * CarBuilderEngine
 *
 * CarBuilder プラグインのショートカットGUI。
 * ポップアップ型: 下1列ナビ、上5列にボタン。
 */
class CarBuilderEngine(private val plugin: OyasaiMenu) : Listener {

    private val activePlayers: MutableSet<String> = mutableSetOf()

    fun openCarBuilder(player: Player) {
        val inv = buildInventory(player)
        player.openInventory(inv)
        activePlayers.add(player.uniqueId.toString())
    }

    private fun buildInventory(player: Player): Inventory {
        val inv = Bukkit.createInventory(null, 54, comp("&bCarBuilder"))

        inv.setItem(22, makeItem(Material.MINECART, "&bCarBuilderメニューを開く",
            listOf("&7/rvmenu を実行します", "", "&eクリックで開く")))

        NavBar.apply(inv, activeSlot = 50)
        NavBar.fillTop(inv, Material.CYAN_STAINED_GLASS_PANE)
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
        when {
            slot in 45..53 -> plugin.navHandler.handleNavClick(player, slot)
            slot == 22 -> { player.performCommand("rvmenu"); player.closeInventory() }
        }
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