package com.github.sahyuya.oyasaiMenu.shop

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
import org.bukkit.event.inventory.InventoryDragEvent
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.ItemStack

/**
 * SellMenuEngine
 *
 * 一括売却GUIを管理するエンジン。
 * プレイヤーがアイテムをGUIに入れると、shops.yml に
 * 売却価格が登録されているアイテムだけが売却対象としてカウントされる。
 *
 * ■ インベントリレイアウト (54スロット)
 *
 *   行0〜3 (スロット 0〜35): 売却アイテム投入エリア (36スロット)
 *   行4    (スロット 36〜44): 売却結果プレビューエリア (表示専用)
 *   行5    (スロット 45〜53): 操作バー
 *
 *   操作バーの内訳:
 *   [45] 戻る  [47] 売却可能合計  [49] 売却実行  [51] 売却不可合計  [53] 全部クリア
 *
 * ■ 使い方
 *   1. /sell または /menu sell でGUIを開く
 *   2. 売却したいアイテムをスロット 0〜35 に入れる
 *   3. スロット 49 の「売却実行」を押す → 売却可能なアイテムのみ換金、
 *      売却不可のアイテムはインベントリに返却される
 *   4. 「戻る」で閉じると全アイテムがプレイヤーに返却される
 */
class SellMenuEngine(private val plugin: OyasaiMenu) : Listener {

    // 売却GUIを開いているプレイヤーのUUIDセット
    private val openPlayers: MutableSet<String> = mutableSetOf()

    // ============================
    // 公開API
    // ============================

    fun openSellMenu(player: Player) {
        if (!EconomyManager.isAvailable) {
            player.sendMessage(c("&cショップを使用するには Vault と経済プラグインが必要です。"))
            return
        }
        val inv = buildSellInventory()
        // openInventory() は同期的に InventoryCloseEvent を発火するため
        // openPlayers.add() は openInventory() の「後」に置く。
        player.openInventory(inv)
        openPlayers.add(player.uniqueId.toString())
        player.sendMessage(c("&a売却したいアイテムを上部エリアに入れて&e「売却実行」&aを押してください。"))
    }

    // ============================
    // インベントリ構築
    // ============================

    private fun buildSellInventory(): Inventory {
        val inv = Bukkit.createInventory(null, 54, comp("&6アイテム一括売却"))

        // 投入エリア (0〜35) は空のまま
        // プレビューエリア (36〜44) をライトグレーで埋める
        val preview = makeItem(Material.LIGHT_GRAY_STAINED_GLASS_PANE, "&7ここに売却結果が表示されます")
        for (i in 36..44) inv.setItem(i, preview)

        // 操作バー初期状態
        buildControlBar(inv, 0.0, 0, 0)
        return inv
    }

    /**
     * 操作バーを再描画する。
     * @param sellableTotal  売却可能合計金額
     * @param sellableCount  売却可能アイテム種類数
     * @param unsellableCount 売却不可アイテム種類数
     */
    private fun buildControlBar(
        inv: Inventory,
        sellableTotal: Double,
        sellableCount: Int,
        unsellableCount: Int
    ) {
        val glass = makeItem(Material.BLACK_STAINED_GLASS_PANE, " ")
        inv.setItem(46, glass)
        inv.setItem(48, glass)
        inv.setItem(50, glass)
        inv.setItem(52, glass)

        // [45] 戻る
        inv.setItem(45, makeItem(Material.ARROW, "&c← 戻る",
            listOf("&7閉じてアイテムを返却します")))

        // [47] 売却可能合計
        inv.setItem(47, makeItem(
            Material.EMERALD,
            "&a売却可能: &f${EconomyManager.format(sellableTotal)}",
            listOf("&7$sellableCount 種類のアイテムが売却対象です")
        ))

        // [49] 売却実行
        inv.setItem(49, makeItem(
            if (sellableCount > 0) Material.LIME_CONCRETE else Material.GRAY_CONCRETE,
            if (sellableCount > 0) "&a▶ 売却実行" else "&7▶ 売却実行 (対象なし)",
            listOf(
                if (sellableCount > 0)
                    "&7クリックで &f${EconomyManager.format(sellableTotal)} &7を受け取ります"
                else
                    "&7売却可能なアイテムを入れてください"
            )
        ))

        // [51] 売却不可合計
        inv.setItem(51, makeItem(
            if (unsellableCount > 0) Material.BARRIER else Material.LIGHT_GRAY_STAINED_GLASS_PANE,
            if (unsellableCount > 0) "&c売却不可: &f${unsellableCount}種" else "&7売却不可なし",
            if (unsellableCount > 0)
                listOf("&7これらはインベントリに返却されます")
            else emptyList()
        ))

        // [53] 全クリア
        inv.setItem(53, makeItem(Material.TNT, "&c全部クリア",
            listOf("&7投入エリアを空にしてアイテムを返却します")))
    }

    // ============================
    // イベントハンドラ
    // ============================

    @EventHandler
    fun onInventoryClick(event: InventoryClickEvent) {
        val player = event.whoClicked as? Player ?: return
        if (!openPlayers.contains(player.uniqueId.toString())) return

        val slot = event.rawSlot

        // プレイヤー自身のインベントリからの移動は通常通り許可
        // (操作バーへの誤配置だけキャンセルする)
        if (event.clickedInventory == player.inventory) {
            // Shift+クリックで投入エリアに移動しようとするとき:
            // 操作バー列 (45〜53) に入らないようにするため上部GUIに送る → デフォルト動作でOK
            return
        }

        // 操作バースロット (45〜53) はアイテム配置を禁止
        if (slot in 45..53) {
            event.isCancelled = true
            when (slot) {
                45 -> handleClose(player)
                49 -> handleSell(player)
                53 -> handleClear(player)
            }
            return
        }

        // プレビューエリア (36〜44) は編集禁止
        if (slot in 36..44) {
            event.isCancelled = true
            return
        }

        // 投入エリア (0〜35) への操作後に操作バーを更新
        Bukkit.getScheduler().runTaskLater(plugin, Runnable {
            refreshControlBar(player)
        }, 1L)
    }

    @EventHandler
    fun onInventoryDrag(event: InventoryDragEvent) {
        val player = event.whoClicked as? Player ?: return
        if (!openPlayers.contains(player.uniqueId.toString())) return
        // ドラッグがプレビューエリアや操作バーに掛かっていたらキャンセル
        val blocked = event.rawSlots.any { it in 36..53 }
        if (blocked) event.isCancelled = true
        else {
            Bukkit.getScheduler().runTaskLater(plugin, Runnable {
                refreshControlBar(player)
            }, 1L)
        }
    }

    @EventHandler
    fun onInventoryClose(event: InventoryCloseEvent) {
        val player = event.player as? Player ?: return
        if (!openPlayers.remove(player.uniqueId.toString())) return
        // クローズ時: 投入エリアのアイテムを全部返却
        returnItems(player, event.inventory)
    }

    // ============================
    // 操作処理
    // ============================

    private fun handleSell(player: Player) {
        val inv = player.openInventory.topInventory
        val items = getInputItems(inv)
        if (items.isEmpty()) {
            player.sendMessage(c("&c売却できるアイテムがありません。"))
            return
        }

        var totalEarned = 0.0
        var soldCount = 0
        val unsellable = mutableListOf<ItemStack>()

        items.forEach { (slot, stack) ->
            val price = plugin.shopConfigLoader.getSellPrice(stack.type)
            if (price != null && price > 0) {
                totalEarned += price * stack.amount
                soldCount += stack.amount
                inv.setItem(slot, null)  // 売却済みスロットをクリア
            } else {
                unsellable.add(stack)
            }
        }

        if (soldCount == 0) {
            player.sendMessage(c("&c売却可能なアイテムがありませんでした。"))
            return
        }

        EconomyManager.deposit(player, totalEarned)
        player.sendMessage(c(
            "&a一括売却完了!\n" +
                    "&7売却数: &f${soldCount}個  &7獲得: &f${EconomyManager.format(totalEarned)}\n" +
                    "&7残高: &f${EconomyManager.format(EconomyManager.getBalance(player))}"
        ))
        player.playSound(player.location, org.bukkit.Sound.ENTITY_PLAYER_LEVELUP, 1f, 1f)

        // 売却不可アイテムをインベントリに返却
        unsellable.forEach { stack ->
            val leftover = player.inventory.addItem(stack)
            leftover.values.forEach { player.world.dropItemNaturally(player.location, it) }
        }

        // 操作バーを更新
        refreshControlBar(player)
    }

    private fun handleClose(player: Player) {
        // onInventoryClose が返却を担うので閉じるだけ
        player.closeInventory()
    }

    private fun handleClear(player: Player) {
        val inv = player.openInventory.topInventory
        getInputItems(inv).forEach { (slot, stack) ->
            inv.setItem(slot, null)
            val leftover = player.inventory.addItem(stack)
            leftover.values.forEach { player.world.dropItemNaturally(player.location, it) }
        }
        refreshControlBar(player)
        player.sendMessage(c("&e投入エリアをクリアしました。"))
    }

    // ============================
    // ユーティリティ
    // ============================

    private fun getInputItems(inv: Inventory): Map<Int, ItemStack> {
        val result = mutableMapOf<Int, ItemStack>()
        for (i in 0..35) {
            val item = inv.getItem(i)
            if (item != null && item.type != Material.AIR) result[i] = item
        }
        return result
    }

    /** 投入エリアの全アイテムをプレイヤーに返す (クローズ時) */
    private fun returnItems(player: Player, inv: Inventory) {
        for (i in 0..35) {
            val item = inv.getItem(i) ?: continue
            if (item.type == Material.AIR) continue
            val leftover = player.inventory.addItem(item)
            leftover.values.forEach { player.world.dropItemNaturally(player.location, it) }
        }
    }

    /** 現在の投入エリアの内容から操作バーを再計算して更新する */
    private fun refreshControlBar(player: Player) {
        val inv = player.openInventory.topInventory
        var sellableTotal = 0.0
        var sellableTypes = 0
        var unsellableTypes = 0

        getInputItems(inv).values.forEach { stack ->
            val price = plugin.shopConfigLoader.getSellPrice(stack.type)
            if (price != null && price > 0) {
                sellableTotal += price * stack.amount
                sellableTypes++
            } else {
                unsellableTypes++
            }
        }

        // プレビューエリアを更新
        updatePreviewArea(inv)
        buildControlBar(inv, sellableTotal, sellableTypes, unsellableTypes)
    }

    /** プレビューエリア (36〜44) に売却可能/不可のサマリーを表示 */
    private fun updatePreviewArea(inv: Inventory) {
        val defaultGlass = makeItem(Material.LIGHT_GRAY_STAINED_GLASS_PANE, "&7ここに売却結果が表示されます")
        for (i in 36..44) inv.setItem(i, defaultGlass)

        var slot = 36
        val seen = mutableSetOf<Material>()
        for (i in 0..35) {
            if (slot > 44) break
            val item = inv.getItem(i) ?: continue
            if (item.type == Material.AIR || seen.contains(item.type)) continue
            seen.add(item.type)

            val price = plugin.shopConfigLoader.getSellPrice(item.type)
            val preview = ItemStack(item.type)
            val meta = preview.itemMeta ?: continue
            meta.displayName(comp(
                if (price != null && price > 0) "&a${item.type.name.lowercase()}" else "&c${item.type.name.lowercase()} (売却不可)"
            ))
            meta.lore(listOf(
                if (price != null && price > 0)
                    comp("&71個: &f${EconomyManager.format(price)}")
                else
                    comp("&7登録なし")
            ))
            preview.itemMeta = meta
            inv.setItem(slot++, preview)
        }
    }

    private fun makeItem(material: Material, name: String, lore: List<String> = emptyList()): ItemStack {
        val item = ItemStack(material)
        val meta = item.itemMeta ?: return item
        meta.displayName(comp(name))
        if (lore.isNotEmpty()) meta.lore(lore.map { comp(it) })
        item.itemMeta = meta
        return item
    }

    private fun comp(text: String): Component =
        LegacyComponentSerializer.legacyAmpersand().deserialize(text)

    private fun c(text: String): String = text.replace('&', '\u00A7')
}