package com.github.sahyuya.oyasaiMenu.engine

import com.github.sahyuya.oyasaiMenu.OyasaiMenu
import com.github.sahyuya.oyasaiMenu.manager.TokenCurrencyManager
import com.github.sahyuya.oyasaiMenu.model.PlayerPointShopState
import com.github.sahyuya.oyasaiMenu.model.PointShopCategory
import com.github.sahyuya.oyasaiMenu.model.PointShopItem
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
 * PointShopEngine
 *
 * TokenManager のポイントを使った購入専用ショップGUI。
 *
 * ■ インベントリレイアウト (54スロット)
 *   行0〜4 (0〜44): 商品 (ページング対応)
 *   行5    (45〜53): 操作バー
 *     [45]戻る [46]ガラス [47]前ページ [48]ガラス
 *     [49]ページ数 [50]ガラス [51]次ページ [52]ガラス [53]ポイント残高表示
 *
 * ■ クリック: 左クリックのみで購入。売却なし・数量固定(1個)。
 *
 * ■ %player% / %tokens% / %price% プレースホルダは
 *   GUIレンダリング時と購入コマンド実行時に展開する。
 */
class PointShopEngine(private val plugin: OyasaiMenu) : Listener {

    private val playerStates: MutableMap<String, PlayerPointShopState> = mutableMapOf()
    private val activePlayers: MutableSet<String> = mutableSetOf()

    // ============================
    // 公開API
    // ============================

    fun openShop(player: Player, categoryId: String, page: Int = 0) {
        if (!TokenCurrencyManager.isAvailable) {
            player.sendMessage(c("&cポイントショップには TokenManager が必要です。")); return
        }
        val category = plugin.pointShopLoader.getCategory(categoryId) ?: run {
            player.sendMessage(c("&cカテゴリが見つかりません: $categoryId")); return
        }
        val clampedPage = page.coerceIn(0, category.pageCount - 1)
        val state = PlayerPointShopState(categoryId = categoryId, page = clampedPage)
        // openInventory() は同期的に InventoryCloseEvent を発火するため、
        // playerStates/activePlayers の登録は openInventory() の「後」に行う。
        // 前に置くと onInventoryClose が playerStates.remove() してしまい
        // クリックが機能しなくなる。
        player.openInventory(buildInventory(player, category, state))
        playerStates[player.uniqueId.toString()] = state
        activePlayers.add(player.uniqueId.toString())
    }

    // ============================
    // インベントリ構築
    // ============================

    private fun buildInventory(player: Player, category: PointShopCategory, state: PlayerPointShopState): Inventory {
        val tokens = TokenCurrencyManager.getTokens(player)
        val inv = Bukkit.createInventory(
            null, 54,
            comp("${cc(category.displayName)} &8| &f${state.page + 1}&8/&f${category.pageCount}")
        )

        category.getPage(state.page).forEachIndexed { i, item ->
            inv.setItem(i, buildItemStack(player, item, tokens))
        }
        buildBottomBar(inv, category, state, tokens)
        return inv
    }

    private fun buildItemStack(player: Player, item: PointShopItem, tokens: Long): ItemStack {
        val stack = ItemStack(item.icon)
        val meta  = stack.itemMeta ?: return stack

        // 表示名: %player% を展開
        if (item.name.isNotEmpty()) {
            meta.displayName(comp(item.name.replace("%player%", player.name)))
        }

        // Lore: %tokens%, %price% を展開
        val lore = item.lore.map { line ->
            comp(line
                .replace("%player%", player.name)
                .replace("%tokens%", TokenCurrencyManager.format(tokens))
                .replace("%price%",  TokenCurrencyManager.format(item.cost))
            )
        }.toMutableList()

        // 購入可否を末尾に追加
        lore += comp("")
        if (tokens >= item.cost) {
            lore += comp("&e左クリック &7→ &a購入 (&f${TokenCurrencyManager.format(item.cost)}P&7)")
        } else {
            lore += comp("&cポイントが不足しています")
            lore += comp("&7必要: &c${TokenCurrencyManager.format(item.cost)}P &7/ 所持: &f${TokenCurrencyManager.format(tokens)}P")
        }

        meta.lore(lore)
        stack.itemMeta = meta
        return stack
    }

    private fun buildBottomBar(inv: Inventory, category: PointShopCategory, state: PlayerPointShopState, tokens: Long) {
        val glass = makeItem(Material.BLACK_STAINED_GLASS_PANE, " ")
        listOf(46, 48, 50, 52).forEach { inv.setItem(it, glass) }

        inv.setItem(45, makeItem(Material.ARROW, "&c← 戻る", listOf("&7ショップ一覧に戻ります")))

        val hasPrev = state.page > 0
        inv.setItem(47, makeItem(
            if (hasPrev) Material.ARROW else Material.GRAY_STAINED_GLASS_PANE,
            if (hasPrev) "&e← 前のページ" else "&8前のページなし"
        ))

        inv.setItem(49, makeItem(Material.BOOK,
            "&fページ &e${state.page + 1} &7/ &e${category.pageCount}",
            listOf("&7全 ${category.itemList.size} 種")
        ))

        val hasNext = state.page < category.pageCount - 1
        inv.setItem(51, makeItem(
            if (hasNext) Material.ARROW else Material.GRAY_STAINED_GLASS_PANE,
            if (hasNext) "&e次のページ →" else "&8次のページなし"
        ))

        // [53] ポイント残高表示
        inv.setItem(53, makeItem(
            Material.NETHER_STAR,
            "&6所持ポイント: &f${TokenCurrencyManager.format(tokens)}P",
            listOf("&7TokenManager ポイント残高")
        ))
    }

    // ============================
    // イベントハンドラ
    // ============================

    @EventHandler
    fun onInventoryClick(event: InventoryClickEvent) {
        val player = event.whoClicked as? Player ?: return
        if (!activePlayers.contains(player.uniqueId.toString())) return
        val state = playerStates[player.uniqueId.toString()] ?: return
        if (event.clickedInventory == player.inventory) {
            if (event.isShiftClick) event.isCancelled = true
            return
        }
        event.isCancelled = true

        val slot = event.rawSlot
        val category = plugin.pointShopLoader.getCategory(state.categoryId) ?: return

        when (slot) {
            45 -> handleBack(player)
            47 -> changePage(player, state, category, state.page - 1)
            49 -> { /* ページ表示 */ }
            51 -> changePage(player, state, category, state.page + 1)
            53 -> { /* ポイント表示: 何もしない */ }
            in 0..44 -> {
                if (!event.isLeftClick) return
                val item = category.getPage(state.page).getOrNull(slot) ?: return
                handlePurchase(player, item, state, category)
            }
        }
    }

    @EventHandler
    fun onInventoryClose(event: InventoryCloseEvent) {
        val player = event.player as? Player ?: return
        activePlayers.remove(player.uniqueId.toString())
        // ページはリセット (ショップ閉じたら1ページ目から再開)
        playerStates.remove(player.uniqueId.toString())
    }

    // ============================
    // 購入処理
    // ============================

    private fun handlePurchase(player: Player, item: PointShopItem, state: PlayerPointShopState, category: PointShopCategory) {
        val err = TokenCurrencyManager.removeTokens(player, item.cost)
        if (err != null) { player.sendMessage(c(err)); return }

        // コマンド実行 (%player%, %price% を展開)
        item.commands.forEach { cmd ->
            val expanded = cmd
                .replace("%player%", player.name)
                .replace("%price%",  item.cost.toString())
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), expanded)
        }

        // 購入メッセージ
        val msg = item.message
            .replace("%player%", player.name)
            .replace("%price%",  item.cost.toString())
            .replace("%tokens%", TokenCurrencyManager.format(TokenCurrencyManager.getTokens(player)))
        player.sendMessage(c(msg))
        player.playSound(player.location, org.bukkit.Sound.ENTITY_PLAYER_LEVELUP, 1f, 1.2f)

        // ★ 購入後はGUIを閉じる。
        //   GUI を開いたままコマンドでアイテムを渡すと、チェストが開く演出などで
        //   プレイヤーのインベントリ操作イベントが誤動作する場合があるため。
        player.closeInventory()
    }

    // ============================
    // ページング
    // ============================

    private fun changePage(player: Player, state: PlayerPointShopState, category: PointShopCategory, newPage: Int) {
        if (newPage !in 0 until category.pageCount) return
        val newState = state.copy(page = newPage)
        player.openInventory(buildInventory(player, category, newState))
        playerStates[player.uniqueId.toString()] = newState   // openInventory() の後
        activePlayers.add(player.uniqueId.toString())
    }

    private fun handleBack(player: Player) {
        player.closeInventory()
        Bukkit.getScheduler().runTaskLater(plugin, Runnable {
            plugin.menuEngine.openMenu(player, "shop/index")
        }, 1L)
    }

    // ============================
    // ユーティリティ
    // ============================

    private fun makeItem(mat: Material, name: String, lore: List<String> = emptyList()): ItemStack {
        val item = ItemStack(mat); val meta = item.itemMeta ?: return item
        meta.displayName(comp(name))
        if (lore.isNotEmpty()) meta.lore(lore.map { comp(it) })
        item.itemMeta = meta; return item
    }

    private fun comp(text: String): Component = LegacyComponentSerializer.legacyAmpersand().deserialize(text)
    private fun cc(text: String) = text.replace('&', '\u00A7')
    private fun c(text: String)  = cc(text)
}