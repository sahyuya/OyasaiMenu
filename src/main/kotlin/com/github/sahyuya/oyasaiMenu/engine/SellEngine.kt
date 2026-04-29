package com.github.sahyuya.oyasaiMenu.engine

import com.github.sahyuya.oyasaiMenu.OyasaiMenu
import com.github.sahyuya.oyasaiMenu.manager.EconomyManager
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryCloseEvent
import org.bukkit.event.inventory.InventoryDragEvent
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.ItemStack

/**
 * SellEngine
 *
 * 変更点:
 *   - 販売ブラックリスト (SellBlacklistManager) のチェックを追加。
 *     ブラックリストに登録されたマテリアル、またはカスタム名付きアイテムは売却不可。
 */
class SellEngine(private val plugin: OyasaiMenu) : Listener {

    private val openPlayers: MutableSet<String> = mutableSetOf()

    fun openSellMenu(player: Player) {
        if (player.gameMode == org.bukkit.GameMode.CREATIVE) {
            player.sendMessage(c("&cクリエイティブモードでは売却できません。"))
            return
        }
        if (!EconomyManager.isAvailable) {
            player.sendMessage(c("&cショップには Vault と経済プラグインが必要です。"))
            return
        }
        player.openInventory(buildSellInventory())
        openPlayers.add(player.uniqueId.toString())
        player.sendMessage(c("&a売却したいアイテムを入れて &e「売却実行」 &aを押してください。"))
    }

    // ============================
    // インベントリ構築
    // ============================

    private fun buildSellInventory(): Inventory {
        val inv = Bukkit.createInventory(null, 54, comp("&6アイテム一括売却"))
        buildControlBar(inv, null)
        return inv
    }

    private fun buildControlBar(inv: Inventory, result: String?) {
        val glass = makeItem(Material.BLACK_STAINED_GLASS_PANE, " ")
        listOf(46, 48, 50, 51, 52, 53).forEach { inv.setItem(it, glass) }

        inv.setItem(45, makeItem(Material.ARROW, "&c閉じる",
            listOf("&7アイテムを返却して閉じます")))

        if (result == null) {
            inv.setItem(47, makeItem(Material.BOOK, "&7売却結果",
                listOf("&7売却実行後にここに金額が表示されます")))
        } else {
            inv.setItem(47, makeItem(Material.EMERALD, "&a売却完了!", listOf(result)))
        }

        inv.setItem(49, makeItem(Material.LIME_CONCRETE, "&a▶ 売却実行",
            listOf("&7登録アイテムのみ換金します", "&7売却不可アイテムはインベントリへ返却")))
    }

    // ============================
    // イベントハンドラ
    // ============================

    @EventHandler
    fun onInventoryClick(event: InventoryClickEvent) {
        val player = event.whoClicked as? Player ?: return
        if (!openPlayers.contains(player.uniqueId.toString())) return

        val slot = event.rawSlot

        if (event.clickedInventory == player.inventory) {
            if (event.isShiftClick) {
                event.isCancelled = true
                val item = event.currentItem?.clone() ?: return
                val inv = player.openInventory.topInventory
                val emptySlot = (0..44).firstOrNull { inv.getItem(it) == null }
                if (emptySlot != null) {
                    inv.setItem(emptySlot, item)
                    if (event.currentItem!!.amount == item.amount) {
                        player.inventory.setItem(event.slot, null)
                    }
                    Bukkit.getScheduler().runTaskLater(plugin, Runnable { updateResult(player, inv, null) }, 1L)
                } else {
                    player.sendMessage(c("&c投入エリアがいっぱいです。"))
                }
            }
            return
        }

        if (slot in 45..53) {
            event.isCancelled = true
            when (slot) {
                45 -> player.closeInventory()
                49 -> handleSell(player)
            }
            return
        }
    }

    @EventHandler
    fun onInventoryDrag(event: InventoryDragEvent) {
        val player = event.whoClicked as? Player ?: return
        if (!openPlayers.contains(player.uniqueId.toString())) return
        if (event.rawSlots.any { it in 45..53 }) {
            event.isCancelled = true
        }
    }

    @EventHandler
    fun onInventoryClose(event: InventoryCloseEvent) {
        val player = event.player as? Player ?: return
        if (!openPlayers.remove(player.uniqueId.toString())) return
        returnItems(player, event.inventory)
    }

    // ============================
    // 売却処理
    // ============================

    private fun handleSell(player: Player) {
        val inv = player.openInventory.topInventory
        val items = getInputItems(inv)
        if (items.isEmpty()) {
            player.sendMessage(c("&c売却できるアイテムがありません。"))
            return
        }

        var earned = 0.0
        var count  = 0
        var unsellableCount = 0
        var blacklistedCount = 0

        items.forEach { (slot, stack) ->
            // ★ 販売ブラックリストチェック
            if (plugin.sellBlacklistManager.isBlacklisted(stack)) {
                blacklistedCount++
                return@forEach
            }
            val price = plugin.shopLoader.getSellPrice(stack.type)
            if (price != null && price > 0) {
                earned += price * stack.amount
                count  += stack.amount
                inv.setItem(slot, null)
            } else {
                unsellableCount++
            }
        }

        if (count == 0) {
            val msg = buildString {
                append("&c売却可能なアイテムがありませんでした。")
                if (blacklistedCount > 0) append(" &7(ブラックリスト対象: ${blacklistedCount}種)")
            }
            player.sendMessage(c(msg))
            return
        }

        EconomyManager.deposit(player, earned)

        val parts = mutableListOf<String>()
        if (unsellableCount > 0) parts.add("未登録 ${unsellableCount}種はGUIに残ります")
        if (blacklistedCount > 0) parts.add("BL対象 ${blacklistedCount}種はGUIに残ります")
        val suffix = if (parts.isEmpty()) "" else " &7(${parts.joinToString(", ")})"
        val resultText = "&f${count}個 → &a+${EconomyManager.format(earned)}$suffix"
        updateResult(player, inv, resultText)

        player.sendMessage(c(
            "&a一括売却完了! &f${count}個 &7→ &f+${EconomyManager.format(earned)}\n" +
            "&7残高: &f${EconomyManager.format(EconomyManager.getBalance(player))}"
        ))
        if (unsellableCount > 0)
            player.sendMessage(c("&7未登録アイテム (${unsellableCount}種) はGUIに残っています。"))
        if (blacklistedCount > 0)
            player.sendMessage(c("&cブラックリスト対象アイテム (${blacklistedCount}種) は売却できません。"))
        player.playSound(player.location, org.bukkit.Sound.ENTITY_PLAYER_LEVELUP, 1f, 1f)
    }

    private fun updateResult(player: Player, inv: Inventory, result: String?) {
        buildControlBar(inv, result)
    }

    // ============================
    // ユーティリティ
    // ============================

    private fun getInputItems(inv: Inventory): Map<Int, ItemStack> =
        (0..44).mapNotNull { i ->
            inv.getItem(i)?.takeIf { it.type != Material.AIR }?.let { i to it }
        }.toMap()

    private fun returnItems(player: Player, inv: Inventory) {
        for (i in 0..44) {
            val item = inv.getItem(i)?.takeIf { it.type != Material.AIR } ?: continue
            player.inventory.addItem(item).values.forEach {
                player.world.dropItemNaturally(player.location, it)
            }
        }
    }

    private fun makeItem(mat: Material, name: String, lore: List<String> = emptyList()): ItemStack {
        val item = ItemStack(mat)
        val meta = item.itemMeta ?: return item
        meta.displayName(comp(name))
        if (lore.isNotEmpty()) meta.lore(lore.map { comp(it) })
        item.itemMeta = meta
        return item
    }

    private fun comp(text: String): Component =
        LegacyComponentSerializer.legacyAmpersand().deserialize(text)
    private fun c(text: String) = text.replace('&', '\u00A7')
}