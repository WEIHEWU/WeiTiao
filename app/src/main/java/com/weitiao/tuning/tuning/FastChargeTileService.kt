package com.weitiao.tuning.tuning

import android.app.StatusBarManager
import android.content.ComponentName
import android.content.Context
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.util.Log
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.weitiao.tuning.R
import com.weitiao.tuning.core.RootShell
import com.weitiao.tuning.core.TuningPrefs
import java.util.Locale

internal enum class TileMode {
    OFF,
    STANDBY,
    CHARGING,
    CHARGING_WATTS,
    OVERHEAT,
}

internal data class TilePresentation(
    val active: Boolean,
    val mode: TileMode,
    val powerWatts: Double = 0.0,
)

internal fun resolveTilePresentation(
    enabled: Boolean,
    isAcCharging: Boolean,
    isOverheated: Boolean,
    livePowerWatts: Double,
): TilePresentation = when {
    !enabled -> TilePresentation(active = false, mode = TileMode.OFF)
    isOverheated -> TilePresentation(active = true, mode = TileMode.OVERHEAT)
    isAcCharging && livePowerWatts >= 0.5 -> TilePresentation(
        active = true,
        mode = TileMode.CHARGING_WATTS,
        powerWatts = livePowerWatts,
    )
    isAcCharging -> TilePresentation(active = true, mode = TileMode.CHARGING)
    else -> TilePresentation(active = true, mode = TileMode.STANDBY)
}

/**
 * 系统下拉控制中心「亮屏快充」快捷磁贴服务。
 * 支持直接点击切换开关、与 App 内设置双向同步、实时展示充电功率或高温保护副标题，以及长按跳转微调主页。
 */
class FastChargeTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        renderTile(TuningPrefs.load(this).brightScreenFastCharge)
    }

    override fun onTileAdded() {
        super.onTileAdded()
        renderTile(TuningPrefs.load(this).brightScreenFastCharge)
    }

    override fun onClick() {
        super.onClick()
        val current = TuningPrefs.load(this)
        val nextEnabled = !current.brightScreenFastCharge
        TuningPrefs.saveAsync(this, current.copy(brightScreenFastCharge = nextEnabled))
        renderTile(nextEnabled)

        if (nextEnabled) {
            val appCtx = applicationContext
            Thread({
                val hasRoot = runCatching { RootShell.hasRoot() }.getOrDefault(false)
                if (!hasRoot) {
                    val latest = TuningPrefs.load(appCtx)
                    if (latest.brightScreenFastCharge) {
                        TuningPrefs.saveAsync(appCtx, latest.copy(brightScreenFastCharge = false))
                    }
                    requestSync(appCtx)
                    Handler(Looper.getMainLooper()).post {
                        Toast.makeText(
                            appCtx,
                            R.string.qs_fast_charge_need_root,
                            Toast.LENGTH_SHORT,
                        ).show()
                    }
                }
            }, "Tuning-TileRootCheck").apply { isDaemon = true }.start()
        }
    }

    private fun renderTile(enabled: Boolean) {
        val tile = qsTile ?: return
        val snap = FastChargeScheduler.readBatterySnapshot(this)
        val liveWatts = if (BrightScreenFastChargeService.isServiceAlive) {
            BrightScreenFastChargeService.lastKnownMetrics.powerWatts
        } else {
            0.0
        }
        val presentation = resolveTilePresentation(
            enabled = enabled,
            isAcCharging = snap.isAcCharging,
            isOverheated = BrightScreenFastChargeService.isServiceAlive && BrightScreenFastChargeService.isThermalCutoffActive,
            livePowerWatts = liveWatts,
        )

        tile.icon = Icon.createWithResource(this, R.drawable.ic_qs_fast_charge)
        tile.label = getString(R.string.qs_fast_charge_label)
        tile.state = if (presentation.active) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            tile.subtitle = when (presentation.mode) {
                TileMode.OFF -> getString(R.string.qs_fast_charge_off)
                TileMode.STANDBY -> getString(R.string.qs_fast_charge_on)
                TileMode.CHARGING -> getString(R.string.qs_fast_charge_charging)
                TileMode.CHARGING_WATTS -> String.format(
                    Locale.ROOT,
                    getString(R.string.qs_fast_charge_charging_watts),
                    presentation.powerWatts,
                )
                TileMode.OVERHEAT -> getString(R.string.qs_fast_charge_overheat)
            }
        }
        runCatching { tile.updateTile() }
            .onFailure { Log.w(TAG, "更新快捷磁贴失败", it) }
    }

    companion object {
        private const val TAG = "FastChargeTile"

        /** 通知系统控制中心刷新磁贴状态（用于 App 内开关与磁贴双向同步）。 */
        fun requestSync(context: Context) {
            val appCtx = context.applicationContext
            runCatching {
                requestListeningState(
                    appCtx,
                    ComponentName(appCtx, FastChargeTileService::class.java),
                )
            }.onFailure { Log.w(TAG, "请求刷新快捷磁贴状态失败", it) }
        }

        /** Android 13+ 一键弹窗引导用户把「亮屏快充」磁贴添加到控制中心；低版本给出手动拖入提示。 */
        fun requestAddTile(context: Context) {
            val appCtx = context.applicationContext
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                val sbm = appCtx.getSystemService(StatusBarManager::class.java)
                if (sbm != null) {
                    runCatching {
                        sbm.requestAddTileService(
                            ComponentName(appCtx, FastChargeTileService::class.java),
                            appCtx.getString(R.string.qs_fast_charge_label),
                            Icon.createWithResource(appCtx, R.drawable.ic_qs_fast_charge),
                            ContextCompat.getMainExecutor(appCtx),
                        ) { resultCode ->
                            when (resultCode) {
                                StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ADDED -> {
                                    Toast.makeText(
                                        appCtx,
                                        R.string.qs_fast_charge_added,
                                        Toast.LENGTH_SHORT,
                                    ).show()
                                }
                                StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ALREADY_ADDED -> {
                                    Toast.makeText(
                                        appCtx,
                                        R.string.qs_fast_charge_already_added,
                                        Toast.LENGTH_SHORT,
                                    ).show()
                                }
                            }
                        }
                        return
                    }.onFailure { Log.w(TAG, "requestAddTileService 失败", it) }
                }
            }
            Toast.makeText(
                appCtx,
                R.string.qs_fast_charge_manual_hint,
                Toast.LENGTH_LONG,
            ).show()
        }
    }
}
