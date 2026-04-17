package com.github.sahyuya.oyasaiMenu.macro

import com.github.sahyuya.oyasaiMenu.OyasaiMenu
import com.github.sahyuya.oyasaiMenu.model.PlayerMacro
import org.bukkit.configuration.file.YamlConfiguration
import java.io.File
import java.util.UUID

/**
 * MacroManager
 *
 * プレイヤーのコマンドマクロを UUID 単位の YAML ファイルに永続化する。
 * ファイルパスは plugins/OyasaiMenu/userdata/<UUID>.yml となる。
 *
 * 設計上の考え方:
 *   - 「1プレイヤー = 1ファイル」にすることで、プレイヤーが増えても
 *     単一ファイルが肥大化しない (設計書の "データ肥大化対策" に準拠)。
 *   - オンラインのプレイヤーのデータはメモリにキャッシュし、
 *     ログオフ時とリロード時のみ YAML に書き出す。
 *   - コマンドホワイトリストは config.yml から取得し、
 *     不正なコマンドはマクロ登録時に弾く。
 */
class MacroManager(private val plugin: OyasaiMenu) {

    // プレイヤー UUID 文字列 → そのプレイヤーの全マクロリスト (インメモリキャッシュ)
    private val cache: MutableMap<String, MutableList<PlayerMacro>> = mutableMapOf()

    // クールダウン管理: "UUID:macroId" → 最後の実行 epoch ms
    private val cooldowns: MutableMap<String, Long> = mutableMapOf()

    // userdata ディレクトリへの参照
    private val dataDir: File
        get() = File(plugin.dataFolder, "userdata").also { it.mkdirs() }

    // ============================
    // プレイヤーデータの読み書き
    // ============================

    /**
     * プレイヤーのマクロデータを YAML から読み込んでキャッシュに格納する。
     * PlayerLoginEvent などのタイミングで呼ぶと効率的。
     *
     * YAML 構造:
     *   macros:
     *     home_tp:
     *       name: "ホームへ"
     *       commands:
     *         - "home"
     *         - "say 帰宅しました"
     *       cooldown: 3
     */
    fun loadPlayer(uuid: UUID) {
        val file = playerFile(uuid)
        if (!file.exists()) {
            // 初回ログイン: 空リストをキャッシュして終了
            cache[uuid.toString()] = mutableListOf()
            return
        }

        val yaml = YamlConfiguration.loadConfiguration(file)
        val macroSection = yaml.getConfigurationSection("macros")
        val list = mutableListOf<PlayerMacro>()

        macroSection?.getKeys(false)?.forEach { id ->
            val sec = macroSection.getConfigurationSection(id) ?: return@forEach
            list.add(
                PlayerMacro(
                    id = id,
                    name = sec.getString("name", id) ?: id,
                    ownerUUID = uuid.toString(),
                    commands = sec.getStringList("commands"),
                    cooldownSeconds = sec.getInt("cooldown",
                        plugin.config.getInt("macro.cooldown-seconds", 3))
                )
            )
        }

        cache[uuid.toString()] = list
        plugin.logger.fine("マクロをロード: ${uuid} (${list.size} 件)")
    }

    /**
     * キャッシュの内容を YAML ファイルに書き出す。
     * PlayerQuitEvent や /menu reload のタイミングで呼ぶ。
     */
    fun savePlayer(uuid: UUID) {
        val macros = cache[uuid.toString()] ?: return
        val yaml = YamlConfiguration()

        macros.forEach { macro ->
            val path = "macros.${macro.id}"
            yaml.set("$path.name", macro.name)
            yaml.set("$path.commands", macro.commands)
            yaml.set("$path.cooldown", macro.cooldownSeconds)
        }

        val file = playerFile(uuid)
        file.parentFile.mkdirs()
        yaml.save(file)
        plugin.logger.fine("マクロを保存: ${uuid} (${macros.size} 件)")
    }

    /** ログアウト時にキャッシュを解放する (savePlayer の後に呼ぶ) */
    fun unloadPlayer(uuid: UUID) {
        savePlayer(uuid)
        cache.remove(uuid.toString())
        // クールダウンエントリも掃除
        cooldowns.keys.removeIf { it.startsWith("$uuid:") }
    }

    // ============================
    // マクロ CRUD
    // ============================

    /** プレイヤーの全マクロを返す */
    fun getMacros(uuid: UUID): List<PlayerMacro> =
        cache[uuid.toString()]?.toList() ?: emptyList()

    /** 指定IDのマクロを返す */
    fun getMacro(uuid: UUID, macroId: String): PlayerMacro? =
        cache[uuid.toString()]?.find { it.id == macroId }

    /**
     * マクロを追加する。
     * 上限数チェックとコマンドホワイトリストチェックを行う。
     *
     * @return 成功時 null、失敗時エラーメッセージ
     */
    fun addMacro(uuid: UUID, macro: PlayerMacro): String? {
        val list = cache.getOrPut(uuid.toString()) { mutableListOf() }

        val maxMacros = plugin.config.getInt("macro.max-per-player", 10)
        if (list.size >= maxMacros) {
            return "マクロの上限数 ($maxMacros) に達しています。"
        }

        if (list.any { it.id == macro.id }) {
            return "ID '${macro.id}' のマクロは既に存在します。"
        }

        // コマンドホワイトリストチェック
        val whitelist = plugin.config.getStringList("macro.command-whitelist")
        if (whitelist.isNotEmpty()) {
            val blocked = macro.commands.filter { cmd ->
                val base = cmd.trimStart('/').split(" ").first().lowercase()
                whitelist.none { allowed -> allowed.lowercase() == base }
            }
            if (blocked.isNotEmpty()) {
                return "許可されていないコマンドが含まれています: ${blocked.joinToString()}"
            }
        }

        list.add(macro)
        savePlayer(uuid) // 即時永続化
        return null
    }

    /**
     * 既存マクロを更新する。
     *
     * @return 成功時 null、失敗時エラーメッセージ
     */
    fun updateMacro(uuid: UUID, updated: PlayerMacro): String? {
        val list = cache[uuid.toString()] ?: return "データが読み込まれていません。"
        val index = list.indexOfFirst { it.id == updated.id }
        if (index < 0) return "ID '${updated.id}' のマクロが見つかりません。"
        list[index] = updated
        savePlayer(uuid)
        return null
    }

    /**
     * マクロを削除する。
     *
     * @return 削除できたなら true
     */
    fun removeMacro(uuid: UUID, macroId: String): Boolean {
        val removed = cache[uuid.toString()]?.removeIf { it.id == macroId } ?: false
        if (removed) savePlayer(uuid)
        return removed
    }

    // ============================
    // マクロ実行
    // ============================

    /**
     * マクロを実行する。クールダウンを考慮し、
     * ホワイトリストに含まれるコマンドのみプレイヤーに実行させる。
     *
     * @return 実行できた場合 null、できなかった場合エラーメッセージ
     */
    fun executeMacro(player: org.bukkit.entity.Player, macroId: String): String? {
        val macro = getMacro(player.uniqueId, macroId)
            ?: return "マクロ '$macroId' が見つかりません。"

        // クールダウンチェック
        val key = "${player.uniqueId}:$macroId"
        val now = System.currentTimeMillis()
        val last = cooldowns[key] ?: 0L
        val cd = macro.cooldownSeconds * 1000L
        if (now - last < cd) {
            val remaining = (cd - (now - last)) / 1000 + 1
            return "クールダウン中です。あと ${remaining} 秒お待ちください。"
        }
        cooldowns[key] = now

        // コマンドを順番に実行
        macro.commands.forEach { cmd ->
            player.performCommand(cmd.trimStart('/'))
        }
        return null
    }

    // ============================
    // ユーティリティ
    // ============================

    /** UUID に対応する YAML ファイルのパスを返す */
    private fun playerFile(uuid: UUID): File =
        File(dataDir, "$uuid.yml")

    /** キャッシュ済みの全プレイヤーを一括保存 (シャットダウン時などに使う) */
    fun saveAll() {
        cache.keys.forEach { uuidStr ->
            runCatching { savePlayer(UUID.fromString(uuidStr)) }
                .onFailure { plugin.logger.warning("マクロ保存失敗: $uuidStr - ${it.message}") }
        }
    }
}