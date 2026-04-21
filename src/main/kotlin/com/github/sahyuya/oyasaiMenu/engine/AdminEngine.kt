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
 * AdminEngine
 *
 * 管理者専用メニュー。/adminmenu (/admenu) で開く。
 * 総合メニューとは完全に独立している。
 *
 * 現在の機能:
 *   - メニューのリロード
 *   - お知らせの管理 (将来実装)
 *   - インゲーム編集モード起動
 *   - その他管理コマンドのショートカット
 */
class AdminEngine(private val plugin: OyasaiMenu) : Listener {

    private val activePlayers: MutableSet<String> = mutableSetOf()

    fun openAdminMenu(player: Player) {
        if (!player.hasPermission("oyasaimenu.admin")) {
            player.sendMessage(c("&c管理者権限がありません。")); return
        }
        val inv = buildInventory(player)
        player.openInventory(inv)
        activePlayers.add(player.uniqueId.toString())
    }

    private fun buildInventory(player: Player): Inventory {
        val inv = Bukkit.createInventory(null, 54, comp("&4⚙ 管理者メニュー"))

        inv.setItem(10, makeItem(Material.EMERALD, "&aメニューをリロード",
            listOf("&7全YAMLを再読み込みします", "", "&eクリックで実行")))

        inv.setItem(12, makeItem(Material.COMMAND_BLOCK, "&bインゲーム編集モード",
            listOf("&7/menuedit でメニューを編集", "", "&eクリックで開く")))

        inv.setItem(14, makeItem(Material.PAPER, "&eお知らせ管理",
            listOf("&7総合メニューのお知らせを編集", "&7(将来実装予定)", "", "&7現在は直接 announcements.yml を編集")))

        inv.setItem(16, makeItem(Material.BARRIER, "&c閉じる",
            listOf("&7管理者メニューを閉じます")))

        // ガラス装飾
        val glass = makeItem(Material.BLACK_STAINED_GLASS_PANE, " ", emptyList())
        for (i in 0..53) { if (inv.getItem(i) == null) inv.setItem(i, glass) }

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
        when (event.rawSlot) {
            10 -> {
                plugin.reload()
                player.sendMessage(c("&aリロードしました。"))
            }
            12 -> {
                player.closeInventory()
                val defaultId = plugin.config.getString("menu.default", "root") ?: "root"
                Bukkit.getScheduler().runTaskLater(plugin, Runnable {
                    plugin.menuEngine.openMenuInEditMode(player, defaultId)
                }, 1L)
            }
            16 -> player.closeInventory()
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