package com.github.sahyuya.oyasaiMenu.engine

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
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.ItemStack

/**
 * ChannelEngine
 *
 * チャットチャンネル切り替えGUI (ChannelChat プラグイン連携)。
 * ポップアップ型: 下1列ナビ、上5列にチャンネル選択ボタン。
 *
 * 各ボタン実行内容 (例: グローバル選択時):
 *   [player] /leave 2
 *   [player] /leave 3
 *   [player] /join Global
 *   [console] tellraw %player% [{"bold":true,"color":"yellow","text":"グローバルチャット"}]
 *   [player] /chwho Global
 */
class ChannelEngine(private val plugin: OyasaiMenu) : Listener {

    private val activePlayers: MutableSet<String> = mutableSetOf()

    // チャンネル定義
    private data class ChannelDef(
        val slot: Int,
        val material: Material,
        val name: String,
        val lore: List<String>,
        val playerCmds: List<String>,   // プレイヤーコマンド
        val consoleCmds: List<String>   // コンソールコマンド (%player% プレースホルダ対応)
    )

    private val channels: List<ChannelDef> = listOf(
        ChannelDef(
            slot = 2,
            material = Material.NAME_TAG,
            name = "&aグローバルチャンネル",
            lore = listOf("&7全員が見えるチャンネル", "", "&eクリックで参加"),
            playerCmds  = listOf("leave 2", "leave 3", "join Global", "chwho Global"),
            consoleCmds = listOf("""tellraw %player% [{"bold":true,"color":"yellow","text":"グローバルチャット"}]""")
        ),
        ChannelDef(
            slot = 11,
            material = Material.NAME_TAG,
            name = "&bチャンネル 2",
            lore = listOf("&7チャンネル2", "", "&eクリックで参加"),
            playerCmds  = listOf("leave 2", "leave 3", "join 2", "chwho 2"),
            consoleCmds = listOf("""tellraw %player% [{"bold":true,"color":"aqua","text":"チャンネル 2"}]""")
        ),
        ChannelDef(
            slot = 20,
            material = Material.NAME_TAG,
            name = "&9チャンネル 3",
            lore = listOf("&7チャンネル3", "", "&eクリックで参加"),
            playerCmds  = listOf("leave 2", "leave 3", "join 3", "chwho 3"),
            consoleCmds = listOf("""tellraw %player% [{"bold":true,"color":"blue","text":"チャンネル 3"}]""")
        ),
        ChannelDef(
            slot = 29,
            material = Material.ENDER_EYE,
            name = "&dチャンネルメンバーを確認",
            lore = listOf("&7各チャンネルの参加者を確認します", "", "&eクリックで確認"),
            playerCmds  = listOf("chwho Global", "chwho 2", "chwho 3"),
            consoleCmds = listOf(
                """tellraw %player% [{"bold":true,"color":"yellow","text":"---------------------------------------------\n"},{"bold":true,"color":"gray","text":"チャットルームの切り替え:\n"},{"color":"yellow","text":"/menuを開いてチャンネルメニューのパネルから選択\n"}]"""
            )
        )
    )

    // ============================
    // 公開API
    // ============================

    fun openChannel(player: Player) {
        val inv = buildInventory(player)
        player.openInventory(inv)
        activePlayers.add(player.uniqueId.toString())
    }

    // ============================
    // 構築
    // ============================

    private fun buildInventory(player: Player): Inventory {
        val inv = Bukkit.createInventory(null, 54, comp("&cチャンネルメニュー"))
        channels.forEach { ch ->
            inv.setItem(ch.slot, makeItem(ch.material, ch.name, ch.lore))
        }
        NavBar.apply(inv, activeSlot = 46)
        NavBar.fillTop(inv, Material.RED_STAINED_GLASS_PANE)
        return inv
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
        val slot = event.rawSlot
        when {
            slot in 45..53 -> plugin.navHandler.handleNavClick(player, slot)
            else -> {
                val ch = channels.find { it.slot == slot } ?: return
                // コンソールコマンド先に実行
                ch.consoleCmds.forEach { cmd ->
                    Bukkit.dispatchCommand(
                        Bukkit.getConsoleSender(),
                        cmd.replace("%player%", player.name)
                    )
                }
                // プレイヤーコマンド実行
                ch.playerCmds.forEach { player.performCommand(it) }
                player.sendMessage(c("&a「${ch.name.replace("§[0-9a-fk-or]".toRegex(), "")}」に切り替えました。"))
                player.closeInventory()
            }
        }
    }

    @EventHandler
    fun onInventoryClose(event: InventoryCloseEvent) {
        activePlayers.remove((event.player as? Player)?.uniqueId?.toString() ?: return)
    }

    // ============================
    // ユーティリティ
    // ============================

    private fun makeItem(mat: Material, name: String, lore: List<String>): ItemStack {
        val item = ItemStack(mat); val meta = item.itemMeta!!
        meta.displayName(comp(name)); meta.lore(lore.map { comp(it) })
        item.itemMeta = meta; return item
    }

    private fun comp(text: String): Component =
        LegacyComponentSerializer.legacyAmpersand().deserialize(text)
    private fun c(text: String) = text.replace('&', '\u00A7')
}