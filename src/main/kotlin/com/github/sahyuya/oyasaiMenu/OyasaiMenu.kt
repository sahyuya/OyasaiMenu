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
    lateinit var popupMenuEngine: PopupMenuEngine   // 汎用ポップアップエンジン
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
    fun onPlayerQuit(event: PlayerQuitEvent) = macroManager.unloadPlayer(event.player.uniqueId)

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
        logger.info("OyasaiMenu をリロードしました。")
    }
}
