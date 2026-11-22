package com.weitiao.tuning.tuning

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.weitiao.tuning.core.RootShell
import com.weitiao.tuning.core.TuningPrefs

/**
 * 亮屏快充拉起入口（静态接收器）。
 *
 * 监听：开机、包更新、插电、拔电。
 *
 * 安全策略：
 * 1. 全量逻辑放入 `goAsync()` 后台线程，不在广播主线程执行同步 `su`；
 * 2. 先校验物理插电、100% 满电状态（方案 A）与 Root 状态，未满足条件前绝不调用
 *    `startForegroundService()`，彻底杜绝无充电时弹通知或 `ForegroundServiceDidNotStartInTimeException`。
 */
class FastChargeReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action
        val appCtx = context.applicationContext
        val pending = goAsync()
        Thread({
            try {
                if (action == Intent.ACTION_BOOT_COMPLETED ||
                    action == Intent.ACTION_LOCKED_BOOT_COMPLETED ||
                    action == Intent.ACTION_MY_PACKAGE_REPLACED
                ) {
                    // 系统重启或覆盖更新后解除无 Root 静默挂起，允许自检重新接入
                    FastChargeScheduler.setRootSuspended(appCtx, false)
                }

                val cfg = TuningPrefs.load(appCtx)
                val fastChargeEnabled = cfg.brightScreenFastCharge
                val passThroughEnabled = cfg.passThrough

                if (!fastChargeEnabled && !passThroughEnabled) {
                    if (FastChargeController.isNodesMayBeBlocked(appCtx) && RootShell.hasRoot()) {
                        FastChargeController.restoreTemperatureNodes(appCtx)
                    }
                    FastChargeScheduler.disable(appCtx)
                    FastChargeNotifier.cancelLive(appCtx)
                    return@Thread
                }

                val snap = FastChargeScheduler.readBatterySnapshot(appCtx)
                if (action == Intent.ACTION_POWER_DISCONNECTED || !snap.plugged) {
                    BrightScreenFastChargeService.isFullChargeReleasedActive = false
                    FastChargeNotifier.cancelLive(appCtx)
                    if (FastChargeController.isNodesMayBeBlocked(appCtx) && RootShell.hasRoot()) {
                        FastChargeController.restoreTemperatureNodes(appCtx)
                    }
                    FastChargeScheduler.enable(appCtx, knownPlugged = false)
                    return@Thread
                }

                if (FastChargeScheduler.isRootSuspended(appCtx)) {
                    FastChargeScheduler.disable(appCtx)
                    FastChargeNotifier.cancelLive(appCtx)
                    return@Thread
                }

                // 已接通电源：统一交给 FastChargeFallback.applyNow 完成满电/Root/服务拉起与兜底判定
                Log.d(TAG, "收到电源广播 action=$action，进入统一门控下发")
                FastChargeFallback.applyNow(appCtx)
            } finally {
                runCatching { pending?.finish() }
            }
        }, "Tuning-FastChargeReceiver").apply { isDaemon = true }.start()
    }

    private companion object {
        const val TAG = "TuningFastCharge"
    }
}
