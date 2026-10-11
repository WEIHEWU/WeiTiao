package com.weitiao.tuning.tuning

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat
import com.weitiao.tuning.core.TuningPrefs

/**
 * 亮屏快充拉起入口。
 *
 * 监听：开机、包更新、插电、拔电。
 *
 * 关键修复：Android 12（targetSdk 31+）起后台启动前台服务被禁止，
 * 而 `ACTION_POWER_CONNECTED` 不在豁免名单内（仅 BOOT_COMPLETED /
 * LOCKED_BOOT_COMPLETED / MY_PACKAGE_REPLACED 等豁免）。
 * 原实现用 `runCatching` 吞掉了这个异常，导致「拔线后服务退出 → 再插电时
 * 静态广播启动服务失败且毫无日志 → 只有重启手机才恢复」。
 *
 * 现在改为两段式：
 * 1. 先尝试拉起常驻前台服务（开机/包更新等豁免场景一定成功）；
 * 2. 若被后台限制拒绝，则在广播自身的存活窗口内直接下发 root 策略并推送实时通知，
 *    保证插入电源立刻可见。
 */
class FastChargeReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        if (!TuningPrefs.load(context).brightScreenFastCharge) return

        FastChargeNotifier.ensureChannels(context)

        val started = runCatching {
            ContextCompat.startForegroundService(context, serviceIntent(context))
            true
        }.getOrDefault(false)

        if (started) {
            Log.i(TAG, "已拉起常驻前台服务 (action=${intent?.action})")
            return
        }

        Log.w(TAG, "后台启动前台服务被限制 (action=${intent?.action})，改用即时兜底路径")
        // 兜底任务：JobService 执行期间处于活动窗口，比广播更容易重新拉起常驻服务
        FastChargeScheduler.kick(context)
        val pending = goAsync()
        Thread({
            try {
                FastChargeFallback.applyNow(context)
            } finally {
                runCatching { pending.finish() }
            }
        }, "Tuning-FastChargeFallback").apply { isDaemon = true }.start()
    }

    private fun serviceIntent(context: Context): Intent =
        Intent(context, BrightScreenFastChargeService::class.java)
            .setAction(BrightScreenFastChargeService.ACTION_START)

    private companion object {
        const val TAG = "TuningFastCharge"
    }
}
