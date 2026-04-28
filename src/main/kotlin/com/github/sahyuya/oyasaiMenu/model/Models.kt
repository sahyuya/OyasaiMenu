package com.github.sahyuya.oyasaiMenu.model

import org.bukkit.Material

// ============================================================
// メニュー定義モデル
// ============================================================

data class MenuDefinition(
    val id: String,
    val title: String,
    val size: Int = 54,
    val items: Map<String, MenuItemDefinition> = emptyMap()
)

data class MenuItemDefinition(
    val slot: Int,
    val icon: Material = Material.STONE,
    val name: String = "",
    val lore: List<String> = emptyList(),
    val actions: List<MenuAction> = emptyList(),
    val permission: String? = null,
    val template: String? = null
)

// ============================================================
// アクション定義
// ============================================================

/**
 * アクションタイプ。
 * 新規追加時は ActionEngine の when にも必ずブランチを追加すること。
 * (Kotlin の sealed-like exhaustive チェック対策として else を持つが
 *  新アクションは明示的に追加することを推奨)
 */
enum class ActionType {
    // ---- 基本 ----
    OPEN_MENU,
    RUN_COMMAND,
    RUN_PLAYER_COMMAND,
    CHECK_PERMISSION,
    MESSAGE,
    CLOSE_MENU,
    MACRO_EXECUTE,
    PLACEHOLDER_TEXT,
    DISCORD_FETCH,
    SOUND,
    BROADCAST,

    // ---- ショップ系 ----
    OPEN_SHOP,
    OPEN_POINT_SHOP,

    // ---- 専用エンジン ----
    OPEN_UTILITY,
    OPEN_MACRO,
    OPEN_INFO,
    OPEN_CHANNEL,
    OPEN_SOCIALLIKES,
    OPEN_CARBUILDER,
    OPEN_LINKS,
    OPEN_SELL,

    // ---- 未定義 ----
    UNKNOWN
}

data class MenuAction(
    val type: ActionType,
    val params: Map<String, Any> = emptyMap(),
    val success: List<MenuAction> = emptyList(),
    val fail: List<MenuAction> = emptyList()
) {
    fun getString(key: String, default: String = ""): String =
        params[key]?.toString() ?: default
    fun getInt(key: String, default: Int = 0): Int =
        params[key]?.toString()?.toIntOrNull() ?: default
    fun getBoolean(key: String, default: Boolean = false): Boolean =
        params[key]?.toString()?.toBooleanStrictOrNull() ?: default
}

// ============================================================
// プレイヤー状態管理
// ============================================================

data class PlayerMenuState(
    val menuId: String,
    val page: Int = 0,
    val isEditing: Boolean = false,
    val selectedItemKey: String? = null
)

// ============================================================
// コマンドマクロ
// ============================================================

data class PlayerMacro(
    val id: String,
    val name: String,
    val ownerUUID: String,
    val commands: List<String>,
    val cooldownSeconds: Int = 3
)