package com.weitiao.tuning.core

/**
 * Galaxy Store 数据清理与市场解锁工具。
 * 100% 对齐 S8Tweak nt3.java 原版行为，严禁破坏系统底层基带与物理 SIM 卡网络。
 */
object StoreCleaner {

    /** Galaxy Store 包名 */
    const val GALAXY_STORE = "com.sec.android.app.samsungapps"
    const val THEME_STORE = "com.samsung.android.themestore"

    /** root 一键清理 Galaxy Store 数据并强行停止进程，返回成功与否 */
    fun clearStoreDataRoot(): Boolean {
        if (!RootShell.hasRoot()) return false
        val (code, _) = RootShell.exec("pm clear $GALAXY_STORE && am force-stop $GALAXY_STORE")
        return code == 0
    }

    /**
     * 完全 100% 对齐 S8Tweak nt3.java 原版市场解锁注入：
     * 仅临时注入 gsm.* 属性并强杀商店，原版绝对没有 persist.omc.* 写入。
     */
    fun applyMarketUnlockExact(
        numeric: String,
        operator: String,
        countryIso: String,
    ): Boolean {
        if (!RootShell.hasRoot()) return false
        val cleanAlpha = operator.replace(" ", "")
        val cmd = buildString {
            append("setprop gsm.sim.operator.numeric $numeric; ")
            append("setprop gsm.operator.numeric $numeric; ")
            append("setprop gsm.sim.operator.iso-country $countryIso; ")
            append("setprop gsm.operator.iso-country $countryIso; ")
            append("setprop gsm.operator.alpha $cleanAlpha; ")
            append("setprop gsm.sim.operator.alpha $cleanAlpha; ")
            append("pkill -9 -f $GALAXY_STORE; am force-stop $GALAXY_STORE; ")
            append("pkill -9 -f com.android.vending; am force-stop com.android.vending; ")
        }
        val (code, _) = RootShell.exec(cmd)
        return code == 0
    }

    /**
     * 恢复物理 SIM 原生蜂窝网络属性：
     * 清理所有残留的 persist.omc.* 及 gsm.* 属性，并重启应用商店，
     * 保证基带与 SIM 管理器立即恢复物理 SIM 卡（如澳洲 Boost / Telstra）。
     */
    fun restoreNativeTelephonyProps(): Boolean {
        if (!RootShell.hasRoot()) return false
        val cmd = buildString {
            append("RP=\$(which resetprop 2>/dev/null || ls /data/adb/ksu/bin/resetprop /data/adb/magisk/resetprop /data/adb/ap/bin/resetprop 2>/dev/null | head -n1); ")
            append("if [ -n \"\$RP\" ]; then ")
            append("\$RP -p -d persist.omc.sales_code 2>/dev/null; ")
            append("\$RP -p -d persist.omc.country_code 2>/dev/null; ")
            append("\$RP -d gsm.sim.operator.numeric 2>/dev/null; ")
            append("\$RP -d gsm.operator.numeric 2>/dev/null; ")
            append("\$RP -d gsm.sim.operator.iso-country 2>/dev/null; ")
            append("\$RP -d gsm.operator.iso-country 2>/dev/null; ")
            append("\$RP -d gsm.operator.alpha 2>/dev/null; ")
            append("\$RP -d gsm.sim.operator.alpha 2>/dev/null; ")
            append("fi; ")
            append("pm clear $GALAXY_STORE; am force-stop $GALAXY_STORE; ")
            append("pkill -9 -f com.android.vending; am force-stop com.android.vending; ")
        }
        val (code, _) = RootShell.exec(cmd)
        return code == 0
    }

    /**
     * 清除历史版本误写的持久化 OMC 属性，防止干扰系统原生电信网络配置。
     */
    fun cleanupPollutedOmcProps(): Boolean {
        if (!RootShell.hasRoot()) return false
        val cmd = "RP=\$(which resetprop 2>/dev/null || ls /data/adb/ksu/bin/resetprop /data/adb/magisk/resetprop /data/adb/ap/bin/resetprop 2>/dev/null | head -n1); if [ -n \"\$RP\" ]; then \$RP -p -d persist.omc.sales_code 2>/dev/null; \$RP -p -d persist.omc.country_code 2>/dev/null; fi"
        val (code, _) = RootShell.exec(cmd)
        return code == 0
    }

    /** 跳转系统设置的应用信息页（无需 root） */
    fun buildAppInfoIntent(packageName: String): android.content.Intent =
        android.content.Intent(
            android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            android.net.Uri.fromParts("package", packageName, null),
        ).apply {
            addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        }

    /** 跳转 Galaxy Store 应用信息页 */
    fun buildStoreAppInfoIntent(): android.content.Intent =
        buildAppInfoIntent(GALAXY_STORE)
}
