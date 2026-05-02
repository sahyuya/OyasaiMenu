package com.github.sahyuya.oyasaiMenu.engine

import com.github.sahyuya.oyasaiMenu.OyasaiMenu
import com.github.sahyuya.oyasaiMenu.model.ActionType
import com.github.sahyuya.oyasaiMenu.model.MenuAction
import com.github.sahyuya.oyasaiMenu.model.MenuItemDefinition
import com.github.sahyuya.oyasaiMenu.model.PlayerMenuState
import com.github.sahyuya.oyasaiMenu.util.GuiUtil.c
import com.github.sahyuya.oyasaiMenu.util.GuiUtil.comp
import net.luckperms.api.LuckPermsProvider
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack

/**
 * ActionEngine
 *
 * 設計書 "2. Action Engine" の実装。
 * アクションリストを受け取り、プレイヤーに対して順番に実行する。
 *
 * check_permission のような条件分岐アクションは、
 * 結果に応じて success/fail リストを再帰的に実行する。
 *
 * 非同期が必要な処理 (discord_fetch など) は Bukkit スケジューラで
 * 別スレッドへ切り出し、完了後にメインスレッドで結果を適用する。
 */
class ActionEngine(private val plugin: OyasaiMenu) {

    // ============================
    // メイン実行エントリポイント
    // ============================

    /**
     * アクションリストを順番に実行する。
     * このメソッドは必ずメインスレッドから呼ぶこと。
     */
    fun executeActions(player: Player, actions: List<MenuAction>, state: PlayerMenuState? = null) {
        actions.forEach { executeAction(player, it, state) }
    }

    // ============================
    // アクション別実行ロジック
    // ============================

    private fun executeAction(player: Player, action: MenuAction, state: PlayerMenuState?) {
        when (action.type) {

            ActionType.OPEN_MENU -> {
                val target = action.getString("target")
                if (target.isEmpty()) {
                    plugin.logger.warning("open_menu にターゲットが未指定。"); return
                }
                Bukkit.getScheduler().runTaskLater(plugin, Runnable {
                    // popup/ プレフィックスがある場合や PopupMenuLoader に登録済みなら popupMenuEngine へ委譲
                    val popupId = target.removePrefix("popup/")
                    if (plugin.popupMenuLoader.getPopup(popupId) != null) {
                        plugin.popupMenuEngine.open(player, popupId)
                    } else {
                        plugin.menuEngine.openMenu(player, target)
                    }
                }, 1L)
            }

            ActionType.RUN_COMMAND -> {
                val cmd = applyPlaceholders(player, action.getString("command"))
                if (cmd.isNotEmpty()) Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd)
            }

            ActionType.RUN_PLAYER_COMMAND -> {
                val cmd = applyPlaceholders(player, action.getString("command"))
                if (cmd.isNotEmpty()) player.performCommand(cmd)
            }

            ActionType.MESSAGE -> {
                val text = applyPlaceholders(player, action.getString("text"))
                player.sendMessage(c(text))
            }

            ActionType.CLOSE_MENU -> player.closeInventory()

            ActionType.CHECK_PERMISSION -> {
                // 成功なら success リスト、失敗なら fail リストを再帰実行
                val hasPerm = checkPermission(player, action.getString("permission"))
                executeActions(player, if (hasPerm) action.success else action.fail, state)
            }

            ActionType.MACRO_EXECUTE -> {
                // MacroManager 経由でクールダウンチェックとコマンド実行を委譲
                val macroId = action.getString("id")
                val error = plugin.macroManager.executeMacro(player, macroId)
                if (error != null) player.sendMessage(c("&c$error"))
            }

            ActionType.SOUND -> {
                val soundName = action.getString("sound", "UI_BUTTON_CLICK").lowercase()
                val volume = action.getString("volume", "1.0").toFloatOrNull() ?: 1.0f
                val pitch  = action.getString("pitch",  "1.0").toFloatOrNull() ?: 1.0f
                runCatching {
                    player.playSound(player.location, "minecraft:$soundName", volume, pitch)
                }.onFailure { plugin.logger.warning("不明なサウンド: $soundName") }
            }

            ActionType.BROADCAST -> {
                Bukkit.broadcastMessage(c(applyPlaceholders(player, action.getString("text"))))
            }

            ActionType.DISCORD_FETCH -> {
                val channel = action.getString("channel", "general")
                fetchDiscordMessages(player, channel, action)
            }

            ActionType.PLACEHOLDER_TEXT -> {
                val text = applyPlaceholders(player, action.getString("text"))
                player.sendMessage(c(text))
            }

            ActionType.OPEN_SHOP -> {
                val category = action.getString("category", "")
                Bukkit.getScheduler().runTaskLater(plugin, Runnable {
                    if (category.isEmpty()) {
                        // カテゴリ未指定 → ショップ一覧メニューを開く
                        plugin.popupMenuEngine.open(player, "shopindex")
                    } else {
                        // カテゴリ指定 → ShopEngine 経由でショップGUIを直接開く
                        // ※ menuEngine.openMenu("shop/blocks") ではなく shopEngine.openShop() を呼ぶ。
                        //   menus/shop/blocks.yml という静的ファイルは存在しないため。
                        plugin.shopEngine.openShop(player, category)
                    }
                }, 1L)
            }

            ActionType.OPEN_POINT_SHOP -> {
                val category = action.getString("category", "")
                Bukkit.getScheduler().runTaskLater(plugin, Runnable {
                    val catId = if (category.isEmpty())
                        plugin.pointShopLoader.getAllCategories().keys.firstOrNull() ?: return@Runnable
                    else category
                    plugin.pointShopEngine.openShop(player, catId)
                }, 1L)
            }

            ActionType.OPEN_UTILITY -> Bukkit.getScheduler().runTaskLater(plugin, Runnable {
                plugin.popupMenuEngine.open(player, "utility") }, 1L)

            ActionType.OPEN_MACRO -> {
                Bukkit.getScheduler().runTaskLater(plugin, Runnable {
                    plugin.macroEngine.openMacroList(player)
                }, 1L)
            }

            ActionType.OPEN_INFO -> Bukkit.getScheduler().runTaskLater(plugin, Runnable {
                // InfoEngine 廃止 → slot45 はナビバーで常時表示。ここでは root を開くだけ
                plugin.menuEngine.openMenu(player, "root") }, 1L)

            ActionType.OPEN_CHANNEL -> Bukkit.getScheduler().runTaskLater(plugin, Runnable {
                plugin.popupMenuEngine.open(player, "channel") }, 1L)

            ActionType.OPEN_SOCIALLIKES -> Bukkit.getScheduler().runTaskLater(plugin, Runnable {
                plugin.popupMenuEngine.open(player, "sociallikes") }, 1L)

            ActionType.OPEN_CARBUILDER -> Bukkit.getScheduler().runTaskLater(plugin, Runnable {
                plugin.popupMenuEngine.open(player, "carbuilder") }, 1L)

            ActionType.OPEN_LINKS -> Bukkit.getScheduler().runTaskLater(plugin, Runnable {
                plugin.popupMenuEngine.open(player, "links") }, 1L)

            ActionType.OPEN_SELL -> Bukkit.getScheduler().runTaskLater(plugin, Runnable {
                plugin.sellEngine.openSellMenu(player) }, 1L)

            ActionType.UNKNOWN ->
                plugin.logger.warning("未知のアクション: player=${player.name}")

            // ★ else ブランチ: 将来の enum 追加時のコンパイルエラー防止
            //   新アクション追加時は上記に明示的ブランチを追加すること
            else -> plugin.logger.warning("未処理のアクションタイプ: ${action.type} (player=${player.name})")
        }
    }

    // ============================
    // 権限チェック (LuckPerms 優先)
    // ============================

    /**
     * LuckPerms が有効なら LuckPerms API 経由で確認し、
     * 無ければ Bukkit 標準の hasPermission() で代替する。
     * LuckPerms は softdepend なので try-catch で安全に呼び出す。
     */
    private fun checkPermission(player: Player, permission: String): Boolean {
        if (permission.isEmpty()) return true
        return runCatching {
            val lp = LuckPermsProvider.get()
            val user = lp.userManager.getUser(player.uniqueId) ?: return player.hasPermission(permission)
            user.cachedData.permissionData.checkPermission(permission).asBoolean()
        }.getOrElse { player.hasPermission(permission) }
    }

    // ============================
    // Discord 非同期フェッチ
    // ============================

    private fun fetchDiscordMessages(player: Player, channel: String, action: MenuAction) {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, Runnable {
            val messages = runCatching {
                // DiscordSRV が有効な場合にリフレクション経由でフックする。
                // 直接 import しないことで DiscordSRV 未導入環境でもクラスロードエラーを防ぐ。
                Class.forName("github.scarsz.discordsrv.DiscordSRV")
                listOf("(DiscordSRV 連携: 実装中)")
            }.getOrElse { listOf("&7Discord 連携には DiscordSRV が必要です。") }

            Bukkit.getScheduler().runTask(plugin, Runnable {
                val format = action.getString("format", "&7%message%")
                messages.forEach { msg ->
                    player.sendMessage(c(format.replace("%message%", msg)))
                }
            })
        })
    }

    // ============================
    // インゲームアイテム詳細編集
    // ============================

    /**
     * 編集モードで特定スロットのアイテム詳細編集 GUI を開く。
     * 設計書の "詳細編集GUI（アイテム設定）" に対応するタブ構造の骨格実装。
     * タブ: 基本 / アクション / 条件 / 表示 / 高度
     */
    fun openItemEditor(player: Player, menuId: String, slot: Int) {
        val menuDef = plugin.menuLoader.getMenu(menuId) ?: return
        val itemDef = menuDef.items.values.find { it.slot == slot }

        val inv = Bukkit.createInventory(null, 54,
            comp("&8アイテム編集: スロット $slot"))

        // タブ行 (0〜4)
        listOf(
            Triple(0, org.bukkit.Material.IRON_SWORD,    "&a基本"),
            Triple(1, org.bukkit.Material.COMMAND_BLOCK, "&bアクション"),
            Triple(2, org.bukkit.Material.COMPARATOR,    "&e条件"),
            Triple(3, org.bukkit.Material.NAME_TAG,      "&d表示"),
            Triple(4, org.bukkit.Material.REDSTONE,      "&c高度")
        ).forEach { (s, m, n) -> inv.setItem(s, makeGuiItem(m, n)) }

        // プレビューアイテムを中央に表示
        if (itemDef != null) inv.setItem(13, buildPreview(itemDef))

        // 保存 / キャンセル
        inv.setItem(45, makeGuiItem(org.bukkit.Material.EMERALD, "&a保存して閉じる"))
        inv.setItem(53, makeGuiItem(org.bukkit.Material.BARRIER,  "&cキャンセル"))

        player.openInventory(inv)
        player.sendMessage(c("&7詳細編集 GUI (タブ機能は開発中)"))
    }

    private fun makeGuiItem(mat: org.bukkit.Material, name: String): ItemStack {
        val item = ItemStack(mat)
        val meta = item.itemMeta!!
        meta.displayName(comp(name))
        item.itemMeta = meta
        return item
    }

    private fun buildPreview(itemDef: MenuItemDefinition): ItemStack {
        val item = ItemStack(itemDef.icon)
        val meta = item.itemMeta!!
        meta.displayName(comp(itemDef.name.ifEmpty { "&7(名前なし)" }))
        if (itemDef.lore.isNotEmpty()) meta.lore(itemDef.lore.map { comp(it) })
        item.itemMeta = meta
        return item
    }

    // ============================
    // ユーティリティ
    // ============================

    private fun applyPlaceholders(player: Player, text: String): String =
        plugin.menuEngine.applyPlaceholders(player, text)
}