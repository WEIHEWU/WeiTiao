package com.weitiao.tuning.core

import org.json.JSONArray
import org.json.JSONObject

/**
 * 模块配置模型。
 * UI 侧写入 SharedPreferences，Hook 侧解析配置文件还原。
 */
data class CscConfig(
    /** 总开关 */
    val enabled: Boolean = true,
    /** 目标销售代码（CSC），如 TGY / BRI / CHC */
    val salesCode: String = "",
    /** 目标地区代码，如 HK / TW / CN */
    val countryCode: String = "",
    /** 是否欺骗 ro.csc.sales_code */
    val spoofSalesCode: Boolean = true,
    /** 是否欺骗 ro.csc.country_code */
    val spoofCountryCode: Boolean = true,
    /** 是否欺骗 ril.sales_code */
    val spoofRilSalesCode: Boolean = true,
    /** 是否欺骗 ro.csc.omcnw_code / official_csc / csc_code */
    val spoofOmcCodes: Boolean = true,
    /** 是否 hook SemCscFeature（CscFeature_* 读取） */
    val spoofSemCscFeature: Boolean = true,
    /** 是否欺骗 SIM 信息；存在 CSC 时始终自动开启。 */
    val spoofSimInfo: Boolean = false,
    /** 目标 SIM MCC（3位数字，如 454=香港/466=台湾/999=占位），用于替换 gsm.sim.operator 等 */
    val simMcc: String = "",
    /** 目标 SIM MNC */
    val simMnc: String = "",
    /** 目标 SIM 国家 ISO（如 hk / tw / kr），用于 getSimCountryIso */
    val simCountryIso: String = "",
    /** 目标 SIM 完整数字代码（如 310260 / 45406），用于 gsm.sim.operator.numeric 与 TelephonyManager */
    val simNumeric: String = "",
    /** 目标 SIM 运营商名称（如 T-Mobile / SmarTone），用于 gsm.operator.alpha 与 TelephonyManager */
    val simOperatorName: String = "",
    /** S8Tweak 市场解锁原始记录（格式：numeric,operator,iso,country） */
    val marketUnlockRaw: String = "",
    /** 是否启用机型伪装（Build.MODEL/DEVICE/PRODUCT） */
    val spoofDevice: Boolean = false,
    /** 目标机型展示名（如 "Galaxy Z Fold8"），与 DeviceModels 对应 */
    val deviceName: String = "",
    /** 启用应用内过滤后，仅对这些包启用机型伪装。 */
    val deviceTargetPackages: Set<String> = emptySet(),
    /** 是否启用应用内目标过滤；关闭时沿用全部 LSPosed 作用域。 */
    val deviceTargetFilterEnabled: Boolean = false,
    /** 自定义属性覆盖：key -> value */
    val customProps: Map<String, String> = emptyMap(),
    /** 自定义 CscFeature 覆盖：key -> value */
    val customCscFeatures: Map<String, String> = emptyMap(),
) {

    companion object {
        private const val KEY_ENABLED = "enabled"
        private const val KEY_SALES_CODE = "sales_code"
        private const val KEY_COUNTRY_CODE = "country_code"
        private const val KEY_SPOOF_SALES = "spoof_sales_code"
        private const val KEY_SPOOF_COUNTRY = "spoof_country_code"
        private const val KEY_SPOOF_RIL = "spoof_ril_sales_code"
        private const val KEY_SPOOF_OMC = "spoof_omc_codes"
        private const val KEY_SPOOF_CSC_FEATURE = "spoof_sem_csc_feature"
        private const val KEY_SPOOF_SIM = "spoof_sim_info"
        private const val KEY_SIM_MCC = "sim_mcc"
        private const val KEY_SIM_MNC = "sim_mnc"
        private const val KEY_SIM_ISO = "sim_country_iso"
        private const val KEY_SIM_NUMERIC = "sim_numeric"
        private const val KEY_SIM_OPERATOR_NAME = "sim_operator_name"
        private const val KEY_MARKET_UNLOCK_RAW = "soul_market_unlocker"
        private const val KEY_SPOOF_DEVICE = "spoof_device"
        private const val KEY_DEVICE_NAME = "device_name"
        private const val KEY_DEVICE_TARGET_PACKAGES = "device_target_packages"
        private const val KEY_DEVICE_TARGET_FILTER = "device_target_filter_enabled"
        private const val KEY_CUSTOM_PROPS = "custom_props"
        private const val KEY_CUSTOM_FEATURES = "custom_csc_features"

        fun jsonToMap(json: String?): Map<String, String> {
            if (json.isNullOrBlank()) return emptyMap()
            return runCatching {
                val obj = JSONObject(json)
                val keys = obj.keys()
                buildMap {
                    while (keys.hasNext()) {
                        val k = keys.next()
                        put(k, obj.optString(k))
                    }
                }
            }.getOrDefault(emptyMap())
        }

        private fun mapToJson(map: Map<String, String>): String =
            JSONObject(map).toString()

        /** 从 SharedPreferences 读取（UI 侧） */
        fun fromPrefs(prefs: android.content.SharedPreferences): CscConfig = CscConfig(
            enabled = prefs.getBoolean(KEY_ENABLED, true),
            salesCode = prefs.getString(KEY_SALES_CODE, "") ?: "",
            countryCode = prefs.getString(KEY_COUNTRY_CODE, "") ?: "",
            spoofSalesCode = prefs.getBoolean(KEY_SPOOF_SALES, true),
            spoofCountryCode = prefs.getBoolean(KEY_SPOOF_COUNTRY, true),
            spoofRilSalesCode = prefs.getBoolean(KEY_SPOOF_RIL, true),
            spoofOmcCodes = prefs.getBoolean(KEY_SPOOF_OMC, true),
            spoofSemCscFeature = prefs.getBoolean(KEY_SPOOF_CSC_FEATURE, true),
            spoofSimInfo = !prefs.getString(KEY_SALES_CODE, "").isNullOrBlank(),
            simMcc = prefs.getString(KEY_SIM_MCC, "") ?: "",
            simMnc = prefs.getString(KEY_SIM_MNC, "") ?: "",
            simCountryIso = prefs.getString(KEY_SIM_ISO, "") ?: "",
            simNumeric = prefs.getString(KEY_SIM_NUMERIC, "") ?: "",
            simOperatorName = prefs.getString(KEY_SIM_OPERATOR_NAME, "") ?: "",
            marketUnlockRaw = prefs.getString(KEY_MARKET_UNLOCK_RAW, "") ?: "",
            spoofDevice = prefs.getBoolean(KEY_SPOOF_DEVICE, false),
            deviceName = prefs.getString(KEY_DEVICE_NAME, "") ?: "",
            deviceTargetPackages = runCatching {
                val raw = runCatching { prefs.getString(KEY_DEVICE_TARGET_PACKAGES, null) }.getOrNull()
                if (!raw.isNullOrBlank()) {
                    if (raw.startsWith("[")) {
                        val arr = JSONArray(raw)
                        (0 until arr.length()).map { arr.getString(it) }.toSet()
                    } else {
                        raw.split(",").map { it.trim() }.filter { it.isNotEmpty() }.toSet()
                    }
                } else {
                    runCatching { prefs.getStringSet(KEY_DEVICE_TARGET_PACKAGES, null)?.toSet() }.getOrNull().orEmpty()
                }
            }.getOrDefault(emptySet()),
            deviceTargetFilterEnabled = prefs.getBoolean(KEY_DEVICE_TARGET_FILTER, false),
            customProps = jsonToMap(prefs.getString(KEY_CUSTOM_PROPS, null)),
            customCscFeatures = jsonToMap(prefs.getString(KEY_CUSTOM_FEATURES, null)),
        )

        /** 写入 SharedPreferences（UI 侧） */
        fun toPrefs(prefs: android.content.SharedPreferences.Editor, config: CscConfig) {
            prefs.putBoolean(KEY_ENABLED, config.enabled)
            prefs.putString(KEY_SALES_CODE, config.salesCode.uppercase())
            prefs.putString(KEY_COUNTRY_CODE, config.countryCode.uppercase())
            prefs.putBoolean(KEY_SPOOF_SALES, config.spoofSalesCode)
            prefs.putBoolean(KEY_SPOOF_COUNTRY, config.spoofCountryCode)
            prefs.putBoolean(KEY_SPOOF_RIL, config.spoofRilSalesCode)
            prefs.putBoolean(KEY_SPOOF_OMC, config.spoofOmcCodes)
            prefs.putBoolean(KEY_SPOOF_CSC_FEATURE, config.spoofSemCscFeature)
            prefs.putBoolean(KEY_SPOOF_SIM, config.salesCode.isNotBlank())
            prefs.putString(KEY_SIM_MCC, config.simMcc.uppercase())
            prefs.putString(KEY_SIM_MNC, config.simMnc.uppercase())
            prefs.putString(KEY_SIM_ISO, config.simCountryIso.lowercase())
            prefs.putString(KEY_SIM_NUMERIC, config.simNumeric)
            prefs.putString(KEY_SIM_OPERATOR_NAME, config.simOperatorName)
            prefs.putString(KEY_MARKET_UNLOCK_RAW, config.marketUnlockRaw)
            prefs.putBoolean(KEY_SPOOF_DEVICE, config.spoofDevice)
            prefs.putString(KEY_DEVICE_NAME, config.deviceName)
            // 采用 JSON 字符串传输，彻底杜绝 Binder 跨进程传输 EmptySet/Set 抛 BadParcelableException 崩溃
            val targetPkgsJson = JSONArray().apply {
                config.deviceTargetPackages.forEach { put(it) }
            }.toString()
            prefs.putString(KEY_DEVICE_TARGET_PACKAGES, targetPkgsJson)
            prefs.putBoolean(KEY_DEVICE_TARGET_FILTER, config.deviceTargetFilterEnabled)
            prefs.putString(KEY_CUSTOM_PROPS, mapToJson(config.customProps))
            prefs.putString(KEY_CUSTOM_FEATURES, mapToJson(config.customCscFeatures))
        }
    }
}
