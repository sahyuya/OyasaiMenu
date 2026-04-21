package com.github.sahyuya.oyasaiMenu.manager

import com.github.sahyuya.oyasaiMenu.OyasaiMenu
import com.github.sahyuya.oyasaiMenu.model.PlayerMacro
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.entity.Player
import java.io.File
import java.util.UUID

/**
 * MacroManager (manager パッケージへ移動)
 *
 * プレイヤーのコマンドマクロを userdata/<UUID>.yml に永続化する。
 * オンライン中はインメモリキャッシュで管理し、
 * ログオフ時・リロード時のみ YAML に書き出す。
 */
class MacroManager(private val plugin: OyasaiMenu) {

    private val cache: MutableMap<String, MutableList<PlayerMacro>> = mutableMapOf()
    private val cooldowns: MutableMap<String, Long> = mutableMapOf()

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
                    id = id,
                    name = sec.getString("name", id) ?: id,
                    ownerUUID = uuid.toString(),
                    commands = sec.getStringList("commands"),
                    cooldownSeconds = sec.getInt("cooldown", plugin.config.getInt("macro.cooldown-seconds", 3))
                ))
            }
        }
        cache[uuid.toString()] = list
    }

    fun savePlayer(uuid: UUID) {
        val macros = cache[uuid.toString()] ?: return
        val yaml = YamlConfiguration()
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
        val max = plugin.config.getInt("macro.max-per-player", 10)
        if (list.size >= max) return "マクロの上限数 ($max) に達しています。"
        if (list.any { it.id == macro.id }) return "ID '${macro.id}' は既に存在します。"

        val whitelist = plugin.config.getStringList("macro.command-whitelist")
        if (whitelist.isNotEmpty()) {
            val blocked = macro.commands.filter { cmd ->
                whitelist.none { it.equals(cmd.trimStart('/').split(" ").first(), ignoreCase = true) }
            }
            if (blocked.isNotEmpty()) return "許可されていないコマンド: ${blocked.joinToString()}"
        }
        list.add(macro); savePlayer(uuid); return null
    }

    fun updateMacro(uuid: UUID, updated: PlayerMacro): String? {
        val list = cache[uuid.toString()] ?: return "データ未ロード。"
        val idx = list.indexOfFirst { it.id == updated.id }
        if (idx < 0) return "ID '${updated.id}' が見つかりません。"
        list[idx] = updated; savePlayer(uuid); return null
    }

    fun removeMacro(uuid: UUID, macroId: String): Boolean {
        val removed = cache[uuid.toString()]?.removeIf { it.id == macroId } ?: false
        if (removed) savePlayer(uuid); return removed
    }

    // ============================
    // 実行
    // ============================

    fun executeMacro(player: Player, macroId: String): String? {
        val macro = getMacro(player.uniqueId, macroId) ?: return "マクロ '$macroId' が見つかりません。"
        val key = "${player.uniqueId}:$macroId"
        val now = System.currentTimeMillis()
        val cd  = macro.cooldownSeconds * 1000L
        if (now - (cooldowns[key] ?: 0L) < cd)
            return "クールダウン中。あと ${(cd - (now - (cooldowns[key] ?: 0L))) / 1000 + 1} 秒。"
        cooldowns[key] = now
        macro.commands.forEach { player.performCommand(it.trimStart('/')) }
        return null
    }

    private fun playerFile(uuid: UUID) = File(dataDir, "$uuid.yml")
}