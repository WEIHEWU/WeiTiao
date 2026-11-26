package com.weitiao.tuning.tuning

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import com.weitiao.tuning.core.RootShell
import com.weitiao.tuning.core.TuningConfig
import kotlin.math.abs

/**
 * 专为 One UI 及高版本内核优化的亮屏快充控制器。
 * 通过 Root 屏蔽温控节点、解除 SIOP 限流，并直接从硬件 sysfs 节点读取精准电压、电流与真实电池温度。
 */
object FastChargeController {

    /**
     * 对齐 S8Tweak 原版完整 17 个温控节点（电池/充电路由 + AP/CP/PA/WF/机身热敏 + 屏幕 + GPU + /sys/class/thermal）。
     * 仅在 AC 有线充电期间统一 `chmod 0000` 屏蔽以彻底解除三星 SSRM/SIOP 亮屏限流；
     * 拔线、非 AC、满电 100% 或触发电池高温熔断时全部恢复（目录 0755，文件 0644）。
     */
    private val ALL_MANAGED_NODES = listOf(
        "/sys/class/sec/sec-ap-thermistor/temperature",
        "/sys/class/sec/sec-cp-thermistor/temperature",
        "/sys/class/sec/sec-pa-thermistor/temperature",
        "/sys/class/sec/sec-wf-thermistor/temperature",
        "/sys/class/sec/sec-thermistor/temperature",
        "/sys/devices/platform/sec-thermistor/temperature",
        "/sys/class/power_supply/battery/temp",
        "/sys/class/power_supply/battery/batt_temp",
        "/sys/class/power_supply/battery/batt_wpc_temp",
        "/sys/class/power_supply/battery/blkt_temp",
        "/sys/class/power_supply/battery/chg_temp",
        "/sys/class/power_supply/battery/dchg_temp",
        "/sys/class/power_supply/battery/temp_ambient",
        "/sys/class/power_supply/ac/temp",
        "/sys/class/lcd/panel/temperature",
        "/sys/kernel/gpu/gpu_tmu",
        "/sys/class/thermal",
    )

    private val ALL_MANAGED_SET = ALL_MANAGED_NODES.toSet()

    /**
     * 在持久化 su 会话中缓存 uid，避免每次轮询都 fork 一次 `/system/bin/id`；
     * 若回退到单次 su 进程，`__wt_uid` 为空仍会自动执行一次 `id -u`。
     */
    private const val UID_PREAMBLE =
        "[ \"\${__wt_uid:-}\" = \"0\" ] || __wt_uid=\$(id -u); echo __UID__\$__wt_uid; "

    internal const val RUNTIME_PREFS_NAME = "fast_charge_runtime"
    private const val KEY_NODES_MAY_BE_BLOCKED = "nodes_may_be_blocked"

    @Volatile private var nodesBlockedHint = false

    private fun runtimePrefs(context: Context) =
        context.getSharedPreferences(RUNTIME_PREFS_NAME, Context.MODE_PRIVATE)

    fun isNodesMayBeBlocked(context: Context): Boolean =
        nodesBlockedHint || runtimePrefs(context).getBoolean(KEY_NODES_MAY_BE_BLOCKED, false)

    fun markNodesMayBeBlocked(context: Context, blocked: Boolean) {
        nodesBlockedHint = blocked
        runtimePrefs(context).edit().putBoolean(KEY_NODES_MAY_BE_BLOCKED, blocked).apply()
    }

    const val FULL_CHARGE_RELEASE_LEVEL = 100
    const val FULL_CHARGE_RESUME_LEVEL = 98
    const val CHG_HARD_LIMIT_TENTHS = 560 // 充电 IC 56.0°C 硬件安全红线
    const val CHG_COOLDOWN_TENTHS = 520   // 充电 IC 降温 4.0°C (52.0°C) 恢复
    const val USB_HARD_LIMIT_TENTHS = 470 // USB 尾插 47.0°C 硬件安全红线
    const val USB_COOLDOWN_TENTHS = 440   // USB 尾插降温 3.0°C (44.0°C) 恢复

    fun isAcPlugged(pluggedType: Int): Boolean = pluggedType == BatteryManager.BATTERY_PLUGGED_AC

    /**
     * 满电自动归还温控滞回判定：
     * - 电量 >= 100% 时释放温控（返回 true），避免 100% 涓流保护后仍锁死节点；
     * - 已释放后在 99% 保持释放，直至回落至 <= 98%（2% 滞回）才重新允许亮屏快充。
     */
    fun nextFullChargeReleased(level: Int, previouslyReleased: Boolean): Boolean = when {
        level >= FULL_CHARGE_RELEASE_LEVEL -> true
        previouslyReleased && level > FULL_CHARGE_RESUME_LEVEL -> true
        else -> false
    }

    /** 判定是否处于「已充满/停充保护」状态（兼容 100%/99% 滞回及系统 BATTERY_STATUS_FULL）。 */
    fun isFullChargeStopped(level: Int, status: Int, previouslyReleased: Boolean): Boolean =
        status == BatteryManager.BATTERY_STATUS_FULL || nextFullChargeReleased(level, previouslyReleased)

    /** 统一判定亮屏快充当前是否需要工作（仅在 AC 有线充电器接入且未进入满电停充保护时激活）。 */
    fun isActivelyChargingForFastCharge(
        pluggedType: Int,
        level: Int,
        status: Int,
        previouslyFullReleased: Boolean,
    ): Boolean = isAcPlugged(pluggedType) && !isFullChargeStopped(level, status, previouslyFullReleased)

    /**
     * 统一策略判定：仅在 AC 有线充电、未过热且未处于满电释放态时才允许屏蔽全部 17 个温控节点（不再因息屏撤销）。
     */
    fun shouldBlockChargingNodes(
        isAcCharging: Boolean,
        isOverheated: Boolean,
        fullChargeReleased: Boolean,
    ): Boolean = isAcCharging && !isOverheated && !fullChargeReleased

    internal data class NodeBlockReport(
        val allChargingBlocked: Boolean?,
        val anyNodeBlocked: Boolean,
    )

    data class Result(val success: Boolean, val detail: String)

    data class BatteryMetrics(
        val currentMa: Int,
        val voltageMv: Int,
        val powerWatts: Double,
        val temperatureTenths: Int, // 电池有效温度（0.1°C 单位，如 385 代表 38.5°C）
        val maxChipTempTenths: Int = 0, // 保留字段兼容历史数据结构（熔断不再计入 AP/PA 芯片温度）
        val chgTempTenths: Int = 0, // 充电 IC 温度（0.1°C 单位）
        val usbTempTenths: Int = 0, // USB 尾插温度（0.1°C 单位）
    )

    /**
     * 判定是否触发高温安全熔断（三路独立硬件安全红线，不含 AP/PA 芯片温度以防误触发）：
     * 1. 电芯真实温度 >= 用户设定阈值（默认 44°C），降温 3.0°C 后恢复；
     * 2. 充电 IC 温度 (chg_temp) >= 56.0°C，降温 4.0°C (<= 52.0°C) 后恢复；
     * 3. USB 尾插温度 (usb_temp) >= 47.0°C，降温 3.0°C (<= 44.0°C) 后恢复；
     * 已处于熔断态且所有安全传感器读温失效（<= 0）时保持 fail-safe 熔断态。
     */
    fun isOverheated(
        metrics: BatteryMetrics,
        cutoffCelsius: Int,
        previouslyOverheated: Boolean,
    ): Boolean {
        val clampedCutoff = cutoffCelsius.coerceIn(
            TuningConfig.MIN_THERMAL_CUTOFF_CELSIUS,
            TuningConfig.MAX_THERMAL_CUTOFF_CELSIUS,
        )
        val battOverheatTenths = clampedCutoff * 10
        val battCoolDownTenths = battOverheatTenths - 30 // 降温 3.0°C 恢复
        val battTemp = metrics.temperatureTenths
        val chgTemp = metrics.chgTempTenths
        val usbTemp = metrics.usbTempTenths

        return if (!previouslyOverheated) {
            battTemp >= battOverheatTenths ||
                chgTemp >= CHG_HARD_LIMIT_TENTHS ||
                usbTemp >= USB_HARD_LIMIT_TENTHS
        } else {
            val allUnreadable = battTemp <= 0 && chgTemp <= 0 && usbTemp <= 0
            allUnreadable ||
                battTemp > battCoolDownTenths ||
                chgTemp > CHG_COOLDOWN_TENTHS ||
                usbTemp > USB_COOLDOWN_TENTHS
        }
    }

    /**
     * 屏蔽全部 17 个温控节点并启用内核软钳制 + 直锁 `siop_level=100` + 原生 SSRM 调试禁用标志。
     */
    fun blockTemperatureNodes(context: Context? = null): Result {
        context?.let { markNodesMayBeBlocked(it, true) }
        val command = buildString {
            append(UID_PREAMBLE)
            append("if [ -e /sys/class/power_supply/battery/siop_level ]; then echo 100 > /sys/class/power_supply/battery/siop_level 2>/dev/null; fi; ")
            append("if [ -e /sys/class/power_supply/battery/batt_temp_test ]; then echo 'b 350' > /sys/class/power_supply/battery/batt_temp_test 2>/dev/null; fi; ")
            append("if [ -d /data/data/com.sec.android.sdhms ]; then touch /data/data/com.sec.android.sdhms/ssrm.disable /data/data/com.sec.android.sdhms/sdhms_cooldown_disable 2>/dev/null; fi; ")
            append("f000=\"\"; ")
            for (node in ALL_MANAGED_NODES) {
                append("[ -e '$node' ] && f000=\"\$f000 $node\"; ")
            }
            append("[ -n \"\$f000\" ] && chmod 0000 \$f000 2>/dev/null; ")
            append("echo __DONE__")
        }
        val res = runRootCommand(command)
        if (!res.success && context != null && isAnyTemperatureNodeBlocked() == false) {
            markNodesMayBeBlocked(context, false)
        }
        return res
    }

    /**
     * 恢复全部 17 个温控节点权限：目录使用 0755，sysfs 传感器文件使用 0644，并释放内核 `batt_temp_test` 钳制与 SSRM 标志。
     */
    fun restoreTemperatureNodes(context: Context? = null): Result {
        val command = buildString {
            append(UID_PREAMBLE)
            append("d755=\"\"; f644=\"\"; ")

            for (node in ALL_MANAGED_NODES) {
                append("if [ -d '$node' ]; then d755=\"\$d755 $node\"; elif [ -e '$node' ]; then f644=\"\$f644 $node\"; fi; ")
            }

            append("[ -n \"\$d755\" ] && chmod 0755 \$d755 2>/dev/null; ")
            append("[ -n \"\$f644\" ] && chmod 0644 \$f644 2>/dev/null; ")
            append("if [ -e /sys/class/power_supply/battery/batt_temp_test ]; then echo 'b -300' > /sys/class/power_supply/battery/batt_temp_test 2>/dev/null; echo 'w -300' > /sys/class/power_supply/battery/batt_temp_test 2>/dev/null; fi; ")
            append("rm -f /data/data/com.sec.android.sdhms/ssrm.disable /data/data/com.sec.android.sdhms/sdhms_cooldown_disable 2>/dev/null; ")
            append("echo __DONE__")
        }
        val res = runRootCommand(command)
        if (res.success && context != null) {
            markNodesMayBeBlocked(context, false)
        }
        return res
    }

    /**
     * 复核全部 17 个受管温控节点当前是否均处于被屏蔽状态。
     */
    fun areTemperatureNodesBlocked(): Boolean? = queryNodeBlockReport()?.allChargingBlocked

    /**
     * 检查是否存在任何一个受管温控节点处于 0000 锁死状态（用于拔线/非 AC/满电/过热态校验自愈）。
     */
    fun isAnyTemperatureNodeBlocked(): Boolean? = queryNodeBlockReport()?.anyNodeBlocked

    private fun queryNodeBlockReport(): NodeBlockReport? {
        val command = buildString {
            append(UID_PREAMBLE)
            append("if [ -e /sys/class/power_supply/battery/siop_level ]; then { read -r __s < /sys/class/power_supply/battery/siop_level; } 2>/dev/null; [ \"\${__s:-100}\" -lt 100 ] 2>/dev/null && echo 100 > /sys/class/power_supply/battery/siop_level 2>/dev/null; fi; ")
            append("stat -c '%a:%n' ")
            ALL_MANAGED_NODES.joinTo(this, separator = " ") { "'${it.replace("'", "'\\''")}'" }
            append(" 2>/dev/null; echo __DONE__")
        }
        val (exitCode, stdout) = RootShell.exec(command, timeoutSeconds = 6)
        if (exitCode != 0) return null
        return parseNodeBlockReport(stdout)
    }

    internal fun parseNodeBlockReport(stdout: String): NodeBlockReport? {
        if (!stdout.contains("__UID__0") || !stdout.contains("__DONE__")) return null
        var anyBlocked = false
        var hasOpen = false

        stdout.lineSequence().forEach { rawLine ->
            val line = rawLine.trim()
            val sep = line.indexOf(':')
            if (sep <= 0) return@forEach
            val mode = line.substring(0, sep)
            val path = line.substring(sep + 1)
            if (path !in ALL_MANAGED_SET) return@forEach
            val isZeroMode = mode == "0" || mode == "00" || mode == "000" || mode == "0000"
            if (isZeroMode) {
                anyBlocked = true
            } else {
                hasOpen = true
            }
        }

        val allChargingBlocked = when {
            !anyBlocked && !hasOpen -> null
            else -> anyBlocked && !hasOpen
        }
        return NodeBlockReport(
            allChargingBlocked = allChargingBlocked,
            anyNodeBlocked = anyBlocked,
        )
    }

    /** 开启三星系统设置快充开关并解除轻度性能限制 */
    fun enableSamsungFastChargingSettings(): Result {
        val command = buildString {
            append(UID_PREAMBLE)
            append("settings put system adaptive_fast_charging '1'; ")
            append("settings put system wireless_fast_charging '1'; ")
            append("settings put system super_fast_charging '1'; ")
            append("settings put system fast_charging '1'; ")
            append("settings put global restricted_device_performance '0,0' 2>/dev/null; ")
            append("echo __DONE__")
        }
        return runRootCommand(command)
    }

    /**
     * 纯免解锁硬件指标读取：通过 0-fork 的 mksh 内建 `read -r` 读取高精度微安电流、电压、
     * 未被屏蔽/未被 `batt_temp_test` 钳制的真实电池温度，以及充电 IC (`chg_temp`) 与 USB 尾插 (`usb_temp`) 温度；
     * 当直读电池节点为空时由系统广播 `EXTRA_TEMPERATURE` 兜底。
     */
    fun readBatteryMetrics(
        context: Context,
        @Suppress("UNUSED_PARAMETER") cutoffCelsius: Int = TuningConfig.DEFAULT_THERMAL_CUTOFF_CELSIUS,
        cachedBatteryIntent: Intent? = null,
    ): BatteryMetrics {
        var currentMa = 0
        var voltageMv = 0
        var tempTenths = 0
        var chgTempTenths = 0
        var usbTempTenths = 0

        if (RootShell.hasRoot()) {
            val (exitCode, stdout) = RootShell.exec(
                UID_PREAMBLE +
                    "cua=\"\"; { read -r cua < /sys/class/power_supply/battery/batt_current_ua_now; } 2>/dev/null; " +
                    "c=\"\"; { read -r c < /sys/class/power_supply/battery/current_now; } 2>/dev/null; " +
                    "v=\"\"; { read -r v < /sys/class/power_supply/battery/voltage_now; } 2>/dev/null; " +
                    "t1=\"\"; { read -r t1 < /sys/class/power_supply/battery/batt_temp_raw; } 2>/dev/null; " +
                    "t2=\"\"; { read -r t2 < /sys/class/sec/battery/batt_temp; } 2>/dev/null; " +
                    "t3=\"\"; { read -r t3 < /sys/class/sec/sec-cf-thermistor/temperature; } 2>/dev/null; " +
                    "t4=\"\"; { read -r t4 < /sys/class/sec/sec-lp-thermistor/temperature; } 2>/dev/null; " +
                    "t5=\"\"; { read -r t5 < /sys/class/power_supply/battery/wpc_temp; } 2>/dev/null; " +
                    "t6=\"\"; { read -r t6 < /sys/class/power_supply/battery/lrp; } 2>/dev/null; " +
                    "tb1=\"\"; { read -r tb1 < /sys/class/power_supply/battery/batt_temp; } 2>/dev/null; " +
                    "tb2=\"\"; { read -r tb2 < /sys/class/power_supply/battery/temp; } 2>/dev/null; " +
                    "tchg=\"\"; { read -r tchg < /sys/class/power_supply/battery/chg_temp; } 2>/dev/null; " +
                    "tusb=\"\"; { read -r tusb < /sys/class/power_supply/battery/usb_temp; } 2>/dev/null; " +
                    "echo \"CUA=\$cua\"; echo \"C=\$c\"; echo \"V=\$v\"; " +
                    "echo \"T1=\$t1\"; echo \"T2=\$t2\"; echo \"T3=\$t3\"; echo \"T4=\$t4\"; echo \"T5=\$t5\"; echo \"T6=\$t6\"; " +
                    "echo \"TB1=\$tb1\"; echo \"TB2=\$tb2\"; echo \"TCHG=\$tchg\"; echo \"TUSB=\$tusb\"; " +
                    "echo __DONE__",
                timeoutSeconds = 4,
            )

            if (exitCode == 0 && stdout.contains("__UID__0")) {
                val rawUa = abs(taggedInt(stdout, "CUA") ?: 0)
                if (rawUa > 10_000) {
                    currentMa = rawUa / 1_000
                } else {
                    var rawCurrent = abs(taggedInt(stdout, "C") ?: 0)
                    if (rawCurrent > 100_000) rawCurrent /= 1_000      // 部分内核以 µA 上报
                    currentMa = rawCurrent
                }

                var rawVoltage = abs(taggedInt(stdout, "V") ?: 0)
                if (rawVoltage > 100_000) rawVoltage /= 1_000      // 部分内核以 µV 上报
                voltageMv = rawVoltage

                val t1 = normalizeTemp(taggedInt(stdout, "T1"))
                val t2 = normalizeTemp(taggedInt(stdout, "T2"))
                val t3 = normalizeTemp(taggedInt(stdout, "T3"))
                val t4 = normalizeTemp(taggedInt(stdout, "T4"))
                val t5 = normalizeTemp(taggedInt(stdout, "T5"))
                val t6 = normalizeTemp(taggedInt(stdout, "T6"))
                val tb1 = normalizeTemp(taggedInt(stdout, "TB1"))
                val tb2 = normalizeTemp(taggedInt(stdout, "TB2"))
                // 优先保留未受 batt_temp_test (350) 钳制影响的真实物理热敏读数
                val rawUnclamped = maxOf(t1, t2, t3, t4, t5, t6)
                val reportedBatt = maxOf(tb1, tb2)
                tempTenths = if (rawUnclamped > 0) maxOf(rawUnclamped, reportedBatt) else reportedBatt
                chgTempTenths = normalizeTemp(taggedInt(stdout, "TCHG"))
                usbTempTenths = normalizeTemp(taggedInt(stdout, "TUSB"))
            }
        }

        // 兜底方案：使用系统 BatteryManager API 与 ACTION_BATTERY_CHANGED 粘性广播
        if (currentMa == 0) {
            val bm = context.getSystemService(BatteryManager::class.java)
            if (bm != null) {
                var bmCurrent = abs(bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW))
                if (bmCurrent > 100_000) bmCurrent /= 1_000
                else if (bmCurrent > 10_000) bmCurrent /= 1_000
                if (bmCurrent in 10..20_000) {
                    currentMa = bmCurrent
                }
            }
        }

        if (voltageMv == 0 || tempTenths == 0) {
            val batteryStatus = cachedBatteryIntent
                ?: runCatching { context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) }.getOrNull()
            if (voltageMv == 0) {
                voltageMv = batteryStatus?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 3800) ?: 3800
            }
            if (tempTenths == 0) {
                val frameworkTemp = batteryStatus?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0
                val normalizedFramework = normalizeTemp(frameworkTemp)
                if (normalizedFramework > 0) {
                    tempTenths = normalizedFramework
                }
            }
        }

        val watts = (voltageMv.toDouble() * currentMa.toDouble()) / 1_000_000.0
        return BatteryMetrics(currentMa, voltageMv, watts, tempTenths, 0, chgTempTenths, usbTempTenths)
    }

    /** 从 `echo C=123` 这类带标签输出里按标签取值，节点缺失时返回 null */
    private fun taggedInt(stdout: String, tag: String): Int? {
        val prefix = "$tag="
        val line = stdout.lineSequence()
            .map { it.trim() }
            .firstOrNull { it.startsWith(prefix) } ?: return null
        val raw = line.removePrefix(prefix).trim()
        if (raw.isEmpty()) return null               // 节点不存在，echo 出来是空值
        return raw.toIntOrNull()
    }

    /**
     * 温度归一化到 0.1°C：兼容十分之一度、百分之一度与千分之一度三种上报口径。
     * 注意：严禁使用 abs()！三星温控节点被屏蔽或异常时会返回负值错误码（如 -888 即 -88.8°C），
     * 若取绝对值会把低温错误码当成正温，导致高温熔断器误判降温而重新锁死节点！
     */
    internal fun normalizeTemp(raw: Int?): Int {
        val value = raw ?: return 0
        if (value <= 0) return 0
        val normalized = when {
            value >= 10_000 -> value / 100
            value in 1_000..9_999 -> value / 10
            else -> value
        }
        return if (normalized in 50..850) normalized else 0
    }

    private fun runRootCommand(command: String): Result {
        val (exitCode, stdout) = RootShell.exec(command, timeoutSeconds = 6)
        return if (exitCode == 0 && stdout.contains("__UID__0")) {
            Result(true, "完成")
        } else {
            Result(false, if (exitCode != 0) "未获得 Root 权限（code=$exitCode）" else stdout.trim())
        }
    }
}
