package com.github.sahyuya.oyasaiMenu.engine

import com.github.sahyuya.oyasaiMenu.OyasaiMenu
import com.github.sahyuya.oyasaiMenu.manager.CooldownManager
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
 * /adminmenu (/admenu) で開く管理者専用メニュー。
 *
 * 機能:
 *   [10] リロード
 *   [12] アナウンス編集 (本と羽ペン)
 *   [14] ショップ商品管理 (チャットでコマンドガイド表示)
 *   [16] 販売ブラックリスト管理 (チャットでコマンドガイド表示)
 *   [34] 閉じる
 */
class AdminEngine(private val plugin: OyasaiMenu) : Listener {

    private val activePlayers: MutableSet<String> = mutableSetOf()

    fun openAdminMenu(player: Player) {
        if (!player.hasPermission("oyasaimenu.admin")) {
            player.sendMessage(c("&c管理者権限がありません。")); return
        }
        val inv = buildInventory()
        player.openInventory(inv)
        activePlayers.add(player.uniqueId.toString())
    }

    private fun buildInventory(): Inventory {
        val inv = Bukkit.createInventory(null, 54, comp("&4⚙ 管理者メニュー"))

        inv.setItem(10, makeItem(Material.EMERALD, "&aリロード",
            listOf("&7全 YAML を再読み込みします", "", "&eクリックで実行")))

        inv.setItem(12, makeItem(Material.WRITABLE_BOOK, "&bアナウンス編集",
            listOf("&7本と羽ペンでお知らせを編集します", "", "&eクリックで本を受け取る")))

        inv.setItem(14, makeItem(Material.CHEST, "&eショップ商品管理",
            listOf(
                "&7コマンドで商品を追加・削除できます",
                "",
                "&f/menuedit shop &7<category> list",
                "&f/menuedit shop &7<category> add <mat> <buy> <sell>",
                "&f/menuedit shop &7<category> remove <index>",
                "",
                "&eクリックでコマンドガイドをチャットに表示"
            )))

        inv.setItem(16, makeItem(Material.BARRIER, "&c販売ブラックリスト管理",
            listOf(
                "&7売却禁止アイテムを管理します",
                "",
                "&f/menuedit blacklist list",
                "&f/menuedit blacklist add &7<material>",
                "&f/menuedit blacklist remove &7<material>",
                "",
                "&eクリックでコマンドガイドをチャットに表示"
            )))

        inv.setItem(34, makeItem(Material.OAK_DOOR, "&7閉じる",
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
        if (CooldownManager.isClickOnCooldown(player.uniqueId)) return
        when (event.rawSlot) {
            10 -> {
                plugin.reload()
                player.sendMessage(c("&aリロードしました。"))
            }
            12 -> {
                player.closeInventory()
                Bukkit.getScheduler().runTaskLater(plugin, Runnable {
                    plugin.announcementManager.openBookEditor(player)
                }, 1L)
            }
            14 -> {
                player.closeInventory()
                player.sendMessage(c("&b--- ショップ商品管理 ---"))
                player.sendMessage(c("&f/menuedit shop &7<category> list &8— 一覧"))
                player.sendMessage(c("&f/menuedit shop &7<category> add <material> <buy> <sell> &8— 追加"))
                player.sendMessage(c("&f/menuedit shop &7<category> remove <index> &8— 削除"))
                val cats = plugin.shopLoader.getAllCategories().keys.joinToString(", ")
                player.sendMessage(c("&7カテゴリ: &f$cats"))
            }
            16 -> {
                player.closeInventory()
                player.sendMessage(c("&b--- 販売ブラックリスト管理 ---"))
                player.sendMessage(c("&f/menuedit blacklist list &8— 一覧"))
                player.sendMessage(c("&f/menuedit blacklist add &7<material> &8— 追加"))
                player.sendMessage(c("&f/menuedit blacklist remove &7<material> &8— 削除"))
                val count = plugin.sellBlacklistManager.getMaterials().size
                player.sendMessage(c("&7現在 &f${count}&7 種登録済み"))
            }
            34 -> player.closeInventory()
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