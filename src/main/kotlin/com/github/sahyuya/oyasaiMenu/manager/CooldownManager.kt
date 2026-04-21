package com.github.sahyuya.oyasaiMenu.manager

import com.github.sahyuya.oyasaiMenu.OyasaiMenu
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * CooldownManager
 *
 * GUIクリックとコマンド実行のクールダウンを管理する。
 *
 * config.yml:
 *   cooldown:
 *     click-ms: 300      # GUIクリックの最小間隔 (ms)。0 = 無効
 *     command-ms: 500    # コマンド実行の最小間隔 (ms)。0 = 無効
 */
object CooldownManager {

    private val clickMap:   ConcurrentHashMap<UUID, Long> = ConcurrentHashMap()
    private val commandMap: ConcurrentHashMap<UUID, Long> = ConcurrentHashMap()

    var clickMs:   Long = 300L
        private set
    var commandMs: Long = 500L
        private set

    fun init(plugin: OyasaiMenu) {
        // YAML は整数を Int として扱うため getInt で読み込んで Long に変換する
        clickMs   = plugin.config.getInt("cooldown.click-ms",   300).toLong()
        commandMs = plugin.config.getInt("cooldown.command-ms", 500).toLong()
        plugin.logger.info("CooldownManager: click=${clickMs}ms / command=${commandMs}ms")
    }

    /**
     * GUIクリックのクールダウンチェック。
     * @return true = クールダウン中 (無視すべき), false = 実行OK かつ時刻を記録
     */
    fun isClickOnCooldown(uuid: UUID): Boolean {
        if (clickMs <= 0L) return false
        val now  = System.currentTimeMillis()
        val last = clickMap[uuid] ?: 0L
        if (now - last < clickMs) return true   // クールダウン中
        clickMap[uuid] = now                     // 時刻を記録してOK
        return false
    }

    /**
     * コマンド実行のクールダウンチェック。
     * @return true = クールダウン中 (無視すべき), false = 実行OK かつ時刻を記録
     */
    fun isCommandOnCooldown(uuid: UUID): Boolean {
        if (commandMs <= 0L) return false
        val now  = System.currentTimeMillis()
        val last = commandMap[uuid] ?: 0L
        if (now - last < commandMs) return true
        commandMap[uuid] = now
        return false
    }

    fun remove(uuid: UUID) { clickMap.remove(uuid); commandMap.remove(uuid) }
    fun reload(plugin: OyasaiMenu) = init(plugin)
}