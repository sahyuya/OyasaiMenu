package com.github.sahyuya.oyasaiMenu.engine

import com.github.sahyuya.oyasaiMenu.OyasaiMenu
import com.github.sahyuya.oyasaiMenu.model.PlayerMacro
import com.github.sahyuya.oyasaiMenu.util.GuiUtil.c
import com.github.sahyuya.oyasaiMenu.util.GuiUtil.comp
import com.github.sahyuya.oyasaiMenu.util.GuiUtil.makeItem
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
import java.util.UUID

/**
 * MacroEngine
 *
 * 変更点:
 *   - マクロID フォーマットを "<mcid>_<マクロ名>" に変更
 *   - italic=false を comp() に追加
 */
class MacroEngine(private val plugin: OyasaiMenu) : Listener {

    private val activePlayers: MutableSet<String> = mutableSetOf()

    private data class ChatInputState(val mode: InputMode, val macroId: String?, val macroName: String? = null)
    private enum class InputMode { MACRO_NAME, COMMAND_ADD }
    private val chatInputPlayers: MutableMap<String, ChatInputState> = mutableMapOf()
    private val bookEditorPending: MutableMap<UUID, String> = mutableMapOf()

    private val plainText = PlainTextComponentSerializer.plainText()

    fun openMacroList(player: Player) {
        val inv = buildMacroListInventory(player, 0)
        player.openInventory(inv)
        activePlayers.add(player.uniqueId.toString())
    }

    // ============================
    // 本エディタ
    // ============================

    private fun openBookEditor(player: Player, macroId: String) {
        val book = ItemStack(Material.WRITABLE_BOOK)
        val meta = book.itemMeta as BookMeta
        val macro = plugin.macroManager.getMacro(player.uniqueId, macroId)
        if (macro != null && macro.commands.isNotEmpty()) {
            val content = macro.commands.joinToString("\n") { if (it.startsWith("/")) "/$it" else it }
            splitIntoPages(content, 254).forEach { meta.addPage(it) }
        } else {
            meta.addPage("コマンドを1行ずつ入力\n例:\n//wand\nwait 1s\n//pos1\nwait 0.5s\nwarp home")
        }
        book.itemMeta = meta
        val slot = player.inventory.firstEmpty().takeIf { it >= 0 } ?: 8
        player.inventory.setItem(slot, book)
        player.inventory.heldItemSlot = slot
        bookEditorPending[player.uniqueId] = macroId
        player.sendMessage(c("&b=== マクロ本エディタ ==="))
        player.sendMessage(c("&71行 = 1コマンド (ページをまたいでOK)"))
        player.sendMessage(c("&7FAWE: &f//wand &7のように入力 (// のまま書く)"))
        player.sendMessage(c("&7待機: &fwait 1s &7/ &fwait 0.5s"))
        player.sendMessage(c("&7Done で確定"))
    }

    @EventHandler
    fun onPlayerEditBook(event: PlayerEditBookEvent) {
        val uuid    = event.player.uniqueId
        val macroId = bookEditorPending.remove(uuid) ?: return
        val meta    = event.newBookMeta
        val commands = meta.pages()
            .joinToString("\n") { plainText.serialize(it) }
            .split("\n").map { it.trim() }.filter { it.isNotEmpty() }
            .map { if (plugin.macroManager.isWaitCommand(it)) it else it.removePrefix("/") }

        if (commands.isEmpty()) {
            event.player.sendMessage(c("&cコマンドが空です。キャンセルしました。"))
            removeBookFromInventory(event.player); return
        }
        val macro = plugin.macroManager.getMacro(event.player.uniqueId, macroId)
        if (macro == null) {
            event.player.sendMessage(c("&cマクロ '${macroId}' が見つかりません。"))
            removeBookFromInventory(event.player); return
        }
        val err = plugin.macroManager.updateMacro(event.player.uniqueId, macro.copy(commands = commands))
        if (err != null) {
            event.player.sendMessage(c("&c$err"))
        } else {
            event.player.sendMessage(c("&aマクロ「&e${macro.name}&a」を更新しました。&f${commands.size}&a個"))
            commands.take(5).forEachIndexed { i, cmd ->
                if (plugin.macroManager.isWaitCommand(cmd)) event.player.sendMessage(c("  &7${i+1}. ⏱ $cmd"))
                else event.player.sendMessage(c("  &7${i+1}. /${cmd}"))
            }
            if (commands.size > 5) event.player.sendMessage(c("  &7...他 ${commands.size - 5} 個"))
        }
        removeBookFromInventory(event.player)
        if (event.isSigning) event.isCancelled = true
    }

    private fun removeBookFromInventory(player: Player) {
        plugin.server.scheduler.runTaskLater(plugin, Runnable {
            val inv = player.inventory
            for (i in 0 until inv.size) {
                val item = inv.getItem(i) ?: continue
                if (item.type == Material.WRITABLE_BOOK || item.type == Material.WRITTEN_BOOK) { inv.setItem(i, null); break }
            }
        }, 1L)
    }

    // ============================
    // インベントリ構築
    // ============================

    private fun buildMacroListInventory(player: Player, page: Int): Inventory {
        val macros     = plugin.macroManager.getMacros(player.uniqueId)
        val maxPerPage = 45
        val pageCount  = maxOf(1, (macros.size + maxPerPage - 1) / maxPerPage)
        val curPage    = page.coerceIn(0, pageCount - 1)
        val inv = Bukkit.createInventory(null, 54, comp("&6コマンドマクロ &8| &f${curPage + 1}/${pageCount}"))
        macros.drop(curPage * maxPerPage).take(maxPerPage).forEachIndexed { i, macro -> inv.setItem(i, buildMacroItem(macro)) }
        inv.setItem(45, makeItem(Material.ARROW, "&c← 戻る", listOf("&7メインメニューに戻ります")))
        if (curPage > 0)             inv.setItem(46, makeItem(Material.ARROW, "&e← 前ページ"))
        if (curPage < pageCount - 1) inv.setItem(52, makeItem(Material.ARROW, "&e次ページ →"))
        val maxMacros = plugin.config.getInt("macro.max-per-player", 10)
        val canAdd    = macros.size < maxMacros
        inv.setItem(49, makeItem(
            if (canAdd) Material.NETHER_STAR else Material.GRAY_STAINED_GLASS_PANE,
            if (canAdd) "&a+ 新しいマクロを作成" else "&7マクロ上限 ($maxMacros) に達しています",
            listOf("&7現在: &f${macros.size} &7/ &f$maxMacros")
        ))
        inv.setItem(53, makeItem(Material.BOOK, "&7ヒント",
            listOf("&e左クリック &7→ 実行", "&e右クリック &7→ 詳細・編集", "", "&7ID形式: &f<mcid>_<マクロ名>")))
        return inv
    }

    private fun buildMacroItem(macro: PlayerMacro): ItemStack {
        val item = makeItem(Material.COMMAND_BLOCK, "&e${macro.name}", buildList {
            add("&8ID: ${macro.id}")
            add("&7コマンド (${macro.commands.size} 個):")
            macro.commands.take(5).forEach { cmd ->
                if (plugin.macroManager.isWaitCommand(cmd)) add("&8  ⏱ $cmd") else add("&8  /${cmd}")
            }
            if (macro.commands.size > 5) add("&8  ...他 ${macro.commands.size - 5} 個")
            add(""); add("&eCooldown: &f${macro.cooldownSeconds}秒"); add("")
            add("&e左クリック &7→ 実行  &e右クリック &7→ 編集")
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
        inv.setItem(10, makeItem(Material.NAME_TAG, "&a名前を変更",
            listOf("&f${macro.name}", "&8ID: ${macro.id}", "", "&eクリックしてチャットで入力")))
        inv.setItem(12, makeItem(Material.WRITABLE_BOOK, "&b本でコマンドを編集", buildList {
            add("&7現在のコマンド (${macro.commands.size} 個):")
            macro.commands.take(5).forEachIndexed { i, cmd ->
                if (plugin.macroManager.isWaitCommand(cmd)) add("&8  ${i+1}. ⏱ $cmd") else add("&8  ${i+1}. /${cmd}")
            }
            if (macro.commands.size > 5) add("&8  ...他 ${macro.commands.size - 5} 個")
            add(""); add("&eクリックで本エディタを開く")
        }))
        inv.setItem(14, makeItem(Material.LIME_CONCRETE, "&a▶ 今すぐ実行", listOf("&7このマクロを今すぐ実行します")))
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
        if (!activePlayers.contains(player.uniqueId.toString())) return
        if (event.clickedInventory == player.inventory) { if (event.isShiftClick) event.isCancelled = true; return }
        event.isCancelled = true
        val slot     = event.rawSlot
        val titleStr = LegacyComponentSerializer.legacyAmpersand().serialize(event.view.title())
        if (titleStr.contains("マクロ詳細")) {
            val macroId = getMacroIdFromTitle(titleStr, player) ?: run {
                plugin.logger.warning("MacroEngine: ID逆引き失敗 title='$titleStr'"); return
            }
            handleDetailClick(player, macroId, slot); return
        }
        when (slot) {
            45 -> { player.closeInventory(); Bukkit.getScheduler().runTaskLater(plugin, Runnable { plugin.menuEngine.openMenu(player, "root") }, 1L) }
            46 -> reopenList(player, -1)
            49 -> startCreateMacro(player)
            52 -> reopenList(player, +1)
            in 0..44 -> {
                val macro = getMacroFromSlot(player, slot) ?: return
                when { event.isRightClick -> openDetail(player, macro); event.isLeftClick -> executeMacro(player, macro) }
            }
        }
    }

    @EventHandler
    fun onInventoryClose(event: InventoryCloseEvent) {
        activePlayers.remove((event.player as? Player)?.uniqueId?.toString() ?: return)
    }

    @EventHandler(priority = EventPriority.LOWEST)
    fun onChat(event: AsyncPlayerChatEvent) {
        val uuid  = event.player.uniqueId.toString()
        val state = chatInputPlayers[uuid] ?: return
        event.isCancelled = true
        val input  = event.message.trim()
        val player = event.player
        Bukkit.getScheduler().runTask(plugin, Runnable {
            chatInputPlayers.remove(uuid)
            when (state.mode) {
                InputMode.MACRO_NAME -> {
                    if (input.isEmpty() || input.equals("cancel", ignoreCase = true)) {
                        player.sendMessage(c("&eキャンセルしました。")); openMacroList(player); return@Runnable
                    }
                    if (state.macroId == null) {
                        // ★ ID を "<mcid>_<マクロ名>" 形式で生成
                        val mcid     = player.name.lowercase()
                        val safeName = input.lowercase().replace(Regex("[^a-z0-9_\\-]"), "_").take(32)
                        val baseId   = "${mcid}_${safeName}"
                        val existingIds = plugin.macroManager.getMacros(player.uniqueId).map { it.id }.toSet()
                        val newId = generateUniqueId(baseId, existingIds)
                        plugin.macroManager.addMacro(player.uniqueId, PlayerMacro(
                            id = newId, name = input, ownerUUID = uuid, commands = emptyList()
                        ))
                        player.sendMessage(c("&7マクロ名: &f$input &8(ID: $newId)"))
                        player.sendMessage(c("&7次に、本エディタでコマンドを入力します。"))
                        Bukkit.getScheduler().runTaskLater(plugin, Runnable { openBookEditor(player, newId) }, 1L)
                    } else {
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
                        plugin.macroManager.removeMacro(player.uniqueId, macroId)
                        player.sendMessage(c("&eキャンセルしました。")); openMacroList(player); return@Runnable
                    }
                    if (input.equals("done", ignoreCase = true)) {
                        val macro = plugin.macroManager.getMacro(player.uniqueId, macroId)
                        if (macro != null && macro.commands.isEmpty()) {
                            plugin.macroManager.removeMacro(player.uniqueId, macroId)
                            player.sendMessage(c("&cコマンドが空のため作成しませんでした。"))
                        } else {
                            player.sendMessage(c("&aマクロ「&e${macro?.name}&a」を作成しました。"))
                        }
                        openMacroList(player); return@Runnable
                    }
                    val normalized = if (plugin.macroManager.isWaitCommand(input)) input.trim() else input.trim().removePrefix("/")
                    val macro = plugin.macroManager.getMacro(player.uniqueId, macroId)
                    if (macro != null) {
                        val err = plugin.macroManager.updateMacro(player.uniqueId, macro.copy(commands = macro.commands + normalized))
                        if (err != null) player.sendMessage(c("&c$err"))
                        else player.sendMessage(c("&7追加: &f/${normalized} &7(「done」で完了)"))
                    }
                    chatInputPlayers[uuid] = state
                }
            }
        })
    }

    // ============================
    // 操作
    // ============================

    private fun handleDetailClick(player: Player, macroId: String, slot: Int) {
        val macro = plugin.macroManager.getMacro(player.uniqueId, macroId) ?: run { openMacroList(player); return }
        when (slot) {
            10 -> { player.closeInventory(); player.sendMessage(c("&7新しいマクロ名を入力してください。(&e「cancel」でキャンセル)")); chatInputPlayers[player.uniqueId.toString()] = ChatInputState(InputMode.MACRO_NAME, macroId) }
            12 -> { player.closeInventory(); Bukkit.getScheduler().runTaskLater(plugin, Runnable { openBookEditor(player, macroId) }, 1L) }
            14 -> executeMacro(player, macro)
            16 -> { plugin.macroManager.removeMacro(player.uniqueId, macroId); player.sendMessage(c("&cマクロ「${macro.name}」を削除しました。")); openMacroList(player) }
            49 -> openMacroList(player)
        }
    }

    private fun executeMacro(player: Player, macro: PlayerMacro) {
        val err = plugin.macroManager.executeMacro(player, macro.id)
        if (err != null) player.sendMessage(c("&c$err"))
        else { player.sendMessage(c("&aマクロ「&e${macro.name}&a」を実行しました。")); player.playSound(player.location, org.bukkit.Sound.UI_BUTTON_CLICK, 1f, 1.2f) }
    }

    private fun openDetail(player: Player, macro: PlayerMacro) {
        player.openInventory(buildMacroDetailInventory(player, macro))
        activePlayers.add(player.uniqueId.toString())
    }

    private fun startCreateMacro(player: Player) {
        val macros    = plugin.macroManager.getMacros(player.uniqueId)
        val maxMacros = plugin.config.getInt("macro.max-per-player", 10)
        if (macros.size >= maxMacros) { player.sendMessage(c("&cマクロの上限 ($maxMacros) に達しています。")); return }
        player.closeInventory()
        player.sendMessage(c("&7マクロ名を入力してください。(&e「cancel」でキャンセル)"))
        player.sendMessage(c("&7ID は &f<あなたのID>_<マクロ名> &7の形式で自動生成されます。"))
        chatInputPlayers[player.uniqueId.toString()] = ChatInputState(InputMode.MACRO_NAME, null)
    }

    private fun reopenList(player: Player, delta: Int) {
        val title   = LegacyComponentSerializer.legacyAmpersand().serialize(player.openInventory.title())
        val curPage = Regex("(\\d+)/").find(title)?.groupValues?.get(1)?.toIntOrNull()?.minus(1) ?: 0
        val newPage = (curPage + delta).coerceAtLeast(0)
        player.closeInventory()
        Bukkit.getScheduler().runTaskLater(plugin, Runnable {
            player.openInventory(buildMacroListInventory(player, newPage))
            activePlayers.add(player.uniqueId.toString())
        }, 1L)
    }

    // ============================
    // ユーティリティ
    // ============================

    private fun getMacroFromSlot(player: Player, slot: Int): PlayerMacro? =
        plugin.macroManager.getMacros(player.uniqueId).getOrNull(slot)

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