package com.github.sahyuya.oyasaiMenu.shop

import com.github.sahyuya.oyasaiMenu.OyasaiMenu
import com.github.sahyuya.oyasaiMenu.model.PlayerShopState
import com.github.sahyuya.oyasaiMenu.model.ShopCategory
import com.github.sahyuya.oyasaiMenu.model.ShopItem
import com.github.sahyuya.oyasaiMenu.model.ShopQuantity
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
 * ShopEngine
 *
 * ショップGUIの生成・クリック処理・ページング を担う。
 *
 * ■ インベントリレイアウト (54スロット = 6行)
 *   行0〜4 (スロット 0〜44) : 商品アイテム (5行×9列 = 最大45品)
 *   行5    (スロット45〜53) : 操作バー (固定)
 *
 *   操作バーの内訳:
 *   [45] 戻る(ARROW)  [46] ガラス  [47] 前ページ(ARROW)  [48] ガラス
 *   [49] ページ表示(BOOK)  [50] ガラス  [51] 次ページ(ARROW)  [52] ガラス
 *   [53] 数量切替(GOLD_NUGGET: 1→4→16→64)
 *
 * ■ クリック操作
 *   左クリック  → 購入 (現在の数量分)
 *   右クリック  → 売却 (現在の数量分)
 *   Shift+左    → 最大スタック購入
 *   Shift+右    → インベントリ内の同マテリアルを全て売却
 */
class ShopEngine(private val plugin: OyasaiMenu) : Listener {

    // プレイヤーUUID → ショップ状態 (ページ・数量をセッション間で保持)
    private val playerStates: MutableMap<String, PlayerShopState> = mutableMapOf()

    // 今まさにショップGUIを開いているプレイヤーのUUIDセット。
    // playerStates と分離することで、クローズ後もページ・数量の記憶を保ちつつ
    // MenuEngine などの別GUIへのクリックを横取りしないようにする。
    private val activeShopPlayers: MutableSet<String> = mutableSetOf()

    // ============================
    // 公開API
    // ============================

    /**
     * プレイヤーにショップを開かせる。
     * @param categoryId  ショップカテゴリID (例: "blocks", "ores")
     * @param page        表示ページ (0-indexed)
     */
    fun openShop(player: Player, categoryId: String, page: Int = 0) {
        if (!EconomyManager.isAvailable) {
            player.sendMessage(c("&cショップを使用するには Vault と経済プラグインが必要です。"))
            return
        }
        val category = plugin.shopConfigLoader.getCategory(categoryId)
        if (category == null) {
            player.sendMessage(c("&cショップカテゴリが見つかりません: $categoryId"))
            return
        }
        val clampedPage = page.coerceIn(0, category.pageCount - 1)
        val state = playerStates.getOrPut(player.uniqueId.toString()) {
            PlayerShopState(categoryId, clampedPage)
        }.copy(categoryId = categoryId, page = clampedPage)
        playerStates[player.uniqueId.toString()] = state
        // openInventory() が同期的に InventoryCloseEvent を発火し
        // onInventoryClose → activeShopPlayers.remove() が呼ばれるため、
        // add() は openInventory() の「後」に置く。
        player.openInventory(buildShopInventory(player, category, state))
        activeShopPlayers.add(player.uniqueId.toString())
    }

    /** ショップGUI外から数量を変更する (テスト・コマンド向け) */
    fun getPlayerState(player: Player): PlayerShopState? =
        playerStates[player.uniqueId.toString()]

    // ============================
    // インベントリ構築
    // ============================

    private fun buildShopInventory(
        player: Player,
        category: ShopCategory,
        state: PlayerShopState
    ): Inventory {
        val title = "${cc(category.displayName)} &8| ${state.page + 1}/${category.pageCount}ページ"
        val inv = Bukkit.createInventory(null, 54, comp(title))

        // 商品を配置 (スロット 0〜44)
        val pageItems = category.getPage(state.page)
        pageItems.forEachIndexed { i, shopItem ->
            if (shopItem.material != null) {
                inv.setItem(i, buildShopItemStack(shopItem, state.quantity.amount))
            }
        }

        // 操作バーを配置 (スロット 45〜53)
        buildBottomBar(inv, category, state)
        return inv
    }

    /**
     * 商品スロットに表示する ItemStack を作成する。
     * Lore に購入・売却価格と操作ガイドを表示する。
     */
    private fun buildShopItemStack(shopItem: ShopItem, quantity: Int): ItemStack {
        val item = ItemStack(shopItem.material!!, quantity.coerceIn(1, 64))
        val meta = item.itemMeta ?: return item

        meta.displayName(comp("&f${formatMaterialName(shopItem.materialId)}"))

        val lore = mutableListOf<Component>()
        if (shopItem.canBuy) {
            val total = EconomyManager.format(shopItem.buyPrice * quantity)
            lore.add(comp("&a購入: &f${EconomyManager.format(shopItem.buyPrice)} &7(×$quantity = &f$total&7)"))
        } else {
            lore.add(comp("&7購入: &c不可"))
        }
        if (shopItem.canSell) {
            val total = EconomyManager.format(shopItem.sellPrice * quantity)
            lore.add(comp("&b売却: &f${EconomyManager.format(shopItem.sellPrice)} &7(×$quantity = &f$total&7)"))
        } else {
            lore.add(comp("&7売却: &c不可"))
        }
        lore.add(comp(""))
        lore.add(comp("&e左クリック &7→ &a購入"))
        lore.add(comp("&e右クリック &7→ &b売却"))
        lore.add(comp("&eShift+左  &7→ &a最大スタック購入"))
        lore.add(comp("&eShift+右  &7→ &b全て売却"))

        meta.lore(lore)
        item.itemMeta = meta
        return item
    }

    /**
     * 下段操作バーを組み立てる。
     *
     * スロット配置:
     *   45: 戻る    46: ガラス   47: 前ページ  48: ガラス
     *   49: ページ  50: ガラス   51: 次ページ  52: ガラス  53: 数量
     */
    private fun buildBottomBar(inv: Inventory, category: ShopCategory, state: PlayerShopState) {
        val glass = makeItem(Material.BLACK_STAINED_GLASS_PANE, " ")
        inv.setItem(46, glass)
        inv.setItem(48, glass)
        inv.setItem(50, glass)
        inv.setItem(52, glass)

        // [45] 戻る
        inv.setItem(45, makeItem(Material.ARROW, "&c← 戻る", listOf("&7ショップ一覧に戻ります")))

        // [47] 前ページ
        val hasPrev = state.page > 0
        inv.setItem(47, makeItem(
            if (hasPrev) Material.ARROW else Material.GRAY_STAINED_GLASS_PANE,
            if (hasPrev) "&e← 前のページ" else "&8前のページ (なし)",
            if (hasPrev) listOf("&7${state.page}/${category.pageCount} ページ目に戻る") else emptyList()
        ))

        // [49] ページ数表示
        inv.setItem(49, makeItem(
            Material.BOOK,
            "&fページ &e${state.page + 1} &7/ &e${category.pageCount}",
            listOf(
                "&7全 ${category.items.size} 種類",
                "&7このページ: ${category.getPage(state.page).size} 種類"
            )
        ))

        // [51] 次ページ
        val hasNext = state.page < category.pageCount - 1
        inv.setItem(51, makeItem(
            if (hasNext) Material.ARROW else Material.GRAY_STAINED_GLASS_PANE,
            if (hasNext) "&e次のページ →" else "&8次のページ (なし)",
            if (hasNext) listOf("&7${state.page + 2}/${category.pageCount} ページ目へ") else emptyList()
        ))

        // [53] 数量切替
        val qty = state.quantity
        inv.setItem(53, makeItem(
            Material.GOLD_NUGGET,
            "${qty.label} &7でやりとり",
            listOf(
                "&7クリックで数量を切替:",
                "${mark(qty, ShopQuantity.ONE)}    1個",
                "${mark(qty, ShopQuantity.FOUR)}    4個",
                "${mark(qty, ShopQuantity.SIXTEEN)}  16個",
                "${mark(qty, ShopQuantity.SIXTY_FOUR)} 64個"
            )
        ))
    }

    private fun mark(current: ShopQuantity, target: ShopQuantity) =
        if (current == target) "&a▶" else "&7  "

    // ============================
    // イベントハンドラ
    // ============================

    @EventHandler
    fun onInventoryClick(event: InventoryClickEvent) {
        val player = event.whoClicked as? Player ?: return
        // activeShopPlayers にいないプレイヤーのクリックは無視。
        // ここを playerStates で判定すると、一度ショップを開いた後に
        // 総合メニューなどを開いても ShopEngine がクリックを横取りし続けるバグが起きる。
        if (!activeShopPlayers.contains(player.uniqueId.toString())) return
        val state = playerStates[player.uniqueId.toString()] ?: return
        if (event.clickedInventory == player.inventory) return
        event.isCancelled = true

        val slot = event.rawSlot
        val category = plugin.shopConfigLoader.getCategory(state.categoryId) ?: return

        when (slot) {
            // ========== 操作バー ==========
            45 -> handleBack(player)
            47 -> changePage(player, state, category, state.page - 1)
            49 -> { /* ページ表示: 何もしない */ }
            51 -> changePage(player, state, category, state.page + 1)
            53 -> cycleQuantity(player, state, category)

            // ========== 商品スロット (0〜44) ==========
            in 0..44 -> {
                val pageItems = category.getPage(state.page)
                val shopItem = pageItems.getOrNull(slot) ?: return
                if (shopItem.material == null) return

                when {
                    event.isShiftClick && event.isLeftClick  -> handleBuy(player, shopItem, 64, state)
                    event.isShiftClick && event.isRightClick -> handleSellAll(player, shopItem)
                    event.isLeftClick  -> handleBuy(player, shopItem, state.quantity.amount, state)
                    event.isRightClick -> handleSell(player, shopItem, state.quantity.amount, state)
                }
                // 購入・売却後に表示を更新
                refreshInventory(player, category, state)
            }
        }
    }

    @EventHandler
    fun onInventoryClose(event: InventoryCloseEvent) {
        val player = event.player as? Player ?: return
        // activeShopPlayers からは削除して「今開いている」フラグを落とす。
        // playerStates は残しておくことで、次回オープン時に前回のページ・数量を引き継げる。
        activeShopPlayers.remove(player.uniqueId.toString())
    }

    // ============================
    // 購入・売却処理
    // ============================

    private fun handleBuy(player: Player, item: ShopItem, quantity: Int, state: PlayerShopState) {
        if (!item.canBuy) {
            player.sendMessage(c("&cこのアイテムは購入できません。"))
            return
        }
        val total = item.buyPrice * quantity
        val err = EconomyManager.withdraw(player, total)
        if (err != null) { player.sendMessage(c(err)); return }

        // インベントリに追加 (溢れた分は足元にドロップ)
        val give = ItemStack(item.material!!, quantity)
        val leftover = player.inventory.addItem(give)
        leftover.values.forEach { player.world.dropItemNaturally(player.location, it) }

        player.sendMessage(c(
            "&a購入しました: &f${formatMaterialName(item.materialId)} ×$quantity\n" +
                    "&7残高: &f${EconomyManager.format(EconomyManager.getBalance(player))}"
        ))
        player.playSound(player.location, org.bukkit.Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1f, 1.2f)
    }

    private fun handleSell(player: Player, item: ShopItem, quantity: Int, state: PlayerShopState) {
        if (!item.canSell) {
            player.sendMessage(c("&cこのアイテムは売却できません。"))
            return
        }
        // インベントリから quantity 個取り出す
        val removed = removeFromInventory(player, item.material!!, quantity)
        if (removed == 0) {
            player.sendMessage(c("&c${formatMaterialName(item.materialId)} を持っていません。"))
            return
        }
        val total = item.sellPrice * removed
        EconomyManager.deposit(player, total)
        player.sendMessage(c(
            "&b売却しました: &f${formatMaterialName(item.materialId)} ×$removed\n" +
                    "&7+${EconomyManager.format(total)} → 残高: &f${EconomyManager.format(EconomyManager.getBalance(player))}"
        ))
        player.playSound(player.location, org.bukkit.Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1f, 0.8f)
    }

    /** Shift+右クリック: 同マテリアルをインベントリ内から全て売却 */
    private fun handleSellAll(player: Player, item: ShopItem) {
        if (!item.canSell) {
            player.sendMessage(c("&cこのアイテムは売却できません。"))
            return
        }
        val total = countInInventory(player, item.material!!)
        if (total == 0) {
            player.sendMessage(c("&c${formatMaterialName(item.materialId)} を持っていません。"))
            return
        }
        removeFromInventory(player, item.material, total)
        val earned = item.sellPrice * total
        EconomyManager.deposit(player, earned)
        player.sendMessage(c(
            "&b全売却: &f${formatMaterialName(item.materialId)} ×$total\n" +
                    "&7+${EconomyManager.format(earned)} → 残高: &f${EconomyManager.format(EconomyManager.getBalance(player))}"
        ))
        player.playSound(player.location, org.bukkit.Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1f, 0.8f)
    }

    // ============================
    // ページング・数量切替
    // ============================

    private fun changePage(player: Player, state: PlayerShopState, category: ShopCategory, newPage: Int) {
        if (newPage < 0 || newPage >= category.pageCount) return
        val newState = state.copy(page = newPage)
        // openInventory() → InventoryCloseEvent → activeShopPlayers.remove() の順で同期実行される。
        // state と activeShopPlayers の登録は openInventory() の後に行う。
        player.openInventory(buildShopInventory(player, category, newState))
        playerStates[player.uniqueId.toString()] = newState
        activeShopPlayers.add(player.uniqueId.toString())
    }

    private fun cycleQuantity(player: Player, state: PlayerShopState, category: ShopCategory) {
        val newState = state.copy(quantity = state.quantity.next())
        playerStates[player.uniqueId.toString()] = newState
        refreshInventory(player, category, newState)
    }

    private fun refreshInventory(player: Player, category: ShopCategory, state: PlayerShopState) {
        val inv = player.openInventory.topInventory
        // 商品スロットと操作バーを再描画
        for (i in 0..44) inv.setItem(i, null)
        val pageItems = category.getPage(state.page)
        pageItems.forEachIndexed { i, shopItem ->
            if (shopItem.material != null) inv.setItem(i, buildShopItemStack(shopItem, state.quantity.amount))
        }
        buildBottomBar(inv, category, state)
    }

    private fun handleBack(player: Player) {
        player.closeInventory()
        Bukkit.getScheduler().runTaskLater(plugin, Runnable {
            plugin.menuEngine.openMenu(player, "shop/index")
        }, 1L)
    }

    // ============================
    // インベントリ操作ユーティリティ
    // ============================

    private fun countInInventory(player: Player, material: Material): Int =
        player.inventory.contents
            .filterNotNull()
            .filter { it.type == material }
            .sumOf { it.amount }

    private fun removeFromInventory(player: Player, material: Material, quantity: Int): Int {
        var remaining = quantity
        player.inventory.contents.forEachIndexed { i, item ->
            if (remaining <= 0) return@forEachIndexed
            if (item == null || item.type != material) return@forEachIndexed
            if (item.amount <= remaining) {
                remaining -= item.amount
                player.inventory.setItem(i, null)
            } else {
                item.amount -= remaining
                remaining = 0
            }
        }
        return quantity - remaining
    }

    // ============================
    // GUI構築ユーティリティ
    // ============================

    private fun makeItem(material: Material, name: String, lore: List<String> = emptyList()): ItemStack {
        val item = ItemStack(material)
        val meta = item.itemMeta ?: return item
        meta.displayName(comp(name))
        if (lore.isNotEmpty()) meta.lore(lore.map { comp(it) })
        item.itemMeta = meta
        return item
    }

    /** "oak_log" → "Oak Log" の読みやすい表示名に変換する */
    private fun formatMaterialName(id: String): String =
        id.split("_").joinToString(" ") { w ->
            w.replaceFirstChar { it.uppercase() }
        }

    private fun comp(text: String): Component =
        LegacyComponentSerializer.legacyAmpersand().deserialize(text)

    private fun cc(text: String): String = text.replace('&', '\u00A7')
    private fun c(text: String): String = cc(text)
}