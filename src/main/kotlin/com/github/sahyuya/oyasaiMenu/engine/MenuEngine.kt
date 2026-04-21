package com.github.sahyuya.oyasaiMenu.engine

import com.github.sahyuya.oyasaiMenu.OyasaiMenu
import com.github.sahyuya.oyasaiMenu.manager.CooldownManager
import com.github.sahyuya.oyasaiMenu.model.MenuDefinition
import com.github.sahyuya.oyasaiMenu.model.MenuItemDefinition
import com.github.sahyuya.oyasaiMenu.model.PlayerMenuState
import me.clip.placeholderapi.PlaceholderAPI          // ← pom.xml に provided 依存を追加したことで解決
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryCloseEvent
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.ItemStack

/**
 * MenuEngine
 *
 * 設計書 "1. Menu Engine（コア）" の実装。
 * - MenuDefinition から Bukkit の Inventory を生成する
 * - クリックイベントを受け取り ActionEngine へ委譲する
 * - プレイヤーごとに「今どのメニューを開いているか」を管理する
 *
 * Listener として登録されるため、InventoryClick / InventoryClose
 * のイベントハンドラもこのクラスが持つ。
 *
 * ★ PlaceholderAPI の "Unresolved reference 'me'" について
 *   原因: pom.xml に me.clip:placeholderapi の依存が宣言されていなかったため
 *         コンパイラがクラスを見つけられなかった。
 *   修正: pom.xml の <repositories> に extendedclip のリポジトリを追加し、
 *         <dependencies> に provided スコープで宣言することで解決した。
 *         実行時は isPluginEnabled("PlaceholderAPI") で存在確認してから
 *         呼び出すため、PlaceholderAPI が入っていない環境でも安全に動作する。
 */
class MenuEngine(private val plugin: OyasaiMenu) : Listener {

    // プレイヤーUUID → 現在開いているメニューの状態
    private val playerStates: MutableMap<String, PlayerMenuState> = mutableMapOf()

    // 静的コンテンツ (プレースホルダーを含まない) のキャッシュ
    private val staticCache: MutableMap<String, Inventory> = mutableMapOf()

    // ============================
    // 公開API
    // ============================

    /**
     * プレイヤーに指定IDのメニューを開かせる。
     * メインスレッドから呼び出すこと。
     */
    fun openMenu(player: Player, menuId: String, page: Int = 0) {
        val menuDef = plugin.menuLoader.getMenu(menuId)
        if (menuDef == null) {
            player.sendMessage(colorize("&cメニューが見つかりません: $menuId"))
            plugin.logger.warning("存在しないメニューID: $menuId (player=${player.name})")
            return
        }
        val inventory = buildInventory(player, menuDef, page)
        // openInventory() は同期的に InventoryCloseEvent を発火させるため、
        // state のセットは openInventory() の「後」に行う必要がある。
        // 前に置くと CloseEvent ハンドラが playerStates.remove() してしまい
        // クリックが一切反応しなくなる。
        player.openInventory(inventory)
        playerStates[player.uniqueId.toString()] = PlayerMenuState(
            menuId = menuId, page = page, isEditing = false
        )
    }

    /**
     * 編集モードでメニューを開く。
     * 通常表示との違いはインベントリ下段9スロットにツールバーが挿入される点。
     */
    fun openMenuInEditMode(player: Player, menuId: String) {
        val menuDef = plugin.menuLoader.getMenu(menuId) ?: run {
            player.sendMessage(colorize("&cメニューが見つかりません: $menuId"))
            return
        }
        // 編集GUIは常に54スロット (下段ツールバー確保のため)
        val inv = Bukkit.createInventory(null, 54, colorizeComponent("&c[編集] ${menuDef.title}"))
        menuDef.items.values.forEach { itemDef ->
            if (itemDef.slot < 45) inv.setItem(itemDef.slot, buildItemStack(player, itemDef))
        }
        setupEditToolbar(inv)
        player.openInventory(inv)
        playerStates[player.uniqueId.toString()] = PlayerMenuState(
            menuId = menuId, page = 0, isEditing = true
        )
    }

    // ============================
    // イベントハンドラ
    // ============================

    @EventHandler
    fun onInventoryClick(event: InventoryClickEvent) {
        val player = event.whoClicked as? Player ?: return
        val state = playerStates[player.uniqueId.toString()] ?: return
        // プレイヤー自身のインベントリへのクリックは無視。
        // ただし Shift+クリックによるメニューGUIへのアイテム移動はキャンセルする。
        if (event.clickedInventory == player.inventory) {
            if (event.isShiftClick) event.isCancelled = true
            return
        }
        event.isCancelled = true
        if (CooldownManager.isClickOnCooldown(player.uniqueId)) return

        val slot = event.rawSlot
        if (state.isEditing) {
            handleEditClick(player, state, slot, event)
            return
        }

        // root メニューのナビバー (45〜53) クリックを PopupMenuEngine に委譲
        if (state.menuId == "root" && slot in 45..53) {
            when (slot) {
                45 -> {
                    // プレイヤーヘッド: 情報更新
                    NavBar.apply(player.openInventory.topInventory, player, plugin, -1)
                    player.playSound(player.location, org.bukkit.Sound.UI_BUTTON_CLICK, 0.5f, 1f)
                }
                else -> {
                    val entry = NavBar.entries.find { it.slot == slot }
                    if (entry != null) {
                        Bukkit.getScheduler().runTaskLater(plugin, Runnable {
                            plugin.popupMenuEngine.open(player, entry.popupId)
                        }, 1L)
                    }
                }
            }
            return
        }

        // 通常モード
        val menuDef = plugin.menuLoader.getMenu(state.menuId) ?: return
        val itemDef = menuDef.items.values.find { it.slot == slot } ?: return
        plugin.actionEngine.executeActions(player, itemDef.actions, state)
    }

    @EventHandler
    fun onInventoryClose(event: InventoryCloseEvent) {
        // メニューを閉じたらプレイヤーの状態をクリア
        val player = event.player as? Player ?: return
        playerStates.remove(player.uniqueId.toString())
    }

    // ============================
    // インベントリ構築
    // ============================

    private fun buildInventory(player: Player, menuDef: MenuDefinition, page: Int): Inventory {
        val title = applyPlaceholders(player, menuDef.title)
        val inv = Bukkit.createInventory(null, menuDef.size, colorizeComponent(title))
        menuDef.items.values.forEach { itemDef ->
            if (itemDef.permission != null && !player.hasPermission(itemDef.permission)) return@forEach
            if (itemDef.slot < menuDef.size) {
                inv.setItem(itemDef.slot, buildItemStack(player, itemDef))
            }
        }

        // ============================================================
        // root メニュー専用処理
        // ============================================================
        if (menuDef.id == "root") {
            // ① 0〜44 を全て無記名灰色ガラスで強制初期化
            for (i in 0..44) {
                val glass = org.bukkit.inventory.ItemStack(org.bukkit.Material.GRAY_STAINED_GLASS_PANE)
                val meta  = glass.itemMeta!!
                meta.displayName(colorizeComponent(" "))
                glass.itemMeta = meta
                inv.setItem(i, glass)
            }
            // ② お知らせを上書き (スロット 0 から順番)
            val announcements = plugin.announcementManager.getAnnouncements()
            announcements.forEachIndexed { idx, ann ->
                if (idx > 44) return@forEachIndexed
                val glass = org.bukkit.inventory.ItemStack(org.bukkit.Material.GRAY_STAINED_GLASS_PANE)
                val meta  = glass.itemMeta!!
                meta.displayName(colorizeComponent(ann.title))
                if (ann.body.isNotEmpty()) {
                    meta.lore(ann.body.map { colorizeComponent(it) })
                }
                glass.itemMeta = meta
                inv.setItem(idx, glass)
            }
            // ③ ナビバー (45〜53) を描画
            NavBar.apply(inv, player, plugin, activeSlot = -1)
        }
        return inv
    }

    private fun buildItemStack(player: Player, itemDef: MenuItemDefinition): ItemStack {
        val item = ItemStack(itemDef.icon)
        val meta = item.itemMeta ?: return item
        meta.displayName(colorizeComponent(applyPlaceholders(player, itemDef.name)))
        val lore = itemDef.lore.map { colorizeComponent(applyPlaceholders(player, it)) }
        if (lore.isNotEmpty()) meta.lore(lore)
        item.itemMeta = meta
        return item
    }

    /**
     * 編集モード用ツールバー (スロット 45〜53) を設置する。
     * 設計書の "ツールバー設計" セクションに対応する。
     */
    private fun setupEditToolbar(inv: Inventory) {
        data class T(val slot: Int, val mat: org.bukkit.Material, val name: String)
        listOf(
            T(45, org.bukkit.Material.ARROW,        "&c← 戻る"),
            T(46, org.bukkit.Material.EMERALD,      "&a保存"),
            T(47, org.bukkit.Material.BARRIER,      "&eキャンセル"),
            T(48, org.bukkit.Material.ENDER_EYE,    "&bテスト表示"),
            T(49, org.bukkit.Material.BOOK,         "&dページ設定"),
            T(50, org.bukkit.Material.NETHER_STAR,  "&6新規アイテム追加"),
            T(51, org.bukkit.Material.PAPER,        "&fコピー"),
            T(52, org.bukkit.Material.TNT,          "&4削除モード"),
            T(53, org.bukkit.Material.COMPARATOR,   "&7設定")
        ).forEach { t ->
            val item = ItemStack(t.mat)
            val meta = item.itemMeta!!
            meta.displayName(colorizeComponent(t.name))
            item.itemMeta = meta
            inv.setItem(t.slot, item)
        }
    }

    private fun handleEditClick(
        player: Player,
        state: PlayerMenuState,
        slot: Int,
        event: InventoryClickEvent
    ) {
        when (slot) {
            45 -> { // 戻る
                player.closeInventory()
                val parentId = state.menuId.substringBeforeLast("/", "root")
                if (parentId != state.menuId) openMenu(player, parentId)
            }
            46 -> { // 保存 (TODO: YAML書き出し実装)
                player.sendMessage(colorize("&a保存しました。(YAML書き出しは実装予定)"))
            }
            47 -> { // キャンセル
                player.closeInventory()
                player.sendMessage(colorize("&e編集をキャンセルしました。"))
            }
            48 -> openMenu(player, state.menuId) // テスト表示
            50 -> player.sendMessage(colorize("&6空スロットをクリックしてアイテムを追加。(実装予定)"))
            52 -> player.sendMessage(colorize("&4削除モード切替。(実装予定)"))
            in 0..44 -> {
                if (event.isRightClick) {
                    plugin.actionEngine.openItemEditor(player, state.menuId, slot)
                } else {
                    player.sendMessage(colorize("&7スロット $slot を選択。右クリックで詳細編集。"))
                }
            }
        }
    }

    // ============================
    // ユーティリティ (他クラスからも使用)
    // ============================

    /**
     * テキスト内のプレースホルダーをプレイヤー情報に合わせて展開する。
     *
     * PlaceholderAPI の存在は isPluginEnabled() で確認してから呼び出す。
     * これにより PlaceholderAPI が導入されていない環境でも
     * NullPointerException や ClassNotFoundException が発生しない。
     */
    fun applyPlaceholders(player: Player, text: String): String {
        var result = text
            .replace("%player%",      player.name)
            .replace("%player_name%", player.name)
            .replace("%online%",      Bukkit.getOnlinePlayers().size.toString())
            .replace("%server_tps%",  String.format("%.2f", Bukkit.getTPS()[0]))

        // PlaceholderAPI が有効な場合のみ委譲 (softdepend なので null チェック必須)
        if (Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI")) {
            result = PlaceholderAPI.setPlaceholders(player, result)
        }
        return result
    }

    /** & カラーコードを Adventure の Component に変換する */
    fun colorizeComponent(text: String): Component =
        LegacyComponentSerializer.legacyAmpersand().deserialize(text)

    /** & カラーコードを §コード (レガシー形式) に変換する */
    fun colorize(text: String): String = text.replace('&', '\u00A7')

    fun getPlayerState(player: Player): PlayerMenuState? =
        playerStates[player.uniqueId.toString()]

    fun clearCache() = staticCache.clear()
}