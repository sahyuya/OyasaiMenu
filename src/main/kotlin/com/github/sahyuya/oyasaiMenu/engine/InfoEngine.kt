package com.github.sahyuya.oyasaiMenu.engine

import com.github.sahyuya.oyasaiMenu.OyasaiMenu
import com.github.sahyuya.oyasaiMenu.manager.EconomyManager
import com.github.sahyuya.oyasaiMenu.manager.TokenCurrencyManager
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
import org.bukkit.Bukkit
import org.bukkit.GameMode
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryCloseEvent
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.SkullMeta

/**
 * InfoEngine
 *
 * サーバー情報・プレイヤー情報を表示する専用パネル。
 * ポップアップ型レイアウトに従い、下1列はナビゲーションバー。
 *
 * ■ 上部 (スロット 0〜44) レイアウト
 *   [4]  サーバー情報 (コンパス) — 左クリックで更新
 *   [13] プレイヤー情報 (プレイヤーヘッド) — 左クリックで更新
 *
 *   残りは灰色ガラスで埋める
 */
class InfoEngine(private val plugin: OyasaiMenu) : Listener {

    private val activePlayers: MutableSet<String> = mutableSetOf()

    fun openInfo(player: Player) {
        val inv = buildInventory(player)
        player.openInventory(inv)
        activePlayers.add(player.uniqueId.toString())
    }

    // ============================
    // 構築
    // ============================

    private fun buildInventory(player: Player): Inventory {
        val inv = Bukkit.createInventory(null, 54, comp("&f⚙ サーバー/個人情報"))

        inv.setItem(4,  buildServerItem())
        inv.setItem(13, buildPlayerItem(player))

        NavBar.apply(inv, activeSlot = 45)
        NavBar.fillTop(inv, Material.GRAY_STAINED_GLASS_PANE)
        return inv
    }

    private fun buildServerItem(): ItemStack {
        val tps = Bukkit.getTPS()
        val t0  = tps[0]; val t1 = tps[1]; val t2 = tps[2]
        fun tColor(v: Double) = if (v >= 19) "&a" else if (v >= 15) "&e" else "&c"
        val online = Bukkit.getOnlinePlayers().size
        val max    = Bukkit.getMaxPlayers()
        return makeItem(Material.COMPASS, "&bサーバー情報", listOf(
            "&7TPS (現在):  ${tColor(t0)}${String.format("%.1f", t0)}",
            "&7TPS (5分):  ${tColor(t1)}${String.format("%.1f", t1)}",
            "&7TPS (15分): ${tColor(t2)}${String.format("%.1f", t2)}",
            "",
            "&7オンライン: &f$online &7/ &f$max 人",
            "",
            "&e左クリックで更新"
        ))
    }

    private fun buildPlayerItem(player: Player): ItemStack {
        val bal = if (EconomyManager.isAvailable)
            EconomyManager.format(EconomyManager.getBalance(player)) else "&7(Vaultなし)"
        val pts = if (TokenCurrencyManager.isAvailable)
            "${TokenCurrencyManager.format(TokenCurrencyManager.getTokens(player))}P" else "&7(TokenManagerなし)"
        val gm = when (player.gameMode) {
            GameMode.SURVIVAL   -> "&aサバイバル"
            GameMode.CREATIVE   -> "&cクリエイティブ"
            GameMode.ADVENTURE  -> "&6アドベンチャー"
            GameMode.SPECTATOR  -> "&7スペクテイター"
            else -> "&7不明"
        }

        // プレイヤーヘッドをスキンで取得
        val skull = ItemStack(Material.PLAYER_HEAD)
        val skullMeta = skull.itemMeta as? SkullMeta ?: return skull
        skullMeta.owningPlayer = player
        skullMeta.displayName(comp("&a${player.name} の情報"))
        skullMeta.lore(listOf(
            comp("&7ゲームモード: $gm"),
            comp("&7体力: &f${String.format("%.1f", player.health)} &7/ &f${player.maxHealth.toInt()}"),
            comp("&7満腹度: &f${player.foodLevel} &7/ 20"),
            comp("&7経験値 Lv: &f${player.level}"),
            comp(""),
            comp("&7所持金: &f$bal"),
            comp("&7ポイント: &f$pts"),
            comp(""),
            comp("&e左クリックで更新")
        ).map { it })
        skull.itemMeta = skullMeta
        return skull
    }

    // ============================
    // イベントハンドラ
    // ============================

    @EventHandler
    fun onInventoryClick(event: InventoryClickEvent) {
        val player = event.whoClicked as? Player ?: return
        if (!activePlayers.contains(player.uniqueId.toString())) return
        if (event.clickedInventory == player.inventory) {
            if (event.isShiftClick) event.isCancelled = true
            return
        }
        event.isCancelled = true
        val inv = player.openInventory.topInventory

        when (val slot = event.rawSlot) {
            4  -> { inv.setItem(4, buildServerItem());      player.playSound(player.location, org.bukkit.Sound.UI_BUTTON_CLICK, 0.5f, 1f) }
            13 -> { inv.setItem(13, buildPlayerItem(player)); player.playSound(player.location, org.bukkit.Sound.UI_BUTTON_CLICK, 0.5f, 1f) }
            in 45..53 -> handleNav(player, slot)
        }
    }

    @EventHandler
    fun onInventoryClose(event: InventoryCloseEvent) {
        activePlayers.remove((event.player as? Player)?.uniqueId?.toString() ?: return)
    }

    private fun handleNav(player: Player, slot: Int) =
        plugin.navHandler.handleNavClick(player, slot)

    // ============================
    // ユーティリティ
    // ============================

    private fun makeItem(mat: Material, name: String, lore: List<String>): ItemStack {
        val item = ItemStack(mat)
        val meta = item.itemMeta!!
        meta.displayName(comp(name))
        meta.lore(lore.map { comp(it) })
        item.itemMeta = meta
        return item
    }

    private fun comp(text: String): Component =
        LegacyComponentSerializer.legacyAmpersand().deserialize(text)
}