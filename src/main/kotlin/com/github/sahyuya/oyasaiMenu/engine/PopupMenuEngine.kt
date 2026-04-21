package com.github.sahyuya.oyasaiMenu.engine

import com.github.sahyuya.oyasaiMenu.OyasaiMenu
import com.github.sahyuya.oyasaiMenu.model.*
import com.github.sahyuya.oyasaiMenu.util.CustomHead
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

class PopupMenuEngine(private val plugin: OyasaiMenu) : Listener {

    // UUID → 現在開いている popup ID
    private val activePlayers: MutableMap<String, String> = mutableMapOf()

    // ============================
    // 公開API
    // ============================

    /** 指定 popup ID のメニューを開く */
    fun open(player: Player, popupId: String) {
        val def = plugin.popupMenuLoader.getPopup(popupId) ?: run {
            plugin.logger.warning("Popup '$popupId' が見つかりません。menus/popup/$popupId.yml を確認してください。")
            player.sendMessage(c("&c内部エラー: popup '$popupId' が見つかりません。"))
            return
        }
        val inv = buildInventory(player, def)
        player.openInventory(inv)
        activePlayers[player.uniqueId.toString()] = popupId   // openInventory() の後
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
        // カスタムヘッドかどうか
        val stack = when {
            item.icon == Material.PLAYER_HEAD && item.customTexture != null ->
                CustomHead.get(item.customTexture)
            else -> ItemStack(item.icon)
        }
        val meta = stack.itemMeta ?: return stack

        if (item.name.isNotEmpty()) meta.displayName(comp(item.name))
        if (item.lore.isNotEmpty()) meta.lore(item.lore.map { comp(it) })

        // 隠しエンチャントグロー
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

        val slot   = event.rawSlot
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
        // 全ポップアップを統一的に開く
        // sell_menu → sell_menu.yml のボタンから SellEngine.openSellMenu() が呼ばれる
        // shop_index → shop_index.yml のボタンから shopEngine.openShop() が呼ばれる
        open(player, entry.popupId)
    }

    // ============================
    // アクション実行
    // ============================

    private fun executeActions(player: Player, actions: List<PopupAction>) {
        var closed = false
        actions.forEach { action ->
            if (closed) return@forEach
            when (action.type) {
                PopupActionType.PLAYER_CMD  -> player.performCommand(action.value.replace("%player%", player.name))
                PopupActionType.CONSOLE_CMD -> Bukkit.dispatchCommand(
                    Bukkit.getConsoleSender(), action.value.replace("%player%", player.name))
                PopupActionType.URL -> {
                    player.sendMessage(c("&e${action.value}"))
                    player.sendMessage(c("&7URLをクリックまたはコピーしてアクセスしてください。"))
                }
                PopupActionType.CHAT_PASTE -> {
                    player.sendMessage(c("&7チャット欄にコピーして使用してください: &f${action.value}"))
                }
                PopupActionType.OPEN_POPUP -> {
                    Bukkit.getScheduler().runTaskLater(plugin, Runnable { open(player, action.value) }, 1L)
                    closed = true
                }
                PopupActionType.OPEN_SHOP -> {
                    Bukkit.getScheduler().runTaskLater(plugin, Runnable {
                        if (action.value.isEmpty()) plugin.menuEngine.openMenu(player, "shop/index")
                        else plugin.shopEngine.openShop(player, action.value)
                    }, 1L)
                    closed = true
                }
                PopupActionType.OPEN_SELL -> {
                    Bukkit.getScheduler().runTaskLater(plugin, Runnable {
                        plugin.sellEngine.openSellMenu(player)
                    }, 1L)
                    closed = true
                }
                PopupActionType.OPEN_MACRO -> {
                    Bukkit.getScheduler().runTaskLater(plugin, Runnable {
                        plugin.macroEngine.openMacroList(player)
                    }, 1L)
                    closed = true
                }
                PopupActionType.OPEN_MENU -> {
                    Bukkit.getScheduler().runTaskLater(plugin, Runnable {
                        plugin.menuEngine.openMenu(player, action.value)
                    }, 1L)
                    closed = true
                }
                PopupActionType.CLOSE -> {
                    player.closeInventory()
                    closed = true
                }
            }
        }
    }

    // ============================
    // ユーティリティ
    // ============================

    private fun comp(t: String): Component = LegacyComponentSerializer.legacyAmpersand().deserialize(t)
    private fun c(t: String) = t.replace('&', '\u00A7')
}