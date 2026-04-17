package com.github.sahyuya.oyasaiMenu

import com.github.sahyuya.oyasaiMenu.command.*
import com.github.sahyuya.oyasaiMenu.config.MenuConfigLoader
import com.github.sahyuya.oyasaiMenu.engine.ActionEngine
import com.github.sahyuya.oyasaiMenu.engine.MenuEngine
import com.github.sahyuya.oyasaiMenu.macro.MacroManager
import com.github.sahyuya.oyasaiMenu.shop.EconomyManager
import com.github.sahyuya.oyasaiMenu.shop.SellMenuEngine
import com.github.sahyuya.oyasaiMenu.shop.ShopConfigLoader
import com.github.sahyuya.oyasaiMenu.shop.ShopEngine
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerLoginEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.plugin.java.JavaPlugin
import java.util.UUID

@Suppress("UnstableApiUsage")
class OyasaiMenu : JavaPlugin(), Listener {

    lateinit var menuConfigLoader: MenuConfigLoader
    lateinit var menuEngine: MenuEngine
    lateinit var actionEngine: ActionEngine
    lateinit var macroManager: MacroManager
    lateinit var shopConfigLoader: ShopConfigLoader
    lateinit var shopEngine: ShopEngine
    lateinit var sellMenuEngine: SellMenuEngine

    override fun onEnable() {
        saveDefaultConfig()

        // コンポーネント初期化
        menuConfigLoader  = MenuConfigLoader(this)
        menuEngine        = MenuEngine(this)
        actionEngine      = ActionEngine(this)
        macroManager      = MacroManager(this)
        shopConfigLoader  = ShopConfigLoader(this)
        shopEngine        = ShopEngine(this)
        sellMenuEngine    = SellMenuEngine(this)

        // データロード
        menuConfigLoader.loadAll()
        shopConfigLoader.loadAll()
        EconomyManager.init(this)

        // Paper 式コマンド登録
        lifecycleManager.registerEventHandler(LifecycleEvents.COMMANDS) { event ->
            val reg = event.registrar()

            // /menu (/m)
            reg.register("menu",     "メニューを開く",           listOf("m"),        MenuCommand(this))
            // /menuedit
            reg.register("menuedit", "メニューを編集モードで開く", emptyList(),        MenuAdminCommand(this))
            // /shop (/sh) — カテゴリ指定でショップを直接開く
            reg.register("shop",     "ショップを開く",            listOf("sh"),       ShopCommand(this))
            // /sell — 一括売却GUI、またはその場売却
            reg.register("sell",     "アイテムを売却する",         emptyList(),        SellCommand(this))
        }

        // イベントリスナー登録
        server.pluginManager.registerEvents(menuEngine,     this)
        server.pluginManager.registerEvents(shopEngine,     this)
        server.pluginManager.registerEvents(sellMenuEngine, this)
        server.pluginManager.registerEvents(this,           this)

        logger.info("OyasaiMenu が有効になりました。" +
                "メニュー: ${menuConfigLoader.getMenuCount()} / " +
                "ショップカテゴリ: ${shopConfigLoader.getAllCategories().size}")
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
        menuConfigLoader.loadAll()
        shopConfigLoader.reload()
        EconomyManager.init(this)
        logger.info("OyasaiMenu をリロードしました。")
    }
}