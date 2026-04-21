package com.github.sahyuya.oyasaiMenu.engine

import com.github.sahyuya.oyasaiMenu.OyasaiMenu
import com.github.sahyuya.oyasaiMenu.model.PlayerMacro
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryCloseEvent
import org.bukkit.event.player.AsyncPlayerChatEvent
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.ItemStack
import java.util.UUID

/**
 * MacroEngine
 *
 * プレイヤーが自分のコマンドマクロを管理・実行するための GUI エンジン。
 *
 * ■ 画面構成
 *
 *   【一覧画面】 /macro または /menu → マクロ から開く
 *     行0〜4 (0〜44): マクロアイテム (1マクロ = 1スロット)
 *     行5:
 *       [45]戻る  [49]新規作成  [53]ページ切替
 *
 *   【詳細/編集画面】 マクロを右クリックで開く
 *     [10] マクロ名変更  [12] コマンド一覧  [14] 実行  [16] 削除
 *     [49] 戻る
 *
 * ■ テキスト入力 (名前・コマンド)
 *   チャット入力方式: GUIを閉じてチャットで入力 → Enter で確定
 *   入力待ち状態は chatInputPlayers で管理する。
 *
 * ■ 権限
 *   visualmenu.use があれば誰でも使用可能
 *   コマンドホワイトリストは config.yml の macro.command-whitelist で制限可能
 */
class MacroEngine(private val plugin: OyasaiMenu) : Listener {

    // 今マクロ一覧GUIを開いているプレイヤー
    private val activePlayers: MutableSet<String> = mutableSetOf()

    // チャット入力待ち状態
    // uuid → 入力モードの情報 (MACRO_NAME, COMMAND_ADD)
    private data class ChatInputState(
        val mode: InputMode,
        val macroId: String?,      // 編集対象マクロID (新規作成時は null)
        val macroName: String? = null // 名前確定後にコマンド入力に移る際に保持
    )
    private enum class InputMode { MACRO_NAME, COMMAND_ADD }
    private val chatInputPlayers: MutableMap<String, ChatInputState> = mutableMapOf()

    // ============================
    // 公開API
    // ============================

    fun openMacroList(player: Player) {
        val inv = buildMacroListInventory(player, 0)
        player.openInventory(inv)
        activePlayers.add(player.uniqueId.toString())
    }

    // ============================
    // インベントリ構築
    // ============================

    private fun buildMacroListInventory(player: Player, page: Int): Inventory {
        val macros = plugin.macroManager.getMacros(player.uniqueId)
        val maxPerPage = 45
        val pageCount = maxOf(1, (macros.size + maxPerPage - 1) / maxPerPage)
        val currentPage = page.coerceIn(0, pageCount - 1)

        val inv = Bukkit.createInventory(null, 54, comp("&6コマンドマクロ &8| &f${currentPage + 1}/${pageCount}"))

        // マクロをスロットに配置
        val pageItems = macros.drop(currentPage * maxPerPage).take(maxPerPage)
        pageItems.forEachIndexed { i, macro ->
            inv.setItem(i, buildMacroItem(macro))
        }

        // 操作バー
        inv.setItem(45, makeItem(Material.ARROW, "&c← 戻る", listOf("&7メインメニューに戻ります")))

        val hasPrev = currentPage > 0
        val hasNext = currentPage < pageCount - 1
        if (hasPrev) inv.setItem(46, makeItem(Material.ARROW, "&e← 前ページ"))
        if (hasNext) inv.setItem(52, makeItem(Material.ARROW, "&e次ページ →"))

        val maxMacros = plugin.config.getInt("macro.max-per-player", 10)
        val canAdd = macros.size < maxMacros
        inv.setItem(49, makeItem(
            if (canAdd) Material.NETHER_STAR else Material.GRAY_STAINED_GLASS_PANE,
            if (canAdd) "&a+ 新しいマクロを作成" else "&7マクロ上限 ($maxMacros) に達しています",
            listOf("&7現在: &f${macros.size} &7/ &f$maxMacros")
        ))

        // 使い方ヒント
        inv.setItem(53, makeItem(Material.BOOK, "&7ヒント", listOf(
            "&e左クリック &7→ マクロを実行",
            "&e右クリック &7→ 詳細・編集・削除",
        )))

        return inv
    }

    private fun buildMacroItem(macro: PlayerMacro): ItemStack {
        val item = makeItem(
            Material.COMMAND_BLOCK,
            "&e${macro.name}",
            buildList {
                add("&7コマンド (${macro.commands.size} 個):")
                macro.commands.take(5).forEach { add("&8  /$it") }
                if (macro.commands.size > 5) add("&8  ...他 ${macro.commands.size - 5} 個")
                add("")
                add("&eCooldown: &f${macro.cooldownSeconds}秒")
                add("")
                add("&e左クリック &7→ 実行")
                add("&e右クリック &7→ 詳細・編集")
            }
        )
        // マクロID を ItemStack の CustomModelData 的な方法で保存する代わりに
        // PersistentDataContainer に格納する
        val meta = item.itemMeta!!
        meta.persistentDataContainer.set(
            org.bukkit.NamespacedKey(plugin, "macro_id"),
            org.bukkit.persistence.PersistentDataType.STRING,
            macro.id
        )
        item.itemMeta = meta
        return item
    }

    private fun buildMacroDetailInventory(player: Player, macro: PlayerMacro): Inventory {
        val inv = Bukkit.createInventory(null, 54, comp("&6マクロ詳細: &e${macro.name}"))

        inv.setItem(10, makeItem(Material.NAME_TAG, "&a名前を変更", listOf(
            "&f${macro.name}",
            "",
            "&eクリックしてチャットで入力"
        )))

        inv.setItem(12, makeItem(Material.WRITABLE_BOOK, "&bコマンド一覧・追加",
            buildList {
                add("&7現在のコマンド (${macro.commands.size} 個):")
                macro.commands.forEachIndexed { i, cmd -> add("&8  ${i + 1}. /$cmd") }
                add("")
                add("&eクリックしてコマンドを追加")
                add("&7(削除は別途対応予定)")
            }
        ))

        inv.setItem(14, makeItem(Material.LIME_CONCRETE, "&a▶ 今すぐ実行", listOf(
            "&7このマクロを今すぐ実行します"
        )))

        inv.setItem(16, makeItem(Material.TNT, "&cマクロを削除", listOf(
            "&7このマクロを削除します。",
            "&c取り消しできません。"
        )))

        inv.setItem(49, makeItem(Material.ARROW, "&c← 戻る", listOf("&7マクロ一覧に戻ります")))

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
        val title = event.view.title()
        val titleStr = LegacyComponentSerializer.legacyAmpersand().serialize(title)

        // ---- 詳細画面 ----
        if (titleStr.contains("マクロ詳細")) {
            val macroId = getMacroIdFromTitle(titleStr, player) ?: return
            handleDetailClick(player, macroId, slot)
            return
        }

        // ---- 一覧画面 ----
        when (slot) {
            45 -> {                             // 戻る
                player.closeInventory()
                Bukkit.getScheduler().runTaskLater(plugin, Runnable {
                    plugin.menuEngine.openMenu(player, "root")
                }, 1L)
            }
            46 -> reopenList(player, -1)        // 前ページ (概算)
            49 -> startCreateMacro(player)      // 新規作成
            52 -> reopenList(player, +1)        // 次ページ
            53 -> { /* ヒント: 何もしない */ }
            in 0..44 -> {
                val macro = getMacroFromSlot(player, slot) ?: return
                when {
                    event.isRightClick -> openDetail(player, macro)
                    event.isLeftClick  -> executeMacro(player, macro)
                }
            }
        }
    }

    @EventHandler
    fun onInventoryClose(event: InventoryCloseEvent) {
        val player = event.player as? Player ?: return
        activePlayers.remove(player.uniqueId.toString())
    }

    // チャット入力を受け取る
    @EventHandler(priority = EventPriority.LOWEST)
    fun onChat(event: AsyncPlayerChatEvent) {
        val uuid = event.player.uniqueId.toString()
        val state = chatInputPlayers[uuid] ?: return
        event.isCancelled = true // チャットをサーバーに流さない

        val input = event.message.trim()
        val player = event.player

        Bukkit.getScheduler().runTask(plugin, Runnable {
            chatInputPlayers.remove(uuid)
            when (state.mode) {
                InputMode.MACRO_NAME -> {
                    if (input.isEmpty() || input.equals("cancel", ignoreCase = true)) {
                        player.sendMessage(c("&eキャンセルしました。"))
                        openMacroList(player)
                        return@Runnable
                    }
                    if (state.macroId == null) {
                        // 新規作成: 名前確定 → コマンド入力へ
                        player.sendMessage(c("&7次に、実行するコマンドを入力してください。"))
                        player.sendMessage(c("&7(/ は不要です。例: &fwarp home&7) &e「done」で完了 / 「cancel」でキャンセル"))
                        chatInputPlayers[uuid] = ChatInputState(InputMode.COMMAND_ADD, null, input)
                        // 一時マクロIDを生成して保存
                        val tmpId = "macro_${UUID.randomUUID().toString().take(8)}"
                        chatInputPlayers[uuid] = ChatInputState(InputMode.COMMAND_ADD, tmpId, input)
                        // 空のコマンドリストで仮保存
                        plugin.macroManager.addMacro(player.uniqueId, PlayerMacro(
                            id = tmpId, name = input, ownerUUID = uuid, commands = emptyList()
                        ))
                    } else {
                        // 既存マクロの名前変更
                        val macro = plugin.macroManager.getMacro(player.uniqueId, state.macroId)
                        if (macro != null) {
                            plugin.macroManager.updateMacro(player.uniqueId, macro.copy(name = input))
                            player.sendMessage(c("&aマクロ名を変更しました: &f$input"))
                        }
                        openMacroList(player)
                    }
                }

                InputMode.COMMAND_ADD -> {
                    val macroId = state.macroId ?: run { openMacroList(player); return@Runnable }
                    if (input.equals("cancel", ignoreCase = true)) {
                        // キャンセル: 仮保存マクロを削除
                        plugin.macroManager.removeMacro(player.uniqueId, macroId)
                        player.sendMessage(c("&eキャンセルしました。"))
                        openMacroList(player)
                        return@Runnable
                    }
                    if (input.equals("done", ignoreCase = true)) {
                        // 完了
                        val macro = plugin.macroManager.getMacro(player.uniqueId, macroId)
                        if (macro != null && macro.commands.isEmpty()) {
                            // コマンドが空のまま完了 → 削除
                            plugin.macroManager.removeMacro(player.uniqueId, macroId)
                            player.sendMessage(c("&cコマンドが1つも登録されていないためマクロを作成しませんでした。"))
                        } else {
                            player.sendMessage(c("&aマクロを作成しました! &e「${macro?.name}」"))
                        }
                        openMacroList(player)
                        return@Runnable
                    }
                    // コマンドを追加
                    val cmd = input.trimStart('/')
                    val macro = plugin.macroManager.getMacro(player.uniqueId, macroId)
                    if (macro != null) {
                        val err = plugin.macroManager.updateMacro(
                            player.uniqueId, macro.copy(commands = macro.commands + cmd)
                        )
                        if (err != null) {
                            player.sendMessage(c("&c$err"))
                        } else {
                            player.sendMessage(c("&7追加: &f/$cmd &7(「done」で完了 / 続けて入力可)"))
                        }
                    }
                    // 再度入力待ち
                    chatInputPlayers[uuid] = state
                }
            }
        })
    }

    // ============================
    // 操作処理
    // ============================

    private fun handleDetailClick(player: Player, macroId: String, slot: Int) {
        val macro = plugin.macroManager.getMacro(player.uniqueId, macroId) ?: run {
            openMacroList(player); return
        }
        when (slot) {
            10 -> { // 名前変更
                player.closeInventory()
                player.sendMessage(c("&7新しいマクロ名を入力してください。(&e「cancel」でキャンセル)"))
                chatInputPlayers[player.uniqueId.toString()] =
                    ChatInputState(InputMode.MACRO_NAME, macroId)
            }
            12 -> { // コマンド追加
                player.closeInventory()
                player.sendMessage(c("&7追加するコマンドを入力 (/ は不要)。「done」で完了 / 「cancel」でキャンセル"))
                player.sendMessage(c("&7現在のコマンド: &f${macro.commands.joinToString(", ")}"))
                chatInputPlayers[player.uniqueId.toString()] =
                    ChatInputState(InputMode.COMMAND_ADD, macroId)
            }
            14 -> executeMacro(player, macro) // 実行
            16 -> { // 削除
                plugin.macroManager.removeMacro(player.uniqueId, macroId)
                player.sendMessage(c("&cマクロ「${macro.name}」を削除しました。"))
                openMacroList(player)
            }
            49 -> openMacroList(player) // 戻る
        }
    }

    private fun executeMacro(player: Player, macro: PlayerMacro) {
        val err = plugin.macroManager.executeMacro(player, macro.id)
        if (err != null) {
            player.sendMessage(c("&c$err"))
        } else {
            player.sendMessage(c("&aマクロ「&e${macro.name}&a」を実行しました。"))
            player.playSound(player.location, org.bukkit.Sound.UI_BUTTON_CLICK, 1f, 1.2f)
        }
    }

    private fun openDetail(player: Player, macro: PlayerMacro) {
        val inv = buildMacroDetailInventory(player, macro)
        player.openInventory(inv)
        // activePlayers はクローズで削除されるため再登録
        activePlayers.add(player.uniqueId.toString())
    }

    private fun startCreateMacro(player: Player) {
        val macros = plugin.macroManager.getMacros(player.uniqueId)
        val maxMacros = plugin.config.getInt("macro.max-per-player", 10)
        if (macros.size >= maxMacros) {
            player.sendMessage(c("&cマクロの上限 ($maxMacros) に達しています。"))
            return
        }
        player.closeInventory()
        player.sendMessage(c("&7マクロ名を入力してください。(&e「cancel」でキャンセル)"))
        chatInputPlayers[player.uniqueId.toString()] =
            ChatInputState(InputMode.MACRO_NAME, null)
    }

    private fun reopenList(player: Player, delta: Int) {
        val title = LegacyComponentSerializer.legacyAmpersand()
            .serialize(player.openInventory.title())
        val currentPage = Regex("(\\d+)/").find(title)?.groupValues?.get(1)?.toIntOrNull()?.minus(1) ?: 0
        val newPage = (currentPage + delta).coerceAtLeast(0)
        player.closeInventory()
        Bukkit.getScheduler().runTaskLater(plugin, Runnable {
            val inv = buildMacroListInventory(player, newPage)
            player.openInventory(inv)
            activePlayers.add(player.uniqueId.toString())
        }, 1L)
    }

    // ============================
    // ユーティリティ
    // ============================

    private fun getMacroFromSlot(player: Player, slot: Int): PlayerMacro? {
        val macros = plugin.macroManager.getMacros(player.uniqueId)
        return macros.getOrNull(slot)
    }

    private fun getMacroIdFromTitle(title: String, player: Player): String? {
        // タイトルからマクロ名を取得し、IDを逆引き
        val macroName = title.substringAfter("マクロ詳細: ").trim()
            .replace("§[0-9a-fk-or]".toRegex(), "")
        return plugin.macroManager.getMacros(player.uniqueId)
            .find { it.name == macroName }?.id
    }

    private fun makeItem(mat: Material, name: String, lore: List<String> = emptyList()): ItemStack {
        val item = ItemStack(mat)
        val meta = item.itemMeta ?: return item
        meta.displayName(comp(name))
        if (lore.isNotEmpty()) meta.lore(lore.map { comp(it) })
        item.itemMeta = meta
        return item
    }

    private fun comp(text: String): Component =
        LegacyComponentSerializer.legacyAmpersand().deserialize(text)
    private fun c(text: String) = text.replace('&', '\u00A7')
}