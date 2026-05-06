package com.github.sahyuya.oyasaiMenu.model

import org.bukkit.Material

/**
 * menus/popup/.yml から読み込む1つのポップアップメニュー定義。
 *
 * YAML の構造 (例: menus/popup/channel.yml):
 *
 *   title: "&cチャンネルメニュー"
 *   glass: RED_STAINED_GLASS_PANE     # 空スロットの埋めるガラスの色
 *   nav_active: 46                     # ナビバーで強調するスロット番号
 *
 *   items:
 *     global:
 *       slot: 36
 *       icon: NAME_TAG
 *       name: "&aグローバルチャンネル"
 *       lore:
 *         - "&7説明文"
 *       enchanted: false       # true = 隠しエンチャントグロー
 *       actions:
 *         - player_cmd: "leave 2"          # プレイヤーコマンド実行
 *         - console_cmd: "..."             # コンソールコマンド (%player% 置換)
 *         - op_player_cmd: "/ch st"        # プレイヤーコマンド実行 (OPのみ)
 *         - url: "https://..."             # URLをチャットに表示
 *         - chat_paste: "/annnai "         # テキストをチャット欄に貼り付け
 *         - suggest_command: "/annaimode " # チャット欄にコマンドをサジェスト
 *         - open_popup: "channel"          # 別ポップアップを開く
 *         - open_shop: "blocks"            # ショップカテゴリを開く
 *         - open_sell: true                # SellEngine を開く
 *         - open_macro: true               # MacroEngine を開く
 *         - open_menu: "root"              # YAMLメニューを開く
 *         - close: true                    # GUIを閉じる
 */

data class PopupMenuDef(
    val id: String,
    val title: String,
    val glass: Material,
    val navActive: Int,
    val items: List<PopupItem>
)

/**
 * ポップアップメニュー内の1アイテム。
 *
 * @param opOnly true の場合、OP権限のあるプレイヤーにのみ表示・クリック可
 */
data class PopupItem(
    val key: String,
    val slot: Int,
    val icon: Material,
    val customTexture: String?,
    val name: String,
    val lore: List<String>,
    val enchanted: Boolean,
    val actions: List<PopupAction>,
    val opOnly: Boolean = false
)

data class PopupAction(val type: PopupActionType, val value: String)

enum class PopupActionType {
    PLAYER_CMD,         // プレイヤーコマンド実行 (全員)
    CONSOLE_CMD,        // コンソールコマンド実行 (全員)
    OP_PLAYER_CMD,      // プレイヤーコマンド実行 (OPのみ)
    URL,                // URLをチャットに表示
    CHAT_PASTE,         // テキストをチャットに表示
    SUGGEST_COMMAND,    // チャット欄にコマンドをサジェスト
    OPEN_POPUP,
    OPEN_SHOP,
    OPEN_SELL,
    OPEN_MACRO,
    OPEN_POINT_SHOP,
    OPEN_MENU,
    CLOSE
}