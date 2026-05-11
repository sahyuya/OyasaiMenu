package com.github.sahyuya.oyasaiMenu.engine

import com.github.sahyuya.oyasaiMenu.OyasaiMenu
import com.github.sahyuya.oyasaiMenu.model.PlayerMacro
import com.github.sahyuya.oyasaiMenu.util.GuiUtil.c
import com.github.sahyuya.oyasaiMenu.util.GuiUtil.comp
import com.github.sahyuya.oyasaiMenu.util.GuiUtil.makeItem
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.event.ClickEvent
import net.kyori.adventure.text.event.HoverEvent
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextDecoration
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
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
import org.bukkit.event.player.PlayerEditBookEvent
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.BookMeta
import org.bukkit.scheduler.BukkitTask
import java.text.SimpleDateFormat
import java.util.Date
import java.util.UUID

/**
 * MacroEngine
 *
 * ■ 新規マクロ作成フロー (変更)
 *   旧: チャットでID名入力 → 本エディタでコマンド入力
 *   新: 本エディタでコマンド入力 → マクロ名を本の署名タイトルから取得
 *       署名タイトルなし (Done のみ) → チャットで名前入力 (20秒タイムアウト)
 *       20秒以内に入力なし → 作成失敗・キャンセル
 *
 * ■ BookEditorState
 *   macroId = null  → 新規作成
 *   macroId = "id"  → 既存マクロのコマンド編集
 */
class MacroEngine(private val plugin: OyasaiMenu) : Listener {

    private val activePlayers: MutableSet<String>          = mutableSetOf()
    private val editModeMap:   MutableMap<String, Boolean> = mutableMapOf()
    private val pageMap:       MutableMap<String, Int>     = mutableMapOf()

    /**
     * チャット入力待ち状態。
     *   macroId = null  → 新規マクロのマクロ名入力中
     *   macroId = "id"  → 既存マクロの名前変更中
     */
    private data class ChatInputState(val macroId: String?)
    private val chatInputPlayers: MutableMap<String, ChatInputState> = mutableMapOf()

    /** 本エディタの状態 (macroId = null なら新規作成) */
    private data class BookEditorState(val macroId: String?, val slot: Int)
    private val bookEditorPending: MutableMap<UUID, BookEditorState> = mutableMapOf()

    /**
     * 新規マクロ作成時、コマンドが確定してマクロ名の入力待ち状態。
     * timeoutTask をキャンセルすることでタイムアウトを中断できる。
     */
    private data class PendingMacroCommands(
        val commands: List<String>,
        val timeoutTask: BukkitTask
    )
    private val pendingMacroCreation: MutableMap<String, PendingMacroCommands> = mutableMapOf()

    private val plainText  = PlainTextComponentSerializer.plainText()
    private val dateFormat = SimpleDateFormat("MM/dd HH:mm")

    /** マクロ名のチャット入力タイムアウト秒数 */
    private val nameInputTimeoutSec = 20

    // ============================
    // マクロ一覧を開く
    // ============================

    fun openMacroList(player: Player) {
        if (!player.hasPermission("oyasaimenu.macro")) {
            player.sendMessage(c("&cこのコマンドを使う権限がありません。")); return
        }
        val page = pageMap[player.uniqueId.toString()] ?: 0
        player.openInventory(buildMacroListInventory(player, page))
        activePlayers.add(player.uniqueId.toString())
    }

    // ============================
    // 本エディタ — 新規作成
    // ============================

    /**
     * 新規マクロ作成用の本エディタを開く。
     * macroId = null として保存し、PlayerEditBookEvent で判別する。
     */
    private fun openBookEditorForNew(player: Player) {
        val book = ItemStack(Material.WRITABLE_BOOK)
        val meta = book.itemMeta as BookMeta
        meta.addPage(
            "コマンドを1行ずつ入力\n\n" +
            "ゲームと同じ構文で登録:\n" +
            "  /warp home\n" +
            "  //wand (FAWE)\n" +
            "  //set stone\n\n" +
            "待機: wait 1s / wait 0.5s\n\n" +
            "★ マクロ名の決め方:\n" +
            "署名 → 署名タイトルが名前\n" +
            "Done → チャットで20秒以内に入力\n\n" +
            "Done で確定"
        )
        book.itemMeta = meta
        val slot = player.inventory.firstEmpty().takeIf { it >= 0 } ?: 8
        player.inventory.setItem(slot, book)
        player.inventory.heldItemSlot = slot
        bookEditorPending[player.uniqueId] = BookEditorState(macroId = null, slot = slot)
        player.sendMessage(c("&b=== 新規マクロ作成 ==="))
        player.sendMessage(c("&7コマンドを入力してください。"))
        player.sendMessage(c("&7署名タイトル → マクロ名 / Done → チャットで入力 &e(${nameInputTimeoutSec}秒以内)"))
    }

    // ============================
    // 本エディタ — 既存マクロ編集
    // ============================

    private fun openBookEditorForEdit(player: Player, macroId: String) {
        val book = ItemStack(Material.WRITABLE_BOOK)
        val meta = book.itemMeta as BookMeta
        val macro = plugin.macroManager.getMacro(player.uniqueId, macroId)
        if (macro != null && macro.commands.isNotEmpty()) {
            splitIntoPages(macro.commands.joinToString("\n"), 254).forEach { meta.addPage(it) }
        } else {
            meta.addPage(
                "コマンドを1行ずつ入力\n\n" +
                "ゲームと同じ構文で登録:\n" +
                "  /warp home\n" +
                "  //wand (FAWE)\n\n" +
                "待機: wait 1s / wait 0.5s\n\n" +
                "Done で確定"
            )
        }
        book.itemMeta = meta
        val slot = player.inventory.firstEmpty().takeIf { it >= 0 } ?: 8
        player.inventory.setItem(slot, book)
        player.inventory.heldItemSlot = slot
        bookEditorPending[player.uniqueId] = BookEditorState(macroId = macroId, slot = slot)
        player.sendMessage(c("&b=== マクロ本エディタ ===  Done で確定"))
    }

    // ============================
    // PlayerEditBookEvent
    // ============================

    @EventHandler
    fun onPlayerEditBook(event: PlayerEditBookEvent) {
        val uuid    = event.player.uniqueId
        val edState = bookEditorPending.remove(uuid) ?: return
        val meta    = event.newBookMeta

        // 書籍内容からコマンドリストを抽出
        val commands = meta.pages()
            .joinToString("\n") { plainText.serialize(it) }
            .split("\n")
            .map { it.trim() }
            .filter { it.isNotEmpty() }

        if (edState.macroId == null) {
            // ===== 新規作成 =====
            // 署名はキャンセルして署名済み本にしない (タイトルだけ使う)
            if (event.isSigning) event.isCancelled = true
            removeBook(event.player, edState.slot)

            if (commands.isEmpty()) {
                event.player.sendMessage(c("&cコマンドが空のためマクロ作成を中止しました。"))
                return
            }

            // 署名タイトルが設定されていればそのままマクロ名に使う
            val titleFromSign: String? = if (event.isSigning) {
                meta.title()?.let { plainText.serialize(it) }?.takeIf { it.isNotBlank() }
            } else null

            if (titleFromSign != null) {
                createNewMacro(event.player, titleFromSign, commands)
            } else {
                // タイトルなし / Done → チャットでマクロ名を入力させる
                startNameInputWithTimeout(event.player, commands)
            }

        } else {
            // ===== 既存マクロ編集 =====
            if (event.isSigning) event.isCancelled = true
            removeBook(event.player, edState.slot)

            if (commands.isEmpty()) {
                event.player.sendMessage(c("&cコマンドが空です。変更を破棄しました。")); return
            }
            val macro = plugin.macroManager.getMacro(event.player.uniqueId, edState.macroId)
                ?: run { event.player.sendMessage(c("&cマクロが見つかりません。")); return }
            if (commands == macro.commands) {
                event.player.sendMessage(c("&7変更がなかったため保存しませんでした。")); return
            }
            val err = plugin.macroManager.updateMacro(event.player.uniqueId, macro.copy(commands = commands), event.player)
            if (err != null) {
                event.player.sendMessage(c("&c$err"))
            } else {
                event.player.sendMessage(c("&aマクロ「&e${macro.name}&a」を更新しました。&f${commands.size}&a個"))
                commands.take(5).forEachIndexed { i, cmd ->
                    event.player.sendMessage(c("  &7${i+1}. ${if (plugin.macroManager.isWaitCommand(cmd)) "⏱ $cmd" else cmd}"))
                }
                if (commands.size > 5) event.player.sendMessage(c("  &7...他 ${commands.size - 5} 個"))
            }
        }
    }

    /**
     * コマンドを保持してチャットでマクロ名の入力を待つ。
     * [nameInputTimeoutSec] 秒以内に入力がなければ自動キャンセル。
     */
    private fun startNameInputWithTimeout(player: Player, commands: List<String>) {
        val uid = player.uniqueId.toString()

        // 既存の待機があれば先にキャンセル
        pendingMacroCreation.remove(uid)?.timeoutTask?.cancel()

        val timeoutTask = Bukkit.getScheduler().runTaskLater(plugin, Runnable {
            if (pendingMacroCreation.remove(uid) != null) {
                chatInputPlayers.remove(uid)
                player.sendMessage(c("&c${nameInputTimeoutSec}秒以内に入力がなかったためマクロ作成を中止しました。"))
            }
        }, (nameInputTimeoutSec * 20).toLong())

        pendingMacroCreation[uid] = PendingMacroCommands(commands, timeoutTask)
        chatInputPlayers[uid]     = ChatInputState(macroId = null)

        player.sendMessage(c("&7マクロ名をチャットで入力してください。&e(${nameInputTimeoutSec}秒以内)"))
        player.sendMessage(c("&7キャンセル: &f「cancel」と入力"))
    }

    /** 新規マクロを実際に作成する共通ヘルパー */
    private fun createNewMacro(player: Player, name: String, commands: List<String>) {
        val uid      = player.uniqueId.toString()
        val safeName = name.lowercase().replace(Regex("[^a-z0-9_\\-]"), "_").take(32)
        val baseId   = "${player.name.lowercase()}_${safeName}"
        val existing = plugin.macroManager.getMacros(player.uniqueId).map { it.id }.toSet()
        val newId    = generateUniqueId(baseId, existing)

        val err = plugin.macroManager.addMacro(
            player.uniqueId,
            PlayerMacro(id = newId, name = name, ownerUUID = uid, commands = commands),
            plugin.macroManager.getMaxMacros(player),
            player
        )
        if (err != null) {
            player.sendMessage(c("&c作成失敗: $err"))
        } else {
            player.sendMessage(c("&aマクロ「&e$name&a」を作成しました。 &8(ID: &f$newId&8)"))
            player.sendMessage(c("&7コマンド数: &f${commands.size}個"))
        }
        openMacroList(player)
    }

    /**
     * 指定スロットの本をインベントリから確実に削除する。
     * Paper の書籍処理が完了するまで 3 tick 待つ。
     */
    private fun removeBook(player: Player, slot: Int) {
        plugin.server.scheduler.runTaskLater(plugin, Runnable {
            val atSlot = player.inventory.getItem(slot)
            if (atSlot != null && (atSlot.type == Material.WRITABLE_BOOK || atSlot.type == Material.WRITTEN_BOOK)) {
                player.inventory.setItem(slot, null)
                return@Runnable
            }
            // フォールバック: 全スキャン
            for (i in 0 until player.inventory.size) {
                val item = player.inventory.getItem(i) ?: continue
                if (item.type == Material.WRITABLE_BOOK || item.type == Material.WRITTEN_BOOK) {
                    player.inventory.setItem(i, null); break
                }
            }
        }, 3L)
    }

    // ============================
    // インベントリ構築
    // ============================

    private fun buildMacroListInventory(player: Player, page: Int): Inventory {
        val uid        = player.uniqueId.toString()
        val macros     = plugin.macroManager.getMacros(player.uniqueId)
        val maxPerPage = 45
        val pageCount  = maxOf(1, (macros.size + maxPerPage - 1) / maxPerPage)
        val curPage    = page.coerceIn(0, pageCount - 1)
        val isEditMode = editModeMap[uid] ?: false
        pageMap[uid]   = curPage

        val inv = Bukkit.createInventory(null, 54, comp("&6コマンドマクロ &8| &f${curPage + 1}&8/&f${pageCount}"))

        macros.drop(curPage * maxPerPage).take(maxPerPage).forEachIndexed { i, macro ->
            inv.setItem(i, buildMacroItem(macro, isEditMode))
        }

        if (curPage > 0)
            inv.setItem(46, makeItem(Material.ARROW, "&e← 前のページ", listOf("&7ページ &f${curPage}&7/&f${pageCount}")))
        if (curPage < pageCount - 1)
            inv.setItem(52, makeItem(Material.ARROW, "&e次のページ →", listOf("&7ページ &f${curPage + 2}&7/&f${pageCount}")))

        inv.setItem(45, makeItem(Material.ARROW, "&c← 戻る", listOf("&7メインメニューに戻ります")))

        val maxMacros = plugin.macroManager.getMaxMacros(player)
        val maxCmds   = plugin.macroManager.getMaxCommands(player)
        val canAdd    = macros.size < maxMacros
        inv.setItem(49, makeItem(
            if (canAdd) Material.NETHER_STAR else Material.GRAY_STAINED_GLASS_PANE,
            if (canAdd) "&a+ 新しいマクロを作成" else "&7マクロ上限 ($maxMacros) に達しています",
            listOf("&7現在: &f${macros.size} &7/ &f$maxMacros", "&7コマンド上限: &f$maxCmds 個/マクロ")
        ))

        if (!isEditMode) {
            inv.setItem(53, makeItem(Material.BOOK, "&7クリック: &a実行モード",
                listOf("&a● 左クリック → 実行", "&7  右クリック → 詳細・編集", "", "&eクリックで &b編集モード &eに切替")))
        } else {
            inv.setItem(53, makeItem(Material.WRITABLE_BOOK, "&7クリック: &b編集モード",
                listOf("&b● 左クリック → 詳細・編集", "&7  右クリック → 実行", "", "&eクリックで &a実行モード &eに切替")))
        }
        return inv
    }

    private fun buildMacroItem(macro: PlayerMacro, isEditMode: Boolean): ItemStack {
        val clickHint = if (!isEditMode)
            listOf("&e左クリック &7→ 実行  &7右クリック → 詳細・編集")
        else
            listOf("&b左クリック &7→ 詳細・編集  &7右クリック → 実行")

        val item = makeItem(Material.COMMAND_BLOCK, "&e${macro.name}", buildList {
            add("&8ID: ${macro.id}")
            add("&7コマンド (${macro.commands.size} 個):")
            macro.commands.take(5).forEach { cmd ->
                add(if (plugin.macroManager.isWaitCommand(cmd)) "&8  ⏱ $cmd" else "&8  $cmd")
            }
            if (macro.commands.size > 5) add("&8  ...他 ${macro.commands.size - 5} 個")
            add(""); add("&eCooldown: &f${macro.cooldownSeconds}秒"); add("")
            addAll(clickHint)
        })
        val meta = item.itemMeta!!
        meta.persistentDataContainer.set(
            org.bukkit.NamespacedKey(plugin, "macro_id"),
            org.bukkit.persistence.PersistentDataType.STRING, macro.id
        )
        item.itemMeta = meta; return item
    }

    private fun buildMacroDetailInventory(player: Player, macro: PlayerMacro): Inventory {
        val inv = Bukkit.createInventory(null, 54, comp("&6マクロ詳細: &e${macro.name}"))

        val existingShares = plugin.sharedMacroManager.findSharesForMacro(player.uniqueId, macro.name)
        val shareLore = buildList {
            add("&7このマクロを他プレイヤーに共有します")
            if (existingShares.isNotEmpty()) {
                add(""); add("&b既存の共有ID (${existingShares.size}件):")
                existingShares.take(3).forEach { share ->
                    add("&f  ${share.shareId}  &8[${dateFormat.format(Date(share.createdAt))}]")
                }
                if (existingShares.size > 3) add("&8  ...他 ${existingShares.size - 3} 件")
                add(""); add("&eクリックで既存IDを再表示 + 新しいIDを発行")
            } else {
                add(""); add("&eクリックで共有IDを発行")
            }
        }

        inv.setItem(10, makeItem(Material.NAME_TAG, "&a名前を変更",
            listOf("&f${macro.name}", "&8ID: ${macro.id}", "", "&eクリックしてチャットで入力")))
        inv.setItem(12, makeItem(Material.WRITABLE_BOOK, "&b本でコマンドを編集", buildList {
            add("&7現在のコマンド (${macro.commands.size} 個):")
            macro.commands.take(5).forEachIndexed { i, cmd ->
                add(if (plugin.macroManager.isWaitCommand(cmd)) "&8  ${i+1}. ⏱ $cmd" else "&8  ${i+1}. $cmd")
            }
            if (macro.commands.size > 5) add("&8  ...他 ${macro.commands.size - 5} 個")
            add(""); add("&eクリックで本エディタを開く")
        }))
        inv.setItem(14, makeItem(Material.LIME_CONCRETE, "&a▶ 今すぐ実行", listOf("&7このマクロを今すぐ実行します")))
        inv.setItem(15, makeItem(Material.PAPER, "&b共有IDを発行", shareLore))
        inv.setItem(16, makeItem(Material.TNT, "&cマクロを削除", listOf("&7このマクロを削除します。", "&c取り消しできません。")))
        inv.setItem(49, makeItem(Material.ARROW, "&c← 戻る", listOf("&7マクロ一覧に戻ります")))
        return inv
    }

    // ============================
    // イベントハンドラ
    // ============================

    @EventHandler
    fun onInventoryClick(event: InventoryClickEvent) {
        val player = event.whoClicked as? Player ?: return
        val uid    = player.uniqueId.toString()
        if (!activePlayers.contains(uid)) return
        if (event.clickedInventory == player.inventory) { if (event.isShiftClick) event.isCancelled = true; return }
        event.isCancelled = true

        val slot     = event.rawSlot
        val titleStr = LegacyComponentSerializer.legacyAmpersand().serialize(event.view.title())

        if (titleStr.contains("マクロ詳細")) {
            val macroId = getMacroIdFromTitle(titleStr, player)
                ?: run { plugin.logger.warning("MacroEngine: ID逆引き失敗 '$titleStr'"); return }
            handleDetailClick(player, macroId, slot); return
        }

        val isEditMode = editModeMap[uid] ?: false
        when (slot) {
            45 -> {
                player.closeInventory()
                Bukkit.getScheduler().runTaskLater(plugin, Runnable { plugin.menuEngine.openMenu(player, "root") }, 1L)
            }
            46 -> changePage(player, -1)
            49 -> startCreateMacro(player)
            52 -> changePage(player, +1)
            53 -> {
                editModeMap[uid] = !isEditMode
                player.openInventory(buildMacroListInventory(player, pageMap[uid] ?: 0))
                activePlayers.add(uid)
            }
            in 0..44 -> {
                val macros  = plugin.macroManager.getMacros(player.uniqueId)
                val curPage = pageMap[uid] ?: 0
                val macro   = macros.getOrNull(curPage * 45 + slot) ?: return
                when {
                    !isEditMode && event.isLeftClick  -> executeMacro(player, macro)
                    !isEditMode && event.isRightClick -> openDetail(player, macro)
                    isEditMode  && event.isLeftClick  -> openDetail(player, macro)
                    isEditMode  && event.isRightClick -> executeMacro(player, macro)
                }
            }
        }
    }

    @EventHandler
    fun onInventoryClose(event: InventoryCloseEvent) {
        val uid   = (event.player as? Player)?.uniqueId?.toString() ?: return
        activePlayers.remove(uid)
        val title = LegacyComponentSerializer.legacyAmpersand().serialize(event.view.title())
        if (!title.contains("マクロ詳細") && !title.contains("コマンドマクロ")) {
            editModeMap.remove(uid); pageMap.remove(uid)
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    fun onChat(event: AsyncPlayerChatEvent) {
        val uid   = event.player.uniqueId.toString()
        val state = chatInputPlayers[uid] ?: return
        event.isCancelled = true
        val input  = event.message.trim()
        val player = event.player

        Bukkit.getScheduler().runTask(plugin, Runnable {
            chatInputPlayers.remove(uid)

            if (state.macroId == null) {
                // ===== 新規マクロのマクロ名入力 =====
                val pending = pendingMacroCreation.remove(uid)
                if (pending == null) {
                    // タイムアウト済み — timeoutTask が既にキャンセル処理を行った
                    return@Runnable
                }
                pending.timeoutTask.cancel()

                if (input.isEmpty() || input.equals("cancel", ignoreCase = true)) {
                    player.sendMessage(c("&eマクロ作成をキャンセルしました。"))
                    openMacroList(player)
                    return@Runnable
                }

                createNewMacro(player, input, pending.commands)

            } else {
                // ===== 既存マクロの名前変更 =====
                if (input.isEmpty() || input.equals("cancel", ignoreCase = true)) {
                    player.sendMessage(c("&eキャンセルしました。")); openMacroList(player); return@Runnable
                }
                val macro = plugin.macroManager.getMacro(player.uniqueId, state.macroId)
                if (macro != null) {
                    plugin.macroManager.updateMacro(player.uniqueId, macro.copy(name = input))
                    player.sendMessage(c("&aマクロ名を変更しました: &f$input"))
                }
                openMacroList(player)
            }
        })
    }

    // ============================
    // 操作ハンドラ
    // ============================

    private fun handleDetailClick(player: Player, macroId: String, slot: Int) {
        val macro = plugin.macroManager.getMacro(player.uniqueId, macroId)
            ?: run { openMacroList(player); return }
        when (slot) {
            10 -> {
                player.closeInventory()
                player.sendMessage(c("&7新しいマクロ名を入力してください。(&e「cancel」でキャンセル)"))
                chatInputPlayers[player.uniqueId.toString()] = ChatInputState(macroId = macroId)
            }
            12 -> {
                player.closeInventory()
                Bukkit.getScheduler().runTaskLater(plugin, Runnable { openBookEditorForEdit(player, macroId) }, 1L)
            }
            14 -> executeMacro(player, macro)
            15 -> handleShare(player, macro)
            16 -> {
                plugin.macroManager.removeMacro(player.uniqueId, macroId)
                player.sendMessage(c("&cマクロ「${macro.name}」を削除しました。"))
                openMacroList(player)
            }
            49 -> openMacroList(player)
        }
    }

    private fun handleShare(player: Player, macro: PlayerMacro) {
        if (macro.commands.isEmpty()) { player.sendMessage(c("&cコマンドが空のマクロは共有できません。")); return }

        val existing = plugin.sharedMacroManager.findSharesForMacro(player.uniqueId, macro.name)
        if (existing.isNotEmpty()) {
            player.sendMessage(comp("&b--- 既存の共有ID (${macro.name}) ---"))
            existing.take(5).forEach { share ->
                player.sendMessage(
                    Component.text("  ").decoration(TextDecoration.ITALIC, false)
                        .append(buildIdComponent(share.shareId))
                        .append(comp("  &8[${dateFormat.format(Date(share.createdAt))}]"))
                )
            }
            if (existing.size > 5) player.sendMessage(comp("  &8...他 ${existing.size - 5} 件"))
            player.sendMessage(comp("&7新しいIDも発行しました:"))
        }

        val shareId = plugin.sharedMacroManager.publishMacro(macro, player.name, player.uniqueId)
            ?: run { player.sendMessage(c("&c共有IDの発行に失敗しました。")); return }

        player.sendMessage(
            Component.text("").decoration(TextDecoration.ITALIC, false)
                .append(comp("&a新しい共有ID: "))
                .append(buildIdComponent(shareId))
                .append(comp("  &7(クリックでコピー)"))
        )
        player.sendMessage(comp("&7相手に &f/macro import $shareId &7と伝えてください。"))
        player.sendMessage(comp("&7過去のID一覧: &f/macro shares"))
        player.playSound(player.location, org.bukkit.Sound.ENTITY_PLAYER_LEVELUP, 0.5f, 1.5f)

        Bukkit.getScheduler().runTaskLater(plugin, Runnable {
            if (activePlayers.contains(player.uniqueId.toString())) {
                player.openInventory(buildMacroDetailInventory(player, macro))
                activePlayers.add(player.uniqueId.toString())
            }
        }, 2L)
    }

    private fun buildIdComponent(shareId: String): Component =
        Component.text(shareId)
            .color(NamedTextColor.GOLD)
            .decoration(TextDecoration.ITALIC, false)
            .decoration(TextDecoration.BOLD, true)
            .clickEvent(ClickEvent.copyToClipboard(shareId))
            .hoverEvent(HoverEvent.showText(
                Component.text("クリックでコピー: $shareId")
                    .color(NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false)
            ))

    private fun executeMacro(player: Player, macro: PlayerMacro) {
        val err = plugin.macroManager.executeMacro(player, macro.id)
        if (err != null) player.sendMessage(c("&c$err"))
        else {
            player.sendMessage(c("&aマクロ「&e${macro.name}&a」を実行しました。"))
            player.playSound(player.location, org.bukkit.Sound.UI_BUTTON_CLICK, 1f, 1.2f)
        }
    }

    private fun openDetail(player: Player, macro: PlayerMacro) {
        player.openInventory(buildMacroDetailInventory(player, macro))
        activePlayers.add(player.uniqueId.toString())
    }

    /** 新規マクロ作成開始 — 本エディタを直接開く (名前は後で決める) */
    private fun startCreateMacro(player: Player) {
        val macros    = plugin.macroManager.getMacros(player.uniqueId)
        val maxMacros = plugin.macroManager.getMaxMacros(player)
        if (macros.size >= maxMacros) {
            player.sendMessage(c("&cマクロの上限 ($maxMacros) に達しています。")); return
        }
        player.closeInventory()
        Bukkit.getScheduler().runTaskLater(plugin, Runnable { openBookEditorForNew(player) }, 1L)
    }

    private fun changePage(player: Player, delta: Int) {
        val uid       = player.uniqueId.toString()
        val macros    = plugin.macroManager.getMacros(player.uniqueId)
        val pageCount = maxOf(1, (macros.size + 44) / 45)
        val curPage   = pageMap[uid] ?: 0
        val newPage   = (curPage + delta).coerceIn(0, pageCount - 1)
        if (newPage == curPage) return
        pageMap[uid] = newPage
        player.openInventory(buildMacroListInventory(player, newPage))
        activePlayers.add(uid)
    }

    // ============================
    // ユーティリティ
    // ============================

    private fun getMacroIdFromTitle(title: String, player: Player): String? {
        val macroName = title.substringAfter("マクロ詳細: ").trim()
            .replace("&[0-9a-fk-orA-FK-OR]".toRegex(), "")
        return plugin.macroManager.getMacros(player.uniqueId).find { it.name == macroName }?.id
    }

    private fun generateUniqueId(baseId: String, existing: Set<String>): String {
        if (!existing.contains(baseId)) return baseId
        var counter = 2
        while (existing.contains("${baseId}_$counter")) counter++
        return "${baseId}_$counter"
    }

    private fun splitIntoPages(text: String, maxChars: Int): List<String> {
        if (text.length <= maxChars) return listOf(text)
        val pages = mutableListOf<String>()
        var remaining = text
        while (remaining.isNotEmpty()) {
            if (remaining.length <= maxChars) { pages.add(remaining); break }
            val cutAt = remaining.lastIndexOf('\n', maxChars - 1).takeIf { it > 0 } ?: maxChars
            pages.add(remaining.substring(0, cutAt))
            remaining = remaining.substring(cutAt).trimStart('\n')
        }
        return pages
    }
}