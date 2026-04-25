package com.github.sahyuya.oyasaiMenu.manager

import com.github.sahyuya.oyasaiMenu.OyasaiMenu
import com.github.sahyuya.oyasaiMenu.model.PlayerMacro
import org.bukkit.Bukkit
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.entity.Player
import java.io.File
import java.util.UUID

/**
 * MacroManager
 *
 * ■ コマンド保存形式
 *   YAML の commands リストには "実行するコマンド文字列" を格納する。
 *   先頭スラッシュの扱い:
 *     /wand   → performCommand("wand")     → /wand 実行
 *     //wand  → removePrefix("/") → /wand → performCommand("/wand") → //wand 実行
 *     home    → performCommand("home")     → /home 実行
 *
 *   wait 指定の形式:
 *     "wait 1s"  / "wait 0.5s" / "wait 1.5s"  (秒単位, 小数OK)
 *
 * ■ executeMacro での wait 処理
 *   commands を先頭から順に処理し、wait に遭遇したら
 *   Bukkit.runTaskLater() で指定 tick 後に残りを再帰実行する。
 *   (= wait はコマンド間に挟める任意のウェイト)
 *
 * ■ 追加 API
 *   setCommandAtIndex   — /macro <id> <番号> <値> で1行を上書き/追加
 *   removeCommandAtIndex — /macro <id> <番号> remove で1行削除
 */
class MacroManager(private val plugin: OyasaiMenu) {

    private val cache:     MutableMap<String, MutableList<PlayerMacro>> = mutableMapOf()
    private val cooldowns: MutableMap<String, Long>                     = mutableMapOf()

    // wait コマンドの正規表現: "wait 1s" / "wait 0.5s" / "wait 1.5s"
    private val waitRegex = Regex("""^wait\s+(\d+(?:\.\d+)?)s?$""", RegexOption.IGNORE_CASE)

    private val dataDir: File
        get() = File(plugin.dataFolder, "userdata").also { it.mkdirs() }

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
        savePlayer(uuid)
        cache.remove(uuid.toString())
        cooldowns.keys.removeIf { it.startsWith("$uuid:") }
    }

    fun saveAll() {
        cache.keys.forEach { uuidStr ->
            runCatching { savePlayer(UUID.fromString(uuidStr)) }
                .onFailure { plugin.logger.warning("マクロ保存失敗: $uuidStr") }
        }
    }

    // ============================
    // CRUD
    // ============================

    fun getMacros(uuid: UUID): List<PlayerMacro> = cache[uuid.toString()]?.toList() ?: emptyList()
    fun getMacro(uuid: UUID, id: String): PlayerMacro? = cache[uuid.toString()]?.find { it.id == id }

    fun addMacro(uuid: UUID, macro: PlayerMacro): String? {
        val list = cache.getOrPut(uuid.toString()) { mutableListOf() }
        val max  = plugin.config.getInt("macro.max-per-player", 10)
        if (list.size >= max) return "マクロの上限数 ($max) に達しています。"
        if (list.any { it.id == macro.id }) return "ID '${macro.id}' は既に存在します。"
        validateCommands(macro.commands)?.let { return it }
        list.add(macro)
        savePlayer(uuid)
        return null
    }

    fun updateMacro(uuid: UUID, updated: PlayerMacro): String? {
        val list = cache[uuid.toString()] ?: return "データ未ロード。"
        val idx  = list.indexOfFirst { it.id == updated.id }
        if (idx < 0) return "ID '${updated.id}' が見つかりません。"
        list[idx] = updated
        savePlayer(uuid)
        return null
    }

    fun removeMacro(uuid: UUID, macroId: String): Boolean {
        val removed = cache[uuid.toString()]?.removeIf { it.id == macroId } ?: false
        if (removed) savePlayer(uuid)
        return removed
    }

    /**
     * 指定インデックスのコマンドを上書きまたは追加する。
     * index が現在のリストサイズを超える場合は末尾に追加する。
     * @param index  0-indexed
     * @param value  コマンド文字列 ("//wand", "wait 1s" など)
     */
    fun setCommandAtIndex(uuid: UUID, macroId: String, index: Int, value: String): String? {
        val macro = getMacro(uuid, macroId) ?: return "マクロ '$macroId' が見つかりません。"
        validateCommands(listOf(value))?.let { return it }

        // removePrefix("/") で先頭の / を1つだけ除去して保存
        val normalized = if (isWaitCommand(value)) value.trim()
                         else value.trim().removePrefix("/")

        val commands = macro.commands.toMutableList()
        if (index >= commands.size) {
            commands.add(normalized)
        } else {
            commands[index] = normalized
        }
        return updateMacro(uuid, macro.copy(commands = commands))
    }

    /**
     * 指定インデックスのコマンドを削除する。
     * @param index  0-indexed
     */
    fun removeCommandAtIndex(uuid: UUID, macroId: String, index: Int): String? {
        val macro = getMacro(uuid, macroId) ?: return "マクロ '$macroId' が見つかりません。"
        if (index !in macro.commands.indices)
            return "${index + 1} 番目は存在しません (現在 ${macro.commands.size} 個)。"
        val commands = macro.commands.toMutableList()
        commands.removeAt(index)
        return updateMacro(uuid, macro.copy(commands = commands))
    }

    // ============================
    // 実行 (wait 対応)
    // ============================

    /**
     * マクロを実行する。
     * wait コマンドに遭遇した場合は指定秒待機してから残りのコマンドを実行する。
     * 実行元は常にプレイヤー (performCommand)。
     *
     * @return エラーメッセージ (成功なら null)
     */
    fun executeMacro(player: Player, macroId: String): String? {
        val macro = getMacro(player.uniqueId, macroId)
            ?: return "マクロ '$macroId' が見つかりません。"
        val key = "${player.uniqueId}:$macroId"
        val now = System.currentTimeMillis()
        val cd  = macro.cooldownSeconds * 1000L
        if (now - (cooldowns[key] ?: 0L) < cd) {
            val remaining = (cd - (now - (cooldowns[key] ?: 0L))) / 1000 + 1
            return "クールダウン中。あと ${remaining} 秒。"
        }
        cooldowns[key] = now
        executeCommandsFrom(player, macro.commands, 0)
        return null
    }

    /**
     * commands[index] から順番に実行する。
     * wait コマンドに当たったら runTaskLater で残りを再帰呼び出し。
     */
    private fun executeCommandsFrom(player: Player, commands: List<String>, index: Int) {
        if (index >= commands.size) return
        val cmd = commands[index].trim()

        val waitMatch = waitRegex.matchEntire(cmd)
        if (waitMatch != null) {
            // wait Xs → X秒後に次のコマンドへ
            val seconds   = waitMatch.groupValues[1].toDoubleOrNull() ?: 0.0
            val delayTick = (seconds * 20.0).toLong().coerceAtLeast(1L)
            Bukkit.getScheduler().runTaskLater(plugin, Runnable {
                executeCommandsFrom(player, commands, index + 1)
            }, delayTick)
        } else {
            // 通常コマンド: performCommand に渡す
            // 保存形式: "home" → performCommand("home") → /home
            //           "/wand" → performCommand("/wand") → //wand
            player.performCommand(cmd)
            // 次のコマンドへ (同期的に続行)
            executeCommandsFrom(player, commands, index + 1)
        }
    }

    // ============================
    // バリデーション
    // ============================

    /**
     * コマンドリストを検証する。
     * - ホワイトリストが設定されている場合: wait 以外のコマンドについてチェック
     * @return エラーメッセージ (問題なければ null)
     */
    private fun validateCommands(commands: List<String>): String? {
        val whitelist = plugin.config.getStringList("macro.command-whitelist")
        if (whitelist.isEmpty()) return null
        val blocked = commands
            .filter { !isWaitCommand(it) }
            .filter { cmd ->
                // 先頭スラッシュを取り除いた最初の単語でチェック
                val base = cmd.removePrefix("/").removePrefix("/").split(" ").first()
                whitelist.none { it.equals(base, ignoreCase = true) }
            }
        if (blocked.isNotEmpty()) return "許可されていないコマンド: ${blocked.joinToString()}"
        return null
    }

    fun isWaitCommand(cmd: String) = waitRegex.matches(cmd.trim())

    // ============================
    // ユーティリティ
    // ============================

    private fun playerFile(uuid: UUID) = File(dataDir, "$uuid.yml")
}