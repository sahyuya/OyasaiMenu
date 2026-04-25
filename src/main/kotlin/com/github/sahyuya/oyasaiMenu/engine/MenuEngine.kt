package com.github.sahyuya.oyasaiMenu.engine

import com.github.sahyuya.oyasaiMenu.OyasaiMenu
import com.github.sahyuya.oyasaiMenu.manager.CooldownManager
import com.github.sahyuya.oyasaiMenu.model.MenuDefinition
import com.github.sahyuya.oyasaiMenu.model.MenuItemDefinition
import com.github.sahyuya.oyasaiMenu.model.PlayerMenuState
import me.clip.placeholderapi.PlaceholderAPI
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
 * MenuEngine
 * アナウンス表示: 先頭1件を 0〜44 全スロットに同じ内容で表示
 */
class MenuEngine(private val plugin: OyasaiMenu) : Listener {

    private val playerStates: MutableMap<String, PlayerMenuState> = mutableMapOf()
    private val staticCache:  MutableMap<String, Inventory>       = mutableMapOf()

    fun openMenu(player: Player, menuId: String, page: Int = 0) {
        val menuDef = plugin.menuLoader.getMenu(menuId)
        if (menuDef == null) {
            player.sendMessage(colorize("&cメニューが見つかりません: $menuId"))
            plugin.logger.warning("存在しないメニューID: $menuId (player=${player.name})"); return
        }
        val inventory = buildInventory(player, menuDef, page)
        player.openInventory(inventory)
        playerStates[player.uniqueId.toString()] = PlayerMenuState(menuId = menuId, page = page, isEditing = false)
    }

    fun openMenuInEditMode(player: Player, menuId: String) {
        val menuDef = plugin.menuLoader.getMenu(menuId) ?: run { player.sendMessage(colorize("&cメニューが見つかりません: $menuId")); return }
        val inv = Bukkit.createInventory(null, 54, colorizeComponent("&c[編集] ${menuDef.title}"))
        menuDef.items.values.forEach { if (it.slot < 45) inv.setItem(it.slot, buildItemStack(player, it)) }
        setupEditToolbar(inv)
        player.openInventory(inv)
        playerStates[player.uniqueId.toString()] = PlayerMenuState(menuId = menuId, page = 0, isEditing = true)
    }

    @EventHandler
    fun onInventoryClick(event: InventoryClickEvent) {
        val player = event.whoClicked as? Player ?: return
        val state  = playerStates[player.uniqueId.toString()] ?: return
        if (event.clickedInventory == player.inventory) { if (event.isShiftClick) event.isCancelled = true; return }
        event.isCancelled = true
        if (CooldownManager.isClickOnCooldown(player.uniqueId)) return
        val slot = event.rawSlot
        if (state.isEditing) { handleEditClick(player, state, slot, event); return }
        if (state.menuId == "root" && slot in 45..53) {
            when (slot) {
                45 -> { NavBar.apply(player.openInventory.topInventory, player, plugin, -1); player.playSound(player.location, org.bukkit.Sound.UI_BUTTON_CLICK, 0.5f, 1f) }
                else -> { val entry = NavBar.entries.find { it.slot == slot }; if (entry != null) Bukkit.getScheduler().runTaskLater(plugin, Runnable { plugin.popupMenuEngine.open(player, entry.popupId) }, 1L) }
            }
            return
        }
        val menuDef = plugin.menuLoader.getMenu(state.menuId) ?: return
        val itemDef = menuDef.items.values.find { it.slot == slot } ?: return
        plugin.actionEngine.executeActions(player, itemDef.actions, state)
    }

    @EventHandler
    fun onInventoryClose(event: InventoryCloseEvent) {
        playerStates.remove((event.player as? Player)?.uniqueId?.toString() ?: return)
    }

    private fun buildInventory(player: Player, menuDef: MenuDefinition, page: Int): Inventory {
        val title = applyPlaceholders(player, menuDef.title)
        val inv   = Bukkit.createInventory(null, menuDef.size, colorizeComponent(title))
        menuDef.items.values.forEach { itemDef ->
            if (itemDef.permission != null && !player.hasPermission(itemDef.permission)) return@forEach
            if (itemDef.slot < menuDef.size) inv.setItem(itemDef.slot, buildItemStack(player, itemDef))
        }
        if (menuDef.id == "root") {
            // 0〜44を灰色ガラスで初期化
            val emptyGlass = makeGlass(Material.GRAY_STAINED_GLASS_PANE, " ")
            for (i in 0..44) inv.setItem(i, emptyGlass)
            // 先頭1件を全スロットに同じ内容で表示
            val ann = plugin.announcementManager.getAnnouncements().firstOrNull()
            if (ann != null) {
                val glass = makeGlass(Material.GRAY_STAINED_GLASS_PANE, ann.title, ann.body)
                for (i in 0..44) inv.setItem(i, glass)
            }
            NavBar.apply(inv, player, plugin, activeSlot = -1)
        }
        return inv
    }

    private fun makeGlass(mat: Material, name: String, lore: List<String> = emptyList()): ItemStack {
        val item = ItemStack(mat); val meta = item.itemMeta!!
        meta.displayName(colorizeComponent(name))
        if (lore.isNotEmpty()) meta.lore(lore.map { colorizeComponent(it) })
        item.itemMeta = meta; return item
    }

    private fun buildItemStack(player: Player, itemDef: MenuItemDefinition): ItemStack {
        val item = ItemStack(itemDef.icon); val meta = item.itemMeta ?: return item
        meta.displayName(colorizeComponent(applyPlaceholders(player, itemDef.name)))
        val lore = itemDef.lore.map { colorizeComponent(applyPlaceholders(player, it)) }
        if (lore.isNotEmpty()) meta.lore(lore)
        item.itemMeta = meta; return item
    }

    private fun setupEditToolbar(inv: Inventory) {
        data class T(val slot: Int, val mat: Material, val name: String)
        listOf(T(45,Material.ARROW,"&c← 戻る"),T(46,Material.EMERALD,"&a保存"),T(47,Material.BARRIER,"&eキャンセル"),
            T(48,Material.ENDER_EYE,"&bテスト表示"),T(49,Material.BOOK,"&dページ設定"),T(50,Material.NETHER_STAR,"&6新規アイテム追加"),
            T(51,Material.PAPER,"&fコピー"),T(52,Material.TNT,"&4削除モード"),T(53,Material.COMPARATOR,"&7設定")).forEach { t ->
            val item = ItemStack(t.mat); val meta = item.itemMeta!!; meta.displayName(colorizeComponent(t.name)); item.itemMeta = meta; inv.setItem(t.slot, item)
        }
    }

    private fun handleEditClick(player: Player, state: PlayerMenuState, slot: Int, event: InventoryClickEvent) {
        when (slot) {
            45 -> { player.closeInventory(); val parentId = state.menuId.substringBeforeLast("/","root"); if (parentId != state.menuId) openMenu(player, parentId) }
            46 -> player.sendMessage(colorize("&a保存しました。(YAML書き出しは実装予定)"))
            47 -> { player.closeInventory(); player.sendMessage(colorize("&e編集をキャンセルしました。")) }
            48 -> openMenu(player, state.menuId)
            50 -> player.sendMessage(colorize("&6空スロットをクリックしてアイテムを追加。(実装予定)"))
            52 -> player.sendMessage(colorize("&4削除モード切替。(実装予定)"))
            in 0..44 -> { if (event.isRightClick) plugin.actionEngine.openItemEditor(player, state.menuId, slot) else player.sendMessage(colorize("&7スロット $slot を選択。右クリックで詳細編集。")) }
        }
    }

    fun applyPlaceholders(player: Player, text: String): String {
        var result = text.replace("%player%", player.name).replace("%player_name%", player.name)
            .replace("%online%", Bukkit.getOnlinePlayers().size.toString())
            .replace("%server_tps%", String.format("%.2f", Bukkit.getTPS()[0]))
        if (Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI")) result = PlaceholderAPI.setPlaceholders(player, result)
        return result
    }
    fun colorizeComponent(text: String): Component = LegacyComponentSerializer.legacyAmpersand().deserialize(text)
    fun colorize(text: String): String = text.replace('&', '\u00A7')
    fun getPlayerState(player: Player): PlayerMenuState? = playerStates[player.uniqueId.toString()]
    fun clearCache() = staticCache.clear()
}