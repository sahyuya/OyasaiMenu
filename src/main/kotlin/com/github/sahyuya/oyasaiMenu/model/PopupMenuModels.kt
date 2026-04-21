package com.github.sahyuya.oyasaiMenu.model

import org.bukkit.Material

// ============================================================
// ポップアップメニュー定義モデル (YAML駆動)
// ============================================================

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
 *       enchanted: false               # true = 隠しエンチャントグロー
 *       actions:
 *         - player_cmd: "leave 2"      # プレイヤーコマンド実行
 *         - console_cmd: "..."         # コンソールコマンド (%player% 置換)
 *         - url: "https://..."         # URLをチャットに表示
 *         - chat_paste: "/annnai "     # テキストをチャット欄に貼り付け
 *         - open_popup: "channel"      # 別ポップアップを開く
 *         - open_shop: "blocks"        # ショップカテゴリを開く
 *         - open_sell: true            # SellEngine を開く
 *         - open_macro: true           # MacroEngine を開く
 *         - open_menu: "root"          # YAMLメニューを開く
 *         - close: true               # GUIを閉じる
 */
data class PopupMenuDef(
    val id: String,
    val title: String,
    val glass: Material,
    /** ナビバーで強調するスロット番号 (45〜53)。-1 なら強調なし */
    val navActive: Int,
    val items: List<PopupItem>
)

/**
 * ポップアップメニュー内の1アイテム。
 *
 * @param key       YAML キー (デバッグ用)
 * @param slot      配置スロット (0〜44。45〜53はナビバー専用)
 * @param icon      表示マテリアル
 * @param customTexture カスタムヘッドのテクスチャハッシュ (icon=PLAYER_HEAD 時のみ有効)
 * @param name      表示名
 * @param lore      説明文リスト
 * @param enchanted true = 隠しエンチャントグロー効果付き
 * @param actions   クリック時のアクションリスト
 */
data class PopupItem(
    val key: String,
    val slot: Int,
    val icon: Material,
    val customTexture: String?,
    val name: String,
    val lore: List<String>,
    val enchanted: Boolean,
    val actions: List<PopupAction>
)

/**
 * ポップアップアイテムのアクション定義。
 * type が種別を示し、value がパラメータ。
 */
data class PopupAction(val type: PopupActionType, val value: String)

enum class PopupActionType {
    PLAYER_CMD,     // プレイヤーコマンド実行 (value = コマンド文字列, / なし)
    CONSOLE_CMD,    // コンソールコマンド実行 (value, %player% 置換)
    URL,            // URLをチャットに表示 (value = URL)
    CHAT_PASTE,     // テキストをチャットに表示 (value = テキスト)
    OPEN_POPUP,     // 別ポップアップを開く (value = popup ID)
    OPEN_SHOP,      // ショップカテゴリを開く (value = category ID, 空ならshop/index)
    OPEN_SELL,        // SellEngine を開く
    OPEN_MACRO,       // MacroEngine を開く
    OPEN_POINT_SHOP,  // PointShopEngine を開く (value = category ID, 空なら最初のカテゴリ)
    OPEN_MENU,        // YAML メニューを開く (value = menu ID)
    CLOSE             // GUIを閉じる
}