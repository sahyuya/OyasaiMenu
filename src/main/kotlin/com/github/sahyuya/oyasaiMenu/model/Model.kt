package com.github.sahyuya.oyasaiMenu.model

import org.bukkit.Material

// ============================================================
// メニュー定義 (YAML 1ファイル = 1 MenuDefinition)
// ============================================================

/**
 * 1つのメニュー画面を表すデータクラス。
 * YAML の "menu:" セクションと "items:" セクションを合わせたもの。
 */
data class MenuDefinition(
    val id: String,
    val title: String,
    val size: Int = 54,
    val items: Map<String, MenuItemDefinition> = emptyMap()
)

// ============================================================
// メニュー内の各アイテム定義
// ============================================================

/**
 * メニュー内に配置する1つのアイテムを表す。
 * template フィールドで継承元テンプレートIDを持つ。
 */
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
 * アクションの種類を表す列挙型。
 * 設計書の "actions:" セクションに対応。
 */
enum class ActionType {
    OPEN_MENU,
    RUN_COMMAND,
    RUN_PLAYER_COMMAND,
    CHECK_PERMISSION,
    MESSAGE,
    CLOSE_MENU,
    MACRO_EXECUTE,
    PLACEHOLDER_TEXT,
    DISCORD_FETCH,
    OPEN_SHOP,
    SOUND,
    BROADCAST,
    UNKNOWN
}

/**
 * 1つのアクションを表す汎用データクラス。
 * check_permission の success/fail は再帰的なリストで表現する。
 */
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

/** プレイヤーが現在開いているメニューの状態 */
data class PlayerMenuState(
    val menuId: String,
    val page: Int = 0,
    val isEditing: Boolean = false,
    val selectedItemKey: String? = null
)

// ============================================================
// コマンドマクロ
// ============================================================

/**
 * プレイヤーが自分で作成・実行できるコマンドマクロ。
 * YAML への永続化は MacroManager が担当する。
 */
data class PlayerMacro(
    val id: String,
    val name: String,
    val ownerUUID: String,
    val commands: List<String>,
    val cooldownSeconds: Int = 3
)