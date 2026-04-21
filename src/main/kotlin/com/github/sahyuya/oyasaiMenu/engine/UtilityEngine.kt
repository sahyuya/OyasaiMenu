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
 * UtilityEngine - サーバー公式コマンドショートカット集
 *
 * ■ ポップアップ型レイアウト (54スロット)
 *
 * 行0 (スロット0〜8): ワープ行
 *   [0] 水色ろうそく (ヘッダー)
 *   [1] スポーン  [2] 資源ワールド  [3] PVP  [4] カジノ  [5] 経験値トラップ
 *   [6][7][8] ガラス
 *
 * 行1 (スロット9〜17): 生活機能行
 *   [9] 水色ろうそく
 *   [10]作業台 [11]エンダーチェスト [12]バックパック [13]ゴミ箱
 *   [14]金床 [15]砥石 [16]機織り機 [17]ガラス
 *
 * 行2 (スロット18〜26): モード変更行
 *   [18] 青ろうそく
 *   [19]Fly [20]レッドブル [21]サバイバル [22]クリエイティブ
 *   [23]スペクテイター [24]AFK [25]案内モード [26]ガラス
 *
 * 行3 (スロット27〜35): 個人設定行
 *   [27] 紫ろうそく
 *   [28]Language [29]TPset open [30]TPset close [31]TPset mode
 *   [32][33][34][35] ガラス
 *
 * 行4 (スロット36〜44): その他行
 *   [36] 赤紫ろうそく
 *   [37]メニュー本 [38]TP許可 [39]TP拒否 [40]お絵かきツール
 *   [41]カラーパレット [42]アイテムフレーム [43][44] ガラス
 *
 * 行5 (スロット45〜53): ナビゲーションバー
 */
class UtilityEngine(private val plugin: OyasaiMenu) : Listener {

    private val activePlayers: MutableSet<String> = mutableSetOf()

    private data class UtilItem(
        val slot: Int,
        val material: Material,
        val name: String,
        val lore: List<String>,
        val playerCmd: String? = null,     // /を除いたコマンド, %player% 使用可
        val consoleCmd: String? = null,    // コンソールコマンド, %player% 使用可
        val chatPaste: String? = null      // チャット欄に貼り付けるテキスト (案内モード用)
    )

    private val items: List<UtilItem> = listOf(
        // ---- 行0: ワープ ----
        UtilItem(0,  Material.CYAN_CANDLE,      "&bワープ", listOf("&7ワープ先を選択"), null),
        UtilItem(1,  Material.RED_BED,           "&cスポーン", listOf("&7スポーンへ"), "spawn"),
        UtilItem(2,  Material.OAK_LOG,           "&a資源ワールド", listOf("&7資源ワールドへ"), "warp shigen"),
        UtilItem(3,  Material.IRON_SWORD,        "&7PVPワールド", listOf("&7PVP専用ワールドへ"), "warp pvp"),
        UtilItem(4,  Material.GOLD_INGOT,        "&6カジノ", listOf("&7カジノへ"), "warp casino"),
        UtilItem(5,  Material.EXPERIENCE_BOTTLE, "&aエンチャントトラップ", listOf("&7経験値トラップへ"), "warp endertt"),

        // ---- 行1: 生活機能 ----
        UtilItem(9,  Material.LIGHT_BLUE_CANDLE, "&b生活機能", listOf("&7便利コマンド"), null),
        UtilItem(10, Material.CRAFTING_TABLE,    "&f作業台", listOf("&7/craft"), "craft"),
        UtilItem(11, Material.ENDER_CHEST,       "&5エンダーチェスト", listOf("&7/ec"), "ec"),
        UtilItem(12, Material.CHEST,             "&6バックパック", listOf("&7/bp"), "bp"),
        UtilItem(13, Material.BARRIER,           "&cゴミ箱", listOf("&7/trash"), "trash"),
        UtilItem(14, Material.ANVIL,             "&7金床", listOf("&7/anvil"), "anvil"),
        UtilItem(15, Material.GRINDSTONE,        "&f砥石", listOf("&7/grindstone"), "grindstone"),
        UtilItem(16, Material.LOOM,              "&dロウム(機織り機)", listOf("&7/loom"), "loom"),

        // ---- 行2: モード変更 ----
        UtilItem(18, Material.BLUE_CANDLE,       "&9モード変更", listOf("&7ゲームモードを切り替え"), null),
        UtilItem(19, Material.FEATHER,           "&bFly 切り替え",
            listOf("&c※ポイント購入 or 上級者以上のランクが必要"), "fly"),
        UtilItem(20, Material.GOLDEN_APPLE,      "&6レッドブルを飲む",
            listOf("&7flyを強制的に有効にします", "&c※1日フライ券がない場合は20Pを消費"), "redbull"),
        UtilItem(21, Material.GRASS_BLOCK,       "&aサバイバルモード", listOf("&7/gms"), "gms"),
        UtilItem(22, Material.GOLDEN_HELMET,     "&eクリエイティブモード",
            listOf("&c※ポイント購入 or 匠以上のランクが必要"), "gmc"),
        UtilItem(23, Material.VEX_SPAWN_EGG,     "&7スペクテイターモード",
            listOf("&c※建築士以上のランクが必要"), "gmsp"),
        UtilItem(24, Material.ARMOR_STAND,       "&7AFK",
            listOf("&7その場でAFK状態に"), "afk"),
        UtilItem(25, Material.PURPLE_GLAZED_TERRACOTTA, "&d案内モード",
            listOf("&7/annnai <player> をチャット欄に貼り付けます"),
            chatPaste = "/annnai "),

        // ---- 行3: 個人設定 ----
        UtilItem(27, Material.PURPLE_CANDLE,     "&5個人設定", listOf("&7個人設定"), null),
        UtilItem(28, Material.FILLED_MAP,        "&bLanguage",
            listOf("&7全メニューを英語版に切り替える (準備中)")),
        UtilItem(29, Material.GLOW_INK_SAC,      "&a/tpset open",
            listOf("&7他プレイヤーが常時テレポートできます",
                "&7/tpset ng <player> でブラックリスト設定"), "tpset open"),
        UtilItem(30, Material.INK_SAC,           "&c/tpset close",
            listOf("&7他プレイヤーのテレポートを拒否します",
                "&7/tpset ok <player> でホワイトリスト設定"), "tpset close"),
        UtilItem(31, Material.SQUID_SPAWN_EGG,   "&e/tpset mode",
            listOf("&7現在のTP許可設定を確認する"), "tpset mode"),

        // ---- 行4: その他 ----
        UtilItem(36, Material.MAGENTA_CANDLE,    "&dその他", listOf("&7その他の便利機能"), null),
        UtilItem(37, Material.BOOK,              "&fメニュー本を再入手",
            listOf("&7/getmenu"), "getmenu"),
        UtilItem(38, Material.LIME_SHULKER_BOX,  "&aTP申請を許可",
            listOf("&7/tpaccept"), "tpaccept"),
        UtilItem(39, Material.RED_SHULKER_BOX,   "&cTP申請を拒否",
            listOf("&7/tpdeny"), "tpdeny"),
        UtilItem(40, Material.ENCHANTED_BOOK,    "&dお絵かきツール",
            listOf("&7額縁に飾って染料で右クリックでお絵かき",
                "&c※ポイントショップで「表現の自由」が必要"),
            "startpaint", consoleCmd = "give %player% item_frame 1"),
        UtilItem(41, Material.MAGENTA_SHULKER_BOX,"&5カラーパレット入手",
            listOf("&c※「表現の自由」が必要"), "getdye"),
        UtilItem(42, Material.ITEM_FRAME,         "&fアイテムフレーム入手",
            listOf("&c※「表現の自由」が必要"),
            consoleCmd = "give %player% item_frame 1")
    )

    // ============================
    // 公開API
    // ============================

    fun openUtility(player: Player) {
        val inv = buildInventory()
        player.openInventory(inv)
        activePlayers.add(player.uniqueId.toString())
    }

    // ============================
    // 構築
    // ============================

    private fun buildInventory(): Inventory {
        val inv = Bukkit.createInventory(null, 54, comp("&9ユーティリティ"))
        items.forEach { u ->
            if (u.playerCmd != null || u.consoleCmd != null || u.chatPaste != null || u.playerCmd == null && u.consoleCmd == null && u.chatPaste == null) {
                inv.setItem(u.slot, makeItem(u.material, u.name, u.lore))
            }
        }
        NavBar.apply(inv, activeSlot = 51)
        NavBar.fillTop(inv, Material.BLUE_STAINED_GLASS_PANE)
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
            if (event.isShiftClick) event.isCancelled = true; return
        }
        event.isCancelled = true
        val slot = event.rawSlot
        if (slot in 45..53) { plugin.navHandler.handleNavClick(player, slot); return }

        val u = items.find { it.slot == slot } ?: return
        // ヘッダー (コマンドなし) は何もしない
        if (u.playerCmd == null && u.consoleCmd == null && u.chatPaste == null) return

        // チャット貼り付けモード
        if (u.chatPaste != null) {
            player.closeInventory()
            // Paper では sendMessage + Suggestion だが、一般的にはコマンドをチャットに流す
            // 代替: チャット欄にプレフィックスを書いてプレイヤーに入力させる
            player.sendMessage(c("&7チャット欄に以下を入力してください: &f${u.chatPaste}"))
            return
        }

        // コンソールコマンド
        u.consoleCmd?.let { cmd ->
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd.replace("%player%", player.name))
        }
        // プレイヤーコマンド
        u.playerCmd?.let { cmd ->
            player.performCommand(cmd.replace("%player%", player.name))
        }
        player.closeInventory()
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
        meta.displayName(comp(name))
        val fullLore = lore.toMutableList()
        fullLore += ""; fullLore += "&eクリックで実行"
        meta.lore(fullLore.map { comp(it) })
        item.itemMeta = meta; return item
    }

    private fun comp(t: String): Component = LegacyComponentSerializer.legacyAmpersand().deserialize(t)
    private fun c(t: String) = t.replace('&', '\u00A7')
}