package com.github.sahyuya.oyasaiMenu

import com.github.sahyuya.oyasaiMenu.command.*
import com.github.sahyuya.oyasaiMenu.engine.*
import com.github.sahyuya.oyasaiMenu.loader.*
import com.github.sahyuya.oyasaiMenu.manager.*
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerLoginEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.plugin.java.JavaPlugin

/**
 * OyasaiMenu エントリポイント
 *
 * ■ アーキテクチャ
 *   描画・クリック処理 → Kotlin エンジン
 *   メニュー内容・コマンド → YAML ファイル (menus/popup/.yml)
 *
 * ■ engine/ (純粋なロジック)
 *   MenuEngine, ActionEngine       — YAML メニュー描画
 *   PopupMenuEngine                — YAML 駆動ポップアップ全般
 *                                    (channel/sellmenu/shopindex/sociallikes/carbuilder/utility/macromenu/links/vtpbiome)
 *   ShopEngine, SellEngine         — ショップ/売却の複雑なロジック
 *   PointShopEngine, MacroEngine   — ポイントショップ/マクロ管理
 *   AdminEngine                    — 管理者メニュー
 *   NavBar (object)                — ナビバー共通描画
 *
 * ■ loader/
 *   MenuLoader, ShopLoader, PointShopLoader, PopupMenuLoader
 *
 * ■ manager/
 *   EconomyManager, MacroManager, TokenCurrencyManager, AnnouncementManager
 *
 * ■ util/
 *   GuiUtil   — makeItem / comp / c など GUI 共通処理
 *   CustomHead — カスタムスキンヘッド生成
 */
@Suppress("UnstableApiUsage")
class OyasaiMenu : JavaPlugin(), Listener {

    // ---- Loaders ----
    lateinit var menuLoader: MenuLoader
    lateinit var shopLoader: ShopLoader
    lateinit var pointShopLoader: PointShopLoader
    lateinit var popupMenuLoader: PopupMenuLoader

    // ---- Managers ----
    lateinit var macroManager: MacroManager
    lateinit var announcementManager: AnnouncementManager

    // ---- Engines ----
    lateinit var menuEngine: MenuEngine
    lateinit var actionEngine: ActionEngine
    lateinit var popupMenuEngine: PopupMenuEngine
    lateinit var shopEngine: ShopEngine
    lateinit var sellEngine: SellEngine
    lateinit var pointShopEngine: PointShopEngine
    lateinit var macroEngine: MacroEngine
    lateinit var adminEngine: AdminEngine

    override fun onEnable() {
        saveDefaultConfig()

        // Loaders
        menuLoader       = MenuLoader(this)
        shopLoader       = ShopLoader(this)
        pointShopLoader  = PointShopLoader(this)
        popupMenuLoader  = PopupMenuLoader(this)

        // Managers
        macroManager         = MacroManager(this)
        announcementManager  = AnnouncementManager(this)

        // Engines
        menuEngine       = MenuEngine(this)
        actionEngine     = ActionEngine(this)
        popupMenuEngine  = PopupMenuEngine(this)
        shopEngine       = ShopEngine(this)
        sellEngine       = SellEngine(this)
        pointShopEngine  = PointShopEngine(this)
        macroEngine      = MacroEngine(this)
        adminEngine      = AdminEngine(this)

        // データロード
        menuLoader.loadAll()
        shopLoader.loadAll()
        pointShopLoader.loadAll()
        popupMenuLoader.loadAll()
        announcementManager.loadAll()
        EconomyManager.init(this)
        TokenCurrencyManager.init(this)
        CooldownManager.init(this)

        // Paper 式コマンド登録
        lifecycleManager.registerEventHandler(LifecycleEvents.COMMANDS) { event ->
            val reg = event.registrar()
            reg.register("menu",       "メニューを開く",              listOf("m"),       MenuCommand(this))
            reg.register("menuedit",   "メニューを編集モードで開く",   emptyList(),       MenuAdminCommand(this))
            reg.register("shop",       "ショップを開く",               listOf("sh"),      ShopCommand(this))
            reg.register("sell",       "アイテムを売却する",           emptyList(),       SellCommand(this))
            reg.register("pshop",      "ポイントショップを開く",        listOf("ps"),      PointShopCommand(this))
            reg.register("macro",      "コマンドマクロを管理・実行する", emptyList(),      MacroCommand(this))
            reg.register("adminmenu",  "管理者メニューを開く",          listOf("admenu"), AdminMenuCommand(this))
            reg.register("oyasaimenu", "OyasaiMenu 管理コマンド",       listOf("om"),     OyasaiMenuCommand(this))
        }

        // イベントリスナー登録
        listOf(
            menuEngine, popupMenuEngine,
            shopEngine, sellEngine, pointShopEngine,
            macroEngine, adminEngine, this
        ).forEach { server.pluginManager.registerEvents(it as org.bukkit.event.Listener, this) }

        logger.info(
            "OyasaiMenu 起動完了 | メニュー:${menuLoader.getMenuCount()} " +
            "ショップ:${shopLoader.getAllCategories().size} " +
            "Pショップ:${pointShopLoader.getAllCategories().size} " +
            "Popup:${popupMenuLoader.let { _ -> "loaded" }}"
        )
    }

    override fun onDisable() {
        macroManager.saveAll()
        logger.info("OyasaiMenu を無効化しました。")
    }

    @EventHandler
    fun onPlayerLogin(event: PlayerLoginEvent) = macroManager.loadPlayer(event.player.uniqueId)

    @EventHandler
    fun onPlayerQuit(event: PlayerQuitEvent) {
        macroManager.unloadPlayer(event.player.uniqueId)
        CooldownManager.remove(event.player.uniqueId)
    }

    fun reload() {
        reloadConfig()
        menuEngine.clearCache()
        server.onlinePlayers.forEach { p ->
            macroManager.savePlayer(p.uniqueId)
            macroManager.loadPlayer(p.uniqueId)
        }
        menuLoader.loadAll()
        shopLoader.reload()
        pointShopLoader.reload()
        popupMenuLoader.reload()
        announcementManager.reload()
        EconomyManager.init(this)
        TokenCurrencyManager.init(this)
        CooldownManager.reload(this)
        logger.info("OyasaiMenu をリロードしました。")
    }
}