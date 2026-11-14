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
     * 仅针对充电与电池限流的温控节点（亮屏快充仅屏蔽这些节点以解除亮屏充电限流）。
     * 严禁屏蔽 AP (CPU)、PA (射频功放)、GPU (gpu_tmu) 与 /sys/class/thermal，
     * 否则会导致三星 SSRM 误判整机处于 -88.8°C 极寒状态而彻底关闭 CPU/GPU/射频过热降频保护！
     */
    private val CHARGING_TEMPERATURE_NODES = listOf(
        "/sys/class/power_supply/battery/temp",
        "/sys/class/power_supply/battery/batt_temp",
        "/sys/class/power_supply/battery/batt_wpc_temp",
        "/sys/class/power_supply/battery/blkt_temp",
        "/sys/class/power_supply/battery/chg_temp",
        "/sys/class/power_supply/battery/dchg_temp",
        "/sys/class/power_supply/battery/temp_ambient",
        "/sys/class/power_supply/ac/temp",
    )

    /**
     * SoC / 处理器 / 射频功放 / GPU / 系统热区节点：
     * 永远保持可读（绝不 chmod 0000），并在每次操作时主动恢复权限，确保旧版本残留锁死节点自动自愈。
     */
    private val SOC_THERMAL_SAFETY_NODES = listOf(
        "/sys/class/sec/sec-ap-thermistor/temperature",
        "/sys/class/sec/sec-cp-thermistor/temperature",
        "/sys/class/sec/sec-pa-thermistor/temperature",
        "/sys/class/sec/sec-wf-thermistor/temperature",
        "/sys/class/sec/sec-thermistor/temperature",
        "/sys/devices/platform/sec-thermistor/temperature",
        "/sys/class/lcd/panel/temperature",
        "/sys/kernel/gpu/gpu_tmu",
        "/sys/class/thermal",
    )

    private val CHARGING_TEMPERATURE_SET = CHARGING_TEMPERATURE_NODES.toSet()
    private val SOC_THERMAL_SAFETY_SET = SOC_THERMAL_SAFETY_NODES.toSet()
    private val ALL_MANAGED_NODES = CHARGING_TEMPERATURE_NODES + SOC_THERMAL_SAFETY_NODES
    private val ALL_MANAGED_SET = ALL_MANAGED_NODES.toSet()

    /**
     * 在持久化 su 会话中缓存 uid，避免每次 5s/15s 轮询都 fork 一次 `/system/bin/id`；
     * 若回退到单次 su 进程，`__wt_uid` 为空仍会自动执行一次 `id -u`。
     */
    private const val UID_PREAMBLE =
        "[ \"\${__wt_uid:-}\" = \"0\" ] || __wt_uid=\$(id -u); echo __UID__\$__wt_uid; "

    internal data class NodeBlockReport(
        val allChargingBlocked: Boolean?,
        val anyNodeBlocked: Boolean,
    )

    data class Result(val success: Boolean, val detail: String)

    data class BatteryMetrics(
        val currentMa: Int,
        val voltageMv: Int,
        val powerWatts: Double,
        val temperatureTenths: Int, // 电池/机身有效温度（0.1°C 单位，如 385 代表 38.5°C）
        val maxChipTempTenths: Int = 0, // 充电IC/处理器/射频功放最高温度（0.1°C 单位，如 500 代表 50.0°C）
    )

    /**
     * 判定是否触发高温安全熔断：
     * 1. 电池/机身温度 >= 用户设定阈值（默认 42~44°C），降温 3.0°C 后恢复；
     * 2. 充电IC (CHG/DC) / 处理器 (AP) / 射频功放 (PA) 任一芯片温度 >= min(阈值+6°C, 50°C)，
     *    降温 4.0°C 后恢复，彻底杜绝电池尚在 43°C 但 AP/CHG 已飙升至 56~57°C 的热失控隐患。
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

        val chipCutoffCelsius = (clampedCutoff + 6).coerceAtMost(50)
        val chipOverheatTenths = chipCutoffCelsius * 10
        val chipCoolDownTenths = chipOverheatTenths - 40 // 芯片降温 4.0°C 恢复

        val battTemp = metrics.temperatureTenths
        val chipTemp = metrics.maxChipTempTenths

        return if (!previouslyOverheated) {
            (battTemp >= battOverheatTenths) || (chipTemp >= chipOverheatTenths)
        } else {
            val battStillHot = battTemp <= 0 || battTemp > battCoolDownTenths
            val chipStillHot = chipTemp > chipCoolDownTenths
            battStillHot || chipStillHot
        }
    }

    /**
     * 屏蔽三星充电限流温控节点（解除亮屏快充限流），
     * 同时强制确保 AP/PA/GPU/thermal 等核心芯片温控节点处于可读状态，保留系统 CPU/射频过热保护。
     * 批量合并为最多三次 chmod 调用（0755 / 0644 / 0000），避免对 17 个节点逐个 fork。
     */
    fun blockTemperatureNodes(): Result {
        val command = buildString {
            append(UID_PREAMBLE)
            append("d755=\"\"; f644=\"\"; f000=\"\"; ")

            for (node in SOC_THERMAL_SAFETY_NODES) {
                append("if [ -d '$node' ]; then d755=\"\$d755 $node\"; elif [ -e '$node' ]; then f644=\"\$f644 $node\"; fi; ")
            }

            for (node in CHARGING_TEMPERATURE_NODES) {
                append("[ -e '$node' ] && f000=\"\$f000 $node\"; ")
            }

            append("[ -n \"\$d755\" ] && chmod 0755 \$d755 2>/dev/null; ")
            append("[ -n \"\$f644\" ] && chmod 0644 \$f644 2>/dev/null; ")
            append("[ -n \"\$f000\" ] && chmod 0000 \$f000 2>/dev/null; ")
            append("echo __DONE__")
        }
        return runRootCommand(command)
    }

    /**
     * 恢复全部温控节点（含充电温控节点与旧版可能残留锁死的 AP/PA/WF/thermal 节点）。
     * 目录使用 0755，sysfs 传感器文件使用 0644，批量合并 chmod 调用。
     */
    fun restoreTemperatureNodes(): Result {
        val command = buildString {
            append(UID_PREAMBLE)
            append("d755=\"\"; f644=\"\"; ")

            for (node in ALL_MANAGED_NODES) {
                append("if [ -d '$node' ]; then d755=\"\$d755 $node\"; elif [ -e '$node' ]; then f644=\"\$f644 $node\"; fi; ")
            }

            append("[ -n \"\$d755\" ] && chmod 0755 \$d755 2>/dev/null; ")
            append("[ -n \"\$f644\" ] && chmod 0644 \$f644 2>/dev/null; ")
            append("echo __DONE__")
        }
        return runRootCommand(command)
    }

    /**
     * 复核充电温控节点当前是否全部处于被屏蔽状态（且 SoC 安全节点未被误锁）。
     * 仅调用一次 `stat -c '%a:%n'` 批量探测全部节点，代替原来的 17 次逐节点 subshell+stat。
     */
    fun areTemperatureNodesBlocked(): Boolean? = queryNodeBlockReport()?.allChargingBlocked

    /**
     * 检查是否存在任何一个温控节点仍处于 0000 锁死状态（用于熔断/息屏/拔线待命态强校验自愈）。
     */
    fun isAnyTemperatureNodeBlocked(): Boolean? = queryNodeBlockReport()?.anyNodeBlocked

    private fun queryNodeBlockReport(): NodeBlockReport? {
        val command = buildString {
            append(UID_PREAMBLE)
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
        var hasBlockedCharging = false
        var hasOpenCharging = false
        var socLocked = false

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
            }
            if (path in SOC_THERMAL_SAFETY_SET && isZeroMode) {
                socLocked = true
            }
            if (path in CHARGING_TEMPERATURE_SET) {
                if (isZeroMode) hasBlockedCharging = true else hasOpenCharging = true
            }
        }

        val allChargingBlocked = when {
            socLocked -> false
            !hasOpenCharging && !hasBlockedCharging -> null
            else -> hasBlockedCharging && !hasOpenCharging
        }
        return NodeBlockReport(
            allChargingBlocked = allChargingBlocked,
            anyNodeBlocked = anyBlocked,
        )
    }

    /** 开启三星系统设置快充开关 */
    fun enableSamsungFastChargingSettings(): Result {
        val command = buildString {
            append(UID_PREAMBLE)
            append("settings put system adaptive_fast_charging '1'; ")
            append("settings put system wireless_fast_charging '1'; ")
            append("settings put system super_fast_charging '1'; ")
            append("settings put system fast_charging '1'; ")
            append("echo __DONE__")
        }
        return runRootCommand(command)
    }

    /**
     * 真实硬件节点直读：利用 Root 在持久化 sh 内直接读取系统 /sys 真实电压、电流与多路真实温度并计算实际功率 (W)。
     *
     * 性能优化要点（避免 root sh 持续 fork/execve 导致高 CPU 与数万次缺页）：
     * 1. 全程使用 mksh 内建 `read -r var < file` 替代外部 `$(cat ... | head -n 1)`，常规节点 0 fork；
     * 2. 缓存电池 `thermal_zone` 路径至会话变量 `__wt_tzb`，避免每次轮询遍历 80+ 个热区并反复 `cat type`；
     * 3. 被 `0000` 屏蔽的节点先直读，仅对未读出值的存在节点做单次批量 `stat` + `chmod 0444` + `read -r` + `chmod 0000`，
     *    将每次轮询的外部进程派生从 ~85 次降至 0~4 次。
     */
    fun readBatteryMetrics(context: Context): BatteryMetrics {
        val (exitCode, stdout) = RootShell.exec(
            UID_PREAMBLE +
                "tzb=\"\"; " +
                "if [ \"\${__wt_tzb:-}\" != \"none\" ]; then " +
                "if [ -n \"\${__wt_tzb:-}\" ] && [ -e \"\$__wt_tzb/temp\" ]; then { read -r tzb < \"\$__wt_tzb/temp\"; } 2>/dev/null; fi; " +
                "case \"\$tzb\" in \"\"|-*|0) tzb=\"\"; __wt_tzb=\"\"; matched=0; " +
                "for z in /sys/class/thermal/thermal_zone*; do " +
                "[ -e \"\$z/type\" ] || continue; t=\"\"; { read -r t < \"\$z/type\"; } 2>/dev/null; " +
                "case \"\$t\" in *battery*|*batt*|*BAT*|*sec-battery*) matched=1; tv=\"\"; { read -r tv < \"\$z/temp\"; } 2>/dev/null; " +
                "case \"\$tv\" in \"\"|-*|0) ;; *) tzb=\"\$tv\"; __wt_tzb=\"\$z\"; break ;; esac ;; esac; done; " +
                "[ \"\$matched\" = \"0\" ] && [ -z \"\$__wt_tzb\" ] && __wt_tzb=\"none\"; ;; esac; " +
                "fi; " +
                "c=\"\"; { read -r c < /sys/class/power_supply/battery/current_now; } 2>/dev/null; " +
                "v=\"\"; { read -r v < /sys/class/power_supply/battery/voltage_now; } 2>/dev/null; " +
                "tcf=\"\"; { read -r tcf < /sys/class/sec/sec-cf-thermistor/temperature; } 2>/dev/null; " +
                "tth=\"\"; { read -r tth < /sys/class/sec/sec-thermistor/temperature; } 2>/dev/null; " +
                "tap=\"\"; { read -r tap < /sys/class/sec/sec-ap-thermistor/temperature; } 2>/dev/null; " +
                "tpa=\"\"; { read -r tpa < /sys/class/sec/sec-pa-thermistor/temperature; } 2>/dev/null; " +
                "p1=/sys/class/power_supply/battery/batt_temp; p2=/sys/class/power_supply/battery/temp; " +
                "p3=/sys/class/sec/battery/batt_temp; p4=/sys/class/power_supply/battery/chg_temp; p5=/sys/class/power_supply/battery/dchg_temp; " +
                "tb1=\"\"; tb2=\"\"; tb3=\"\"; tchg=\"\"; tdchg=\"\"; need=\"\"; " +
                "if [ -e \"\$p1\" ]; then { read -r tb1 < \"\$p1\"; } 2>/dev/null; [ -z \"\$tb1\" ] && need=\"\$need \$p1\"; fi; " +
                "if [ -e \"\$p2\" ]; then { read -r tb2 < \"\$p2\"; } 2>/dev/null; [ -z \"\$tb2\" ] && need=\"\$need \$p2\"; fi; " +
                "if [ -e \"\$p3\" ]; then { read -r tb3 < \"\$p3\"; } 2>/dev/null; [ -z \"\$tb3\" ] && need=\"\$need \$p3\"; fi; " +
                "if [ -e \"\$p4\" ]; then { read -r tchg < \"\$p4\"; } 2>/dev/null; [ -z \"\$tchg\" ] && need=\"\$need \$p4\"; fi; " +
                "if [ -e \"\$p5\" ]; then { read -r tdchg < \"\$p5\"; } 2>/dev/null; [ -z \"\$tdchg\" ] && need=\"\$need \$p5\"; fi; " +
                "if [ -n \"\$need\" ]; then " +
                "relock=\"\"; for line in \$(stat -c \"%a:%n\" \$need 2>/dev/null); do " +
                "m=\"\${line%%:*}\"; np=\"\${line#*:}\"; " +
                "case \"\$m\" in 0|00|000|0000) relock=\"\$relock \$np\" ;; esac; " +
                "done; " +
                "if [ -n \"\$relock\" ]; then " +
                "chmod 0444 \$relock 2>/dev/null; " +
                "[ -z \"\$tb1\" ] && [ -e \"\$p1\" ] && { read -r tb1 < \"\$p1\"; } 2>/dev/null; " +
                "[ -z \"\$tb2\" ] && [ -e \"\$p2\" ] && { read -r tb2 < \"\$p2\"; } 2>/dev/null; " +
                "[ -z \"\$tb3\" ] && [ -e \"\$p3\" ] && { read -r tb3 < \"\$p3\"; } 2>/dev/null; " +
                "[ -z \"\$tchg\" ] && [ -e \"\$p4\" ] && { read -r tchg < \"\$p4\"; } 2>/dev/null; " +
                "[ -z \"\$tdchg\" ] && [ -e \"\$p5\" ] && { read -r tdchg < \"\$p5\"; } 2>/dev/null; " +
                "chmod 0000 \$relock 2>/dev/null; " +
                "fi; " +
                "fi; " +
                "echo \"C=\$c\"; echo \"V=\$v\"; " +
                "echo \"TB1=\$tb1\"; echo \"TB2=\$tb2\"; echo \"TB3=\$tb3\"; echo \"TZB=\$tzb\"; " +
                "echo \"TCF=\$tcf\"; echo \"TTH=\$tth\"; echo \"TAP=\$tap\"; echo \"TPA=\$tpa\"; " +
                "echo \"TCHG=\$tchg\"; echo \"TDCHG=\$tdchg\"; " +
                "echo __DONE__",
            timeoutSeconds = 4,
        )

        var currentMa = 0
        var voltageMv = 0
        var tempTenths = 0
        var maxChipTempTenths = 0

        if (exitCode == 0 && stdout.contains("__UID__0")) {
            var rawCurrent = abs(taggedInt(stdout, "C") ?: 0)
            if (rawCurrent > 100_000) rawCurrent /= 1_000      // 部分内核以 µA 上报
            currentMa = rawCurrent

            var rawVoltage = abs(taggedInt(stdout, "V") ?: 0)
            if (rawVoltage > 100_000) rawVoltage /= 1_000      // 部分内核以 µV 上报
            voltageMv = rawVoltage

            val tb1 = normalizeTemp(taggedInt(stdout, "TB1"))
            val tb2 = normalizeTemp(taggedInt(stdout, "TB2"))
            val tb3 = normalizeTemp(taggedInt(stdout, "TB3"))
            val tzb = normalizeTemp(taggedInt(stdout, "TZB"))
            val tcf = normalizeTemp(taggedInt(stdout, "TCF"))
            val tth = normalizeTemp(taggedInt(stdout, "TTH"))
            // 电池/机身有效温度取电池、thermal_zone 电池热敏与中框的最高值，防止机身已达 45°C 而单一节点滞后或失真
            tempTenths = maxOf(tb1, tb2, tb3, tzb, tcf, tth)

            val tap = normalizeTemp(taggedInt(stdout, "TAP"))
            val tpa = normalizeTemp(taggedInt(stdout, "TPA"))
            val tchg = normalizeTemp(taggedInt(stdout, "TCHG"))
            val tdchg = normalizeTemp(taggedInt(stdout, "TDCHG"))
            maxChipTempTenths = maxOf(tap, tpa, tchg, tdchg)
        }

        // 兜底方案：使用系统 BatteryManager API
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
            val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
            val batteryStatus = context.registerReceiver(null, filter)
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
        return BatteryMetrics(currentMa, voltageMv, watts, tempTenths, maxChipTempTenths)
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
