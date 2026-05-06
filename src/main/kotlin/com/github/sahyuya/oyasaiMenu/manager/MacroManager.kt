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
 * 変更点:
 *   - distributeOpTemplates(player): OPログイン時にテンプレートマクロを配布
 *     op-macro-templates.yml で定義、IDは "_op_<key>" 形式
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

    fun setCommandAtIndex(uuid: UUID, macroId: String, index: Int, value: String): String? {
        val macro = getMacro(uuid, macroId) ?: return "マクロ '$macroId' が見つかりません。"
        validateCommands(listOf(value))?.let { return it }
        val normalized = if (isWaitCommand(value)) value.trim() else value.trim().removePrefix("/")
        val commands = macro.commands.toMutableList()
        if (index >= commands.size) commands.add(normalized) else commands[index] = normalized
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

    /**
     * OPプレイヤーにテンプレートマクロを配布する。
     * op-macro-templates.yml に定義されたマクロを、まだ持っていない場合のみ追加する。
     * テンプレートIDは "_op_<key>" 形式。
     */
    fun distributeOpTemplates(player: Player) {
        if (!player.isOp) return

        val templateFile = File(plugin.dataFolder, "op-macro-templates.yml").also {
            if (!it.exists()) createDefaultOpTemplates(it)
        }

        val yaml         = YamlConfiguration.loadConfiguration(templateFile)
        val templatesSec = yaml.getConfigurationSection("templates") ?: return

        var added = 0
        templatesSec.getKeys(false).forEach { templateId ->
            val sec      = templatesSec.getConfigurationSection(templateId) ?: return@forEach
            val name     = sec.getString("name", templateId) ?: templateId
            val commands = sec.getStringList("commands")
            val cooldown = sec.getInt("cooldown", plugin.config.getInt("macro.cooldown-seconds", 3))
            val macroId  = "_op_$templateId"

            // 既に持っている場合はスキップ
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
            // 1秒後に通知 (ログイン直後の他メッセージに埋もれないよう遅延)
            Bukkit.getScheduler().runTaskLater(plugin, Runnable {
                player.sendMessage(c("&b[OyasaiMenu] &a${added}個のOPマクロテンプレートを追加しました。(&e/macro &aで確認)"))
            }, 20L)
        }
    }

    private fun createDefaultOpTemplates(file: File) {
        file.parentFile.mkdirs()
        file.writeText("""# op-macro-templates.yml
#
# OPプレイヤーがログインした際に自動配布されるマクロテンプレートを定義します。
# マクロIDは "_op_<キー名>" 形式で付与されます (例: _op_worldsettings)。
# 既に同じIDのマクロを持っているプレイヤーには再配布しません。
#
# commands フォーマット:
#   - コマンド文字列 (先頭スラッシュなし)
#   - "wait 1s" / "wait 0.5s" でウェイト挿入可
#
# このファイルを編集後は /om reload でリロードできます。
# ただし再配布は既にIDがないプレイヤーのみです。

templates:

  worldsettings:
    name: "ワールド設定"
    cooldown: 5
    commands:
      - "gamerule doDaylightCycle false"
      - "gamerule doWeatherCycle false"
      - "gamerule doMobSpawning false"
      - "gamerule randomTickSpeed 0"
      - "rg flag __global__ coral-fade deny"
      - "gamerule playersNetherPortalDefaultDelay 100000000"
      - "gamerule playersNetherPortalCreativeDelay 100000000"

  timeday:
    name: "昼にセット"
    cooldown: 3
    commands:
      - "time set day"
      - "weather clear"

  clearlag:
    name: "エンティティクリア"
    cooldown: 10
    commands:
      - "kill @e[type=!player,type=!armor_stand,type=!item_frame]"

  staffmode:
    name: "スタッフチャンネル切替"
    cooldown: 3
    commands:
      - "ch st"
""", Charsets.UTF_8)
        plugin.logger.info("op-macro-templates.yml をデフォルト内容で作成しました。")
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
            player.performCommand(cmd)
            executeCommandsFrom(player, commands, index + 1)
        }
    }

    // ============================
    // バリデーション
    // ============================

    private fun validateCommands(commands: List<String>): String? {
        val whitelist = plugin.config.getStringList("macro.command-whitelist")
        if (whitelist.isEmpty()) return null
        val blocked = commands
            .filter { !isWaitCommand(it) }
            .filter { cmd ->
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