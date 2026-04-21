package com.github.sahyuya.oyasaiMenu.engine

import com.github.sahyuya.oyasaiMenu.OyasaiMenu
import com.github.sahyuya.oyasaiMenu.manager.CooldownManager
import com.github.sahyuya.oyasaiMenu.model.*
import com.github.sahyuya.oyasaiMenu.util.CustomHead
import com.github.sahyuya.oyasaiMenu.util.GuiUtil
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.Registry
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryCloseEvent
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.ItemFlag
import org.bukkit.inventory.ItemStack

/**
 * PopupMenuEngine
 *
 * YAML 定義 (menus/popup/.yml) を読み込んでポップアップメニューを描画・操作する汎用エンジン。
 *
 * ■ アクション実行順序 (修正済み)
 *   即時実行 : URL / CHAT_PASTE のみ (メッセージ送信。GUI に影響しない)
 *
 *   CLOSE or GUI遷移アクションを含む場合:
 *     tick +1 : closeInventory()  ← まず確実に閉じる
 *     tick +2 : コマンド実行 → GUI を開く
 *                ↑ これにより「コマンドで開いた GUI が close で潰される」問題を解消
 *
 *   CLOSE も GUI遷移もない (純コマンド列) 場合:
 *     tick +1 : コマンド実行 (クリックイベント処理完了後に安全に実行)
 *
 * ■ コマンドクールダウン
 *   クリックスパム対策は isClickOnCooldown() で管理済み。
 *   1回のクリックで複数コマンドが実行されるのは正常動作のため、
 *   executeActions 内では isCommandOnCooldown() を適用しない。
 *   (旧実装では1コマンドしか実行されないバグがあった)
 */
class PopupMenuEngine(private val plugin: OyasaiMenu) : Listener {

    // UUID → 現在開いている popup ID
    private val activePlayers: MutableMap<String, String> = mutableMapOf()

    // ============================
    // 公開API
    // ============================

    /** 指定 popup ID のメニューを開く。既に同じ popup が開いていたら root に戻る (トグル)。 */
    fun open(player: Player, popupId: String) {
        val uid = player.uniqueId.toString()

        // ★ トグル: 同じポップアップを再クリックしたら総合メニューに戻る
        if (activePlayers[uid] == popupId) {
            Bukkit.getScheduler().runTaskLater(plugin, Runnable {
                plugin.menuEngine.openMenu(player, "root")
            }, 1L)
            return
        }

        val def = plugin.popupMenuLoader.getPopup(popupId) ?: run {
            plugin.logger.warning("Popup '$popupId' が見つかりません。menus/popup/$popupId.yml を確認してください。")
            player.sendMessage(c("&c内部エラー: popup '$popupId' が見つかりません。"))
            return
        }
        val inv = buildInventory(player, def)
        player.openInventory(inv)
        activePlayers[uid] = popupId   // openInventory() の後
    }

    // ============================
    // インベントリ構築
    // ============================

    private fun buildInventory(player: Player, def: PopupMenuDef): Inventory {
        val inv = Bukkit.createInventory(null, 54, comp(def.title))

        // アイテムを配置
        def.items.forEach { item ->
            if (item.slot in 0..44) inv.setItem(item.slot, buildItemStack(item))
        }

        // ガラス埋め・ナビバー描画
        NavBar.fillGlass(inv, def.glass)
        NavBar.apply(inv, player, plugin, def.navActive)
        return inv
    }

    private fun buildItemStack(item: PopupItem): ItemStack {
        val stack = when {
            item.icon == Material.PLAYER_HEAD && item.customTexture != null ->
                CustomHead.get(item.customTexture)
            else -> ItemStack(item.icon)
        }
        val meta = stack.itemMeta ?: return stack

        if (item.name.isNotEmpty()) meta.displayName(comp(item.name))
        if (item.lore.isNotEmpty()) meta.lore(item.lore.map { comp(it) })

        if (item.enchanted) {
            val unbreaking = runCatching {
                Registry.ENCHANTMENT.get(NamespacedKey.minecraft("unbreaking"))
            }.getOrNull()
            if (unbreaking != null) {
                meta.addEnchant(unbreaking, 1, true)
                meta.addItemFlags(ItemFlag.HIDE_ENCHANTS)
            }
        }

        stack.itemMeta = meta
        return stack
    }

    // ============================
    // イベントハンドラ
    // ============================

    @EventHandler
    fun onInventoryClick(event: InventoryClickEvent) {
        val player = event.whoClicked as? Player ?: return
        val uid    = player.uniqueId.toString()
        if (!activePlayers.containsKey(uid)) return
        if (event.clickedInventory == player.inventory) {
            if (event.isShiftClick) event.isCancelled = true
            return
        }
        event.isCancelled = true

        if (CooldownManager.isClickOnCooldown(player.uniqueId)) return

        val slot    = event.rawSlot
        val popupId = activePlayers[uid] ?: return

        when {
            // [45] プレイヤーヘッドクリック → ナビバー更新
            slot == 45 -> {
                val def = plugin.popupMenuLoader.getPopup(popupId) ?: return
                NavBar.apply(player.openInventory.topInventory, player, plugin, def.navActive)
                player.playSound(player.location, org.bukkit.Sound.UI_BUTTON_CLICK, 0.5f, 1f)
            }
            // [46〜53] ナビバークリック
            slot in 46..53 -> handleNavClick(player, slot)
            // [0〜44] コンテンツスロットクリック
            slot in 0..44 -> {
                val def  = plugin.popupMenuLoader.getPopup(popupId) ?: return
                val item = def.items.find { it.slot == slot } ?: return
                executeActions(player, item.actions)
            }
        }
    }

    @EventHandler
    fun onInventoryClose(event: InventoryCloseEvent) {
        activePlayers.remove((event.player as? Player)?.uniqueId?.toString() ?: return)
    }

    // ============================
    // ナビバークリック処理
    // ============================

    private fun handleNavClick(player: Player, slot: Int) {
        val entry = NavBar.entries.find { it.slot == slot } ?: return
        open(player, entry.popupId)
    }

    // ============================
    // アクション実行 (修正済み)
    // ============================

    /**
     * アクションリストを実行する。
     *
     * [修正内容]
     * 旧実装の問題:
     *   ① isCommandOnCooldown() をコマンドごとに呼び出し → 2コマンド目以降がクールダウン扱いで無視
     *   ② コマンドを先に実行してから close → コマンドで開いた GUI が close で潰される
     *
     * 新実装:
     *   - URL / CHAT_PASTE のみ即時実行 (メッセージ送信。GUI 干渉なし)
     *   - CLOSE or GUI遷移を含む場合: tick+1 で close → tick+2 でコマンド+GUI
     *   - 純コマンド列の場合: tick+1 でコマンド (クリックイベント処理後)
     */
    private fun executeActions(player: Player, actions: List<PopupAction>) {

        // === Phase 0: 即時実行 (メッセージ系のみ) ===
        for (action in actions) {
            when (action.type) {
                PopupActionType.URL ->
                    player.sendMessage(c("&e${action.value}\n&7クリックまたはコピーしてアクセスしてください。"))
                PopupActionType.CHAT_PASTE ->
                    player.sendMessage(c("&7チャット欄にコピーして使用してください: &f${action.value}"))
                else -> {}
            }
        }

        // === CLOSE または GUI遷移アクションが含まれるか判定 ===
        val needsCloseFirst = actions.any {
            it.type == PopupActionType.CLOSE         ||
            it.type == PopupActionType.OPEN_POPUP    ||
            it.type == PopupActionType.OPEN_SHOP     ||
            it.type == PopupActionType.OPEN_SELL     ||
            it.type == PopupActionType.OPEN_MACRO    ||
            it.type == PopupActionType.OPEN_POINT_SHOP ||
            it.type == PopupActionType.OPEN_MENU
        }

        if (needsCloseFirst) {
            // tick +1: インベントリを先に閉じる
            Bukkit.getScheduler().runTaskLater(plugin, Runnable {
                player.closeInventory()
                // tick +2: コマンド実行 + GUI 遷移
                Bukkit.getScheduler().runTaskLater(plugin, Runnable {
                    dispatchActionsDeferred(player, actions)
                }, 1L)
            }, 1L)
        } else {
            // CLOSE なし (純コマンド列): tick+1 で実行 (クリックイベント処理後に安全)
            Bukkit.getScheduler().runTaskLater(plugin, Runnable {
                dispatchActionsDeferred(player, actions)
            }, 1L)
        }
    }

    /**
     * コマンド実行・GUI遷移のサブメソッド。
     * close 後 (tick+2) または tick+1 に呼ばれる。
     * URL / CHAT_PASTE は executeActions() で処理済みのためスキップ。
     * コマンドクールダウンはここでは適用しない (click cooldown で十分)。
     */
    private fun dispatchActionsDeferred(player: Player, actions: List<PopupAction>) {
        for (action in actions) {
            when (action.type) {
                PopupActionType.PLAYER_CMD ->
                    player.performCommand(action.value.replace("%player%", player.name))

                PopupActionType.CONSOLE_CMD ->
                    Bukkit.dispatchCommand(
                        Bukkit.getConsoleSender(),
                        action.value.replace("%player%", player.name)
                    )

                PopupActionType.OPEN_POPUP ->
                    open(player, action.value)

                PopupActionType.OPEN_SHOP -> {
                    val cat = action.value
                    if (cat.isEmpty()) open(player, "shopindex")
                    else plugin.shopEngine.openShop(player, cat)
                }

                PopupActionType.OPEN_SELL ->
                    plugin.sellEngine.openSellMenu(player)

                PopupActionType.OPEN_MACRO ->
                    plugin.macroEngine.openMacroList(player)

                PopupActionType.OPEN_POINT_SHOP -> {
                    val catId = if (action.value.isEmpty() || action.value == "true")
                        plugin.pointShopLoader.getAllCategories().keys.firstOrNull() ?: return
                    else action.value
                    plugin.pointShopEngine.openShop(player, catId)
                }

                PopupActionType.OPEN_MENU ->
                    plugin.menuEngine.openMenu(player, action.value)

                // CLOSE は executeActions() の needsCloseFirst ブランチで処理済み
                // URL / CHAT_PASTE は executeActions() の即時実行フェーズで処理済み
                else -> {}
            }
        }
    }

    // ============================
    // ユーティリティ
    // ============================

    private fun comp(t: String): Component = LegacyComponentSerializer.legacyAmpersand().deserialize(t)
    private fun c(t: String) = t.replace('&', '\u00A7')
}