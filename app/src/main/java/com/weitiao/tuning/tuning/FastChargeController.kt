package com.weitiao.tuning.tuning

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import com.weitiao.tuning.core.RootShell
import kotlin.math.abs

/**
 * 专为 One UI 及高版本内核优化的亮屏快充控制器。
 * 通过 Root 屏蔽温控节点、解除 SIOP 限流，并直接从硬件 sysfs 节点读取精准电压、电流与真实电池温度。
 */
object FastChargeController {

    // 三星电池与充电专用温控传感器节点（完全对齐 S8Tweak y11.O0ooOO0o 17 个节点）
    private val TEMPERATURE_NODES = listOf(
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

    data class Result(val success: Boolean, val detail: String)

    data class BatteryMetrics(
        val currentMa: Int,
        val voltageMv: Int,
        val powerWatts: Double,
        val temperatureTenths: Int, // 0.1°C 单位，如 385 代表 38.5°C
    )

    /** 屏蔽三星充电温控节点，解除亮屏限流（纯净 chmod 0000，对齐 S8Tweak x11 原版逻辑） */
    fun blockTemperatureNodes(): Result {
        val command = buildString {
            append("uid=$(id -u); echo __UID__\$uid; ")

            for (node in TEMPERATURE_NODES) {
                append("chmod 0000 '${node.replace("'", "'\\''")}' 2>/dev/null; ")
            }

            append("echo __DONE__")
        }
        return runRootCommand(command)
    }

    /** 恢复温控节点（对齐 S8Tweak 原版使用 chmod 0755，确保 /sys/class/thermal 等目录保持可遍历） */
    fun restoreTemperatureNodes(): Result {
        val command = buildString {
            append("uid=$(id -u); echo __UID__\$uid; ")

            for (node in TEMPERATURE_NODES) {
                append("chmod 0755 '${node.replace("'", "'\\''")}' 2>/dev/null; ")
            }

            append("echo __DONE__")
        }
        return runRootCommand(command)
    }

    /**
     * 复核温控节点当前是否仍处于被屏蔽状态（自检用）。
     *
     * 说明：root 拥有 CAP_DAC_OVERRIDE，`-r` 判断对 root 恒为真，因此这里用
     * `stat -c %a` 读取权限位判断，而不是尝试读取文件。
     * 返回 null 表示无法判定（节点不存在 / shell 不可用）。
     */
    fun areTemperatureNodesBlocked(): Boolean? {
        val command = buildString {
            append("uid=$(id -u); echo __UID__\$uid; ")
            for (node in TEMPERATURE_NODES) {
                val escaped = node.replace("'", "'\\''")
                append("[ -e '$escaped' ] && { m=$(stat -c %a '$escaped' 2>/dev/null); ")
                append("if [ -n \"\$m\" ] && [ \"\$m\" != \"0\" ]; then echo __OPEN__; else echo __BLOCKED__; fi; }; ")
            }
            append("echo __DONE__")
        }
        val (exitCode, stdout) = RootShell.exec(command, timeoutSeconds = 6)
        if (exitCode != 0 || !stdout.contains("__UID__0")) return null
        val open = stdout.contains("__OPEN__")
        val blocked = stdout.contains("__BLOCKED__")
        if (!open && !blocked) return null
        return blocked && !open
    }

    /** 开启三星系统设置快充开关 */
    fun enableSamsungFastChargingSettings(): Result {
        val command = buildString {
            append("echo __UID__$(id -u); ")
            append("settings put system adaptive_fast_charging '1'; ")
            append("settings put system wireless_fast_charging '1'; ")
            append("settings put system super_fast_charging '1'; ")
            append("settings put system fast_charging '1'; ")
            append("echo __DONE__")
        }
        return runRootCommand(command)
    }

    /** 真实硬件节点直读：利用 Root 毫秒级读取系统 /sys 真实电压、电流与真实电池温度并计算实际功率 (W) */
    fun readBatteryMetrics(context: Context): BatteryMetrics {
        // 关键点：每个节点单独 echo 并带标签，不要 `cat A B C D` 后按行号取值。
        // cat 遇到不存在的节点会静默跳过（stderr 已丢弃），后面的行会整体前移，
        // 于是「电压」被当成「电流」解析，通知栏出现几千毫安的荒谬值或干脆读数为 0。
        // 不同机型/内核暴露的节点集合并不一致，按标签取值才能免疫缺失节点。
        val (exitCode, stdout) = RootShell.exec(
            "echo __UID__\$(id -u); " +
                "echo C=\$(cat /sys/class/power_supply/battery/current_now 2>/dev/null); " +
                "echo V=\$(cat /sys/class/power_supply/battery/voltage_now 2>/dev/null); " +
                "echo T=\$(cat /sys/class/power_supply/battery/batt_temp_raw 2>/dev/null); " +
                "echo T2=\$(cat /sys/class/sec/sec-cf-thermistor/temperature 2>/dev/null); " +
                "echo T3=\$(cat /sys/class/sec/sec-thermistor/temperature 2>/dev/null); " +
                "echo T4=\$(cat /sys/class/sec/sec-lp-thermistor/temperature 2>/dev/null); " +
                "echo T5=\$(cat /sys/class/power_supply/battery/temp 2>/dev/null); " +
                "echo __DONE__",
            // su 首次调用有启动开销（冷启动/刚解锁时可达 1s 以上），2s 太紧，
            // 超时会直接拿不到读数，表现为通知栏电流信息缺失。
            timeoutSeconds = 4,
        )

        var currentMa = 0
        var voltageMv = 0
        var tempTenths = 0

        if (exitCode == 0 && stdout.contains("__UID__0")) {
            var rawCurrent = abs(taggedInt(stdout, "C") ?: 0)
            if (rawCurrent > 100_000) rawCurrent /= 1_000      // 部分内核以 µA 上报
            currentMa = rawCurrent

            var rawVoltage = abs(taggedInt(stdout, "V") ?: 0)
            if (rawVoltage > 100_000) rawVoltage /= 1_000      // 部分内核以 µV 上报
            voltageMv = rawVoltage

            tempTenths = normalizeTemp(taggedInt(stdout, "T"))
            if (tempTenths == 0) tempTenths = normalizeTemp(taggedInt(stdout, "T2"))
            if (tempTenths == 0) tempTenths = normalizeTemp(taggedInt(stdout, "T3"))
            if (tempTenths == 0) tempTenths = normalizeTemp(taggedInt(stdout, "T4"))
            if (tempTenths == 0) tempTenths = normalizeTemp(taggedInt(stdout, "T5"))
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
                val frameworkTemp = batteryStatus?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 320) ?: 320
                if (frameworkTemp > 0) {
                    tempTenths = frameworkTemp
                }
            }
        }

        val watts = (voltageMv.toDouble() * currentMa.toDouble()) / 1_000_000.0
        return BatteryMetrics(currentMa, voltageMv, watts, tempTenths)
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

    /** 温度归一化到 0.1°C：兼容原始值、百分之一度与千分之一度三种上报口径 */
    private fun normalizeTemp(raw: Int?): Int {
        var value = abs(raw ?: 0)
        if (value > 1_000) value /= 100              // 例如 38500 → 385（38.5°C）
        return if (value in 50..800) value else 0    // 5.0°C~80.0°C 之外视为无效
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
