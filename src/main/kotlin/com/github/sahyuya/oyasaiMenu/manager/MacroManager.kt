package com.github.sahyuya.oyasaiMenu.manager

import com.github.sahyuya.oyasaiMenu.OyasaiMenu
import com.github.sahyuya.oyasaiMenu.model.PlayerMacro
import com.github.sahyuya.oyasaiMenu.util.GuiUtil.c
import org.bukkit.Bukkit
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.entity.Player
import java.io.File
import java.util.UUID

/**
 * MacroManager
 *
 * ■ スラッシュの扱い
 *   保存時: ユーザーが入力した内容をそのまま保存 (スラッシュを除去しない)
 *   実行時: performCommand() の前に先頭スラッシュを1つだけ除去する
 *     → `warp home`  → performCommand("warp home")  → /warp home
 *     → `/warp home` → performCommand("warp home")  → /warp home
 *     → `//wand`     → performCommand("/wand")       → //wand (FAWE)
 *
 * ■ コマンドホワイトリスト
 *   command-whitelist の各エントリはコマンド名 または パーミッションノード。
 *   - コマンド名一致 (例: "warp") → そのコマンドを許可
 *   - パーミッションノード (例: "fawe.select") → そのパーミッションを持つ
 *     プレイヤーはホワイトリスト検査を全スキップ
 *   - リストが空の場合は全コマンドを許可
 *
 * ■ コマンド数制限
 *   macro.max-commands-per-macro (デフォルト 20) で1マクロあたりの上限を設定。
 *   macro.max-commands-per-permission で権限別に上限を上書き可能。
 */
class MacroManager(private val plugin: OyasaiMenu) {

    private val cache:     MutableMap<String, MutableList<PlayerMacro>> = mutableMapOf()
    private val cooldowns: MutableMap<String, Long>                     = mutableMapOf()

    private val waitRegex = Regex("""^wait\s+(\d+(?:\.\d+)?)s?$""", RegexOption.IGNORE_CASE)

    private val dataDir: File get() = File(plugin.dataFolder, "userdata").also { it.mkdirs() }

    // ============================
    // 読み書き
    // ============================

    fun loadPlayer(uuid: UUID) {
        val file = playerFile(uuid)
        if (!file.exists()) { cache[uuid.toString()] = mutableListOf(); return }
        val yaml = YamlConfiguration.loadConfiguration(file)
        val list = mutableListOf<PlayerMacro>()
        yaml.getConfigurationSection("macros")?.getKeys(false)?.forEach { id ->
            yaml.getConfigurationSection("macros.$id")?.let { sec ->
                list.add(PlayerMacro(
                    id              = id,
                    name            = sec.getString("name", id) ?: id,
                    ownerUUID       = uuid.toString(),
                    commands        = sec.getStringList("commands"),
                    cooldownSeconds = sec.getInt("cooldown", plugin.config.getInt("macro.cooldown-seconds", 3))
                ))
            }
        }
        cache[uuid.toString()] = list
    }

    fun savePlayer(uuid: UUID) {
        val macros = cache[uuid.toString()] ?: return
        val yaml   = YamlConfiguration()
        macros.forEach { m ->
            yaml.set("macros.${m.id}.name",     m.name)
            yaml.set("macros.${m.id}.commands", m.commands)
            yaml.set("macros.${m.id}.cooldown", m.cooldownSeconds)
        }
        playerFile(uuid).also { it.parentFile.mkdirs() }.let { yaml.save(it) }
    }

    fun unloadPlayer(uuid: UUID) {
        savePlayer(uuid); cache.remove(uuid.toString())
        cooldowns.keys.removeIf { it.startsWith("$uuid:") }
    }

    fun saveAll() {
        cache.keys.forEach { uuidStr ->
            runCatching { savePlayer(UUID.fromString(uuidStr)) }
                .onFailure { plugin.logger.warning("マクロ保存失敗: $uuidStr") }
        }
    }

    // ============================
    // 権限別マクロ最大数
    // ============================

    fun getMaxMacros(player: Player): Int {
        val default     = plugin.config.getInt("macro.max-per-player", 10)
        val permSection = plugin.config.getConfigurationSection("macro.max-per-permission")
            ?: return default
        return permSection.getKeys(false)
            .filter { player.hasPermission(it) }
            .mapNotNull { permSection.getInt(it, 0).takeIf { v -> v > 0 } }
            .maxOrNull() ?: default
    }

    // ============================
    // 権限別コマンド最大数
    // ============================

    fun getMaxCommands(player: Player): Int {
        val default     = plugin.config.getInt("macro.max-commands-per-macro", 20)
        val permSection = plugin.config.getConfigurationSection("macro.max-commands-per-permission")
            ?: return default
        return permSection.getKeys(false)
            .filter { player.hasPermission(it) }
            .mapNotNull { permSection.getInt(it, 0).takeIf { v -> v > 0 } }
            .maxOrNull() ?: default
    }

    // ============================
    // CRUD
    // ============================

    fun getMacros(uuid: UUID): List<PlayerMacro>   = cache[uuid.toString()]?.toList() ?: emptyList()
    fun getMacro(uuid: UUID, id: String): PlayerMacro? = cache[uuid.toString()]?.find { it.id == id }

    /**
     * @param maxOverride  null の場合は config のデフォルト値を使用
     * @param player       ホワイトリスト検査とコマンド数上限確認に使用 (null = 検査スキップ)
     */
    fun addMacro(uuid: UUID, macro: PlayerMacro, maxOverride: Int? = null, player: Player? = null): String? {
        val list = cache.getOrPut(uuid.toString()) { mutableListOf() }
        val max  = maxOverride ?: plugin.config.getInt("macro.max-per-player", 10)
        if (list.size >= max) return "マクロの上限数 ($max) に達しています。"
        if (list.any { it.id == macro.id }) return "ID '${macro.id}' は既に存在します。"
        validateCommands(macro.commands, player)?.let { return it }
        list.add(macro); savePlayer(uuid); return null
    }

    /**
     * @param player  ホワイトリスト検査とコマンド数上限確認に使用 (null = 検査スキップ)
     */
    fun updateMacro(uuid: UUID, updated: PlayerMacro, player: Player? = null): String? {
        val list = cache[uuid.toString()] ?: return "データ未ロード。"
        val idx  = list.indexOfFirst { it.id == updated.id }
        if (idx < 0) return "ID '${updated.id}' が見つかりません。"
        validateCommands(updated.commands, player)?.let { return it }
        list[idx] = updated; savePlayer(uuid); return null
    }

    fun removeMacro(uuid: UUID, macroId: String): Boolean {
        val removed = cache[uuid.toString()]?.removeIf { it.id == macroId } ?: false
        if (removed) savePlayer(uuid); return removed
    }

    fun setCommandAtIndex(uuid: UUID, macroId: String, index: Int, value: String, player: Player? = null): String? {
        val macro = getMacro(uuid, macroId) ?: return "マクロ '$macroId' が見つかりません。"
        val commands = macro.commands.toMutableList()
        if (index >= commands.size) commands.add(value.trim()) else commands[index] = value.trim()
        validateCommands(commands, player)?.let { return it }
        return updateMacro(uuid, macro.copy(commands = commands))
    }

    fun removeCommandAtIndex(uuid: UUID, macroId: String, index: Int): String? {
        val macro = getMacro(uuid, macroId) ?: return "マクロ '$macroId' が見つかりません。"
        if (index !in macro.commands.indices)
            return "${index + 1} 番目は存在しません (現在 ${macro.commands.size} 個)。"
        val commands = macro.commands.toMutableList()
        commands.removeAt(index)
        return updateMacro(uuid, macro.copy(commands = commands))
    }

    // ============================
    // OPテンプレートマクロ配布
    // ============================

    fun distributeOpTemplates(player: Player) {
        if (!player.isOp) return

        val templateFile = File(plugin.dataFolder, "op-macro-templates.yml")
        if (!templateFile.exists()) {
            runCatching { plugin.saveResource("op-macro-templates.yml", false) }
                .onFailure { plugin.logger.warning("op-macro-templates.yml の展開失敗: ${it.message}"); return }
        }
        if (!templateFile.exists()) return

        val yaml         = YamlConfiguration.loadConfiguration(templateFile)
        val templatesSec = yaml.getConfigurationSection("templates") ?: return
        var added        = 0

        templatesSec.getKeys(false).forEach { templateId ->
            val sec      = templatesSec.getConfigurationSection(templateId) ?: return@forEach
            val name     = sec.getString("name", templateId) ?: templateId
            val commands = sec.getStringList("commands")
            val cooldown = sec.getInt("cooldown", plugin.config.getInt("macro.cooldown-seconds", 3))
            val macroId  = "_op_$templateId"
            if (getMacro(player.uniqueId, macroId) != null) return@forEach

            val list = cache.getOrPut(player.uniqueId.toString()) { mutableListOf() }
            list.add(PlayerMacro(
                id              = macroId,
                name            = name,
                ownerUUID       = player.uniqueId.toString(),
                commands        = commands,
                cooldownSeconds = cooldown
            ))
            added++
        }

        if (added > 0) {
            savePlayer(player.uniqueId)
            Bukkit.getScheduler().runTaskLater(plugin, Runnable {
                player.sendMessage(c("&b[OyasaiMenu] &a${added}個のOPマクロテンプレートを追加しました。(&e/macro &aで確認)"))
            }, 20L)
        }
    }

    // ============================
    // 実行 (wait 対応)
    // ============================

    fun executeMacro(player: Player, macroId: String): String? {
        val macro = getMacro(player.uniqueId, macroId) ?: return "マクロ '$macroId' が見つかりません。"
        val key   = "${player.uniqueId}:$macroId"
        val now   = System.currentTimeMillis()
        val cd    = macro.cooldownSeconds * 1000L
        if (now - (cooldowns[key] ?: 0L) < cd) {
            val remaining = (cd - (now - (cooldowns[key] ?: 0L))) / 1000 + 1
            return "クールダウン中。あと ${remaining} 秒。"
        }
        cooldowns[key] = now
        executeCommandsFrom(player, macro.commands, 0)
        return null
    }

    /**
     * コマンドを順に実行する。
     *
     * ■ スラッシュの扱い (実行時)
     *   performCommand() に渡す前に先頭スラッシュを1つだけ除去する。
     *     `/warp home` → performCommand("warp home")  → /warp home
     *     `//wand`     → performCommand("/wand")       → //wand (FAWE)
     *     `warp home`  → performCommand("warp home")  → /warp home
     */
    private fun executeCommandsFrom(player: Player, commands: List<String>, index: Int) {
        if (index >= commands.size) return
        val cmd = commands[index].trim()
        val waitMatch = waitRegex.matchEntire(cmd)
        if (waitMatch != null) {
            val seconds   = waitMatch.groupValues[1].toDoubleOrNull() ?: 0.0
            val delayTick = (seconds * 20.0).toLong().coerceAtLeast(1L)
            Bukkit.getScheduler().runTaskLater(plugin, Runnable {
                executeCommandsFrom(player, commands, index + 1)
            }, delayTick)
        } else {
            // 先頭スラッシュを1つだけ除去して実行
            val execCmd = if (cmd.startsWith("/")) cmd.removePrefix("/") else cmd
            player.performCommand(execCmd)
            executeCommandsFrom(player, commands, index + 1)
        }
    }

    // ============================
    // バリデーション
    // ============================

    /**
     * コマンドリストを検証する。
     *
     * @param commands 検証するコマンドリスト
     * @param player   権限チェックとコマンド数上限確認に使用 (null = スキップ)
     * @return エラーメッセージ (問題なければ null)
     *
     * ■ ホワイトリストエントリの種別
     *   - "warp" のようなコマンド名 → ベース名一致で許可
     *   - "fawe.select" のようなパーミッションノード (ドット含む) →
     *     プレイヤーがそのパーミッションを持つ場合はホワイトリスト検査を全スキップ
     */
    private fun validateCommands(commands: List<String>, player: Player? = null): String? {
        // コマンド数上限チェック
        if (player != null) {
            val maxCmds = getMaxCommands(player)
            if (commands.size > maxCmds) return "コマンド数の上限 ($maxCmds) に達しています。"
        }

        val whitelist = plugin.config.getStringList("macro.command-whitelist")
        if (whitelist.isEmpty()) return null

        // パーミッションノードによる全体バイパス
        if (player != null) {
            val hasBypass = whitelist.any { entry ->
                entry.contains('.') && !entry.contains(' ') && player.hasPermission(entry)
            }
            if (hasBypass) return null
        }

        // コマンド名一致チェック
        // ベース名抽出: 先頭のスラッシュを最大2つ除去してコマンド名だけ取り出す
        val blocked = commands.filter { !isWaitCommand(it) }.filter { cmd ->
            val base = cmd.trimStart().removePrefix("/").removePrefix("/").split(" ").first().lowercase()
            whitelist.none { entry ->
                // コマンド名としてのエントリのみ比較 (ドットを含まないか、スラッシュ始まりの場合)
                !entry.contains('.') && entry.equals(base, ignoreCase = true)
            }
        }
        if (blocked.isNotEmpty()) return "許可されていないコマンド: ${blocked.joinToString()}"
        return null
    }

    fun isWaitCommand(cmd: String) = waitRegex.matches(cmd.trim())

    private fun playerFile(uuid: UUID) = File(dataDir, "$uuid.yml")
}