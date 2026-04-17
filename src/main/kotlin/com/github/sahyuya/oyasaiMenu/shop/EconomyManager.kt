package com.github.sahyuya.oyasaiMenu.shop

import com.github.sahyuya.oyasaiMenu.OyasaiMenu
import net.milkbowl.vault.economy.Economy
import org.bukkit.Bukkit
import org.bukkit.entity.Player

/**
 * EconomyManager
 *
 * Vault の Economy API をラップするシングルトンヘルパー。
 * Vault / 経済プラグインが導入されていない場合でも
 * NullPointerException が発生しないよう全操作を安全に処理する。
 *
 * 使い方:
 *   EconomyManager.init(plugin)   // onEnable() で一度だけ呼ぶ
 *   EconomyManager.getBalance(player)
 *   EconomyManager.withdraw(player, amount)
 *   EconomyManager.deposit(player, amount)
 *
 * Vault が未導入の場合は全操作が失敗扱いになり、
 * プレイヤーには「経済プラグインが見つかりません」と通知される。
 */
object EconomyManager {

    private var economy: Economy? = null
    private var plugin: OyasaiMenu? = null

    fun init(p: OyasaiMenu) {
        plugin = p
        economy = setupEconomy()
        if (economy == null) {
            p.logger.warning("Vault の Economy プロバイダが見つかりません。ショップ機能は無効です。")
            p.logger.warning("Vault と対応する経済プラグイン (EssentialsX など) を導入してください。")
        } else {
            p.logger.info("Vault Economy: ${economy!!.name} を検出しました。")
        }
    }

    val isAvailable: Boolean get() = economy != null

    // ============================
    // 残高操作
    // ============================

    /** プレイヤーの残高を返す。Vault 未導入の場合は 0.0 */
    fun getBalance(player: Player): Double =
        economy?.getBalance(player) ?: 0.0

    /**
     * プレイヤーから指定額を引く。
     * @return 成功なら null、失敗なら理由メッセージ
     */
    fun withdraw(player: Player, amount: Double): String? {
        val eco = economy ?: return "&c経済プラグインが見つかりません。"
        if (amount <= 0) return "&c金額は1以上で指定してください。"
        val balance = eco.getBalance(player)
        if (balance < amount) {
            return "&c残高が不足しています。(所持: ${format(balance)} / 必要: ${format(amount)})"
        }
        val result = eco.withdrawPlayer(player, amount)
        return if (result.transactionSuccess()) null else "&c引き落とし処理に失敗しました。"
    }

    /**
     * プレイヤーに指定額を加える。
     * @return 成功なら null、失敗なら理由メッセージ
     */
    fun deposit(player: Player, amount: Double): String? {
        val eco = economy ?: return "&c経済プラグインが見つかりません。"
        if (amount <= 0) return null  // 0円の入金は成功扱い (売却不可アイテムの扱い)
        val result = eco.depositPlayer(player, amount)
        return if (result.transactionSuccess()) null else "&c入金処理に失敗しました。"
    }

    /** 金額を読みやすい形式にフォーマット (例: 1234.5 → "1,234.50") */
    fun format(amount: Double): String =
        economy?.format(amount) ?: String.format("%,.2f", amount)

    // ============================
    // セットアップ
    // ============================

    private fun setupEconomy(): Economy? {
        val rsp = Bukkit.getServicesManager().getRegistration(Economy::class.java)
        return rsp?.provider
    }
}