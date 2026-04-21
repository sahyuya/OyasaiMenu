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
 * LinksEngine
 *
 * リンク集GUI。URLをチャットに出力する。
 * ポップアップ型: 下1列ナビ、上5列にリンクボタン。
 */
class LinksEngine(private val plugin: OyasaiMenu) : Listener {

    private val activePlayers: MutableSet<String> = mutableSetOf()

    private data class LinkDef(
        val slot: Int,
        val material: Material,
        val name: String,
        val url: String,
        val lore: List<String> = emptyList()
    )

    private val links: List<LinkDef> = listOf(
        LinkDef(2,  Material.COD_BUCKET,       "&bおやさいWiki",
            "http://wiki.oyasai.io/",
            listOf("&7サーバーWikiサイト")),
        LinkDef(11, Material.SALMON_BUCKET,    "&9Discordサーバー",
            "https://discord.gg/rgKayZPW6X",
            listOf("&7Discordコミュニティ")),
        LinkDef(20, Material.TROPICAL_FISH_BUCKET, "&aおやさい鯖 Web MAP",
            "http://oyasai.io:8100",
            listOf("&7ダイナマップ")),
        LinkDef(29, Material.PUFFERFISH_BUCKET,"&6投票 (JMS)",
            "https://minecraft.jp/servers/oyasai.io/vote",
            listOf("&7Minecraft Japan Server", "&7(minecraft.jp)")),
        LinkDef(38, Material.AXOLOTL_BUCKET,   "&d投票 (MinePortal)",
            "https://mineportal.jp/servers/clrfqwdka0000lgansuxtj9mi",
            listOf("&7MinePortal 投票ページ"))
    )

    fun openLinks(player: Player) {
        val inv = buildInventory()
        player.openInventory(inv)
        activePlayers.add(player.uniqueId.toString())
    }

    private fun buildInventory(): Inventory {
        val inv = Bukkit.createInventory(null, 54, comp("&dリンク集"))
        links.forEach { lk ->
            val lore = lk.lore.toMutableList()
            lore += ""
            lore += "&7${lk.url}"
            lore += ""
            lore += "&eクリックでURLをチャットに表示"
            inv.setItem(lk.slot, makeItem(lk.material, lk.name, lore))
        }
        NavBar.apply(inv, activeSlot = 53)
        NavBar.fillTop(inv, Material.PINK_STAINED_GLASS_PANE)
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
            else -> {
                val lk = links.find { it.slot == slot } ?: return
                player.sendMessage(c("&d${lk.name.replace("§[0-9a-fk-or]".toRegex(), "")} &7→ &f${lk.url}"))
            }
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
    private fun c(t: String) = t.replace('&', '\u00A7')
}