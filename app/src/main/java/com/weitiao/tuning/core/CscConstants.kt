package com.weitiao.tuning.core

import com.weitiao.tuning.data.CscCatalog

/**
 * 三星 CSC 相关常量：属性名、类名、完整国家码与 MCC 关联。
 */
object CscConstants {

    const val PREFS_NAME = "csc_config"
    const val MODULE_PACKAGE = "com.weitiao.tuning"

    /**
     * 本地配置被 UI 显式保存过的标记（wall-clock 毫秒），只写本地、从不迁移到 remote。
     *
     * 缺失 = 本地这份配置从未被用户在 UI 上保存过（全新安装 / 备份恢复 / 数据被清），
     * 此时它是"默认值"而不是"用户的意图"，绝不能拿它去覆盖 LSPosed remote
     * ——那会把 hook 正在用的配置静默打回默认值。
     *
     * 存在 = 本地是用户操作过的真相，可以正向覆盖 remote。
     */
    const val KEY_LOCAL_SAVED_AT = "local_config_saved_at"

    // ---- 需要欺骗的系统属性 ----
    const val PROP_SALES_CODE = "ro.csc.sales_code"
    const val PROP_COUNTRY_CODE = "ro.csc.country_code"
    const val PROP_RIL_SALES_CODE = "ril.sales_code"
    const val PROP_PERSIST_OMC_SALES_CODE = "persist.omc.sales_code"
    const val PROP_PERSIST_OMC_COUNTRY_CODE = "persist.omc.country_code"
    const val PROP_RO_OMC_SALES_CODE = "ro.omc.sales_code"
    const val PROP_RO_OMC_COUNTRY_CODE = "ro.omc.country_code"
    const val PROP_OMCNW_CODE = "ro.csc.omcnw_code"
    const val PROP_OFFICIAL_CSC = "ro.csc.official_csc"
    const val PROP_CSC_CODE = "ro.csc.csc_code"
    const val PROP_COUNTRYISO_CODE = "ro.csc.countryiso_code"
    const val PROP_BOOT_SALES_CODE = "ro.boot.sales_code"
    const val PROP_PERSIST_SYS_OMC_SALES_CODE = "persist.sys.omc_sales_code"
    const val PROP_RIL_OFFICIAL_CSCCODE = "ril.official_csccode"
    const val PROP_PERSIST_AUDIO_SALES_CODE = "persist.audio.sales_code"
    const val PROP_PERSIST_SYS_PREV_OMCNWCODE = "persist.sys.prev_omcnwcode"
    const val PROP_PERSIST_SYS_PREV_OMCNWCODE2 = "persist.sys.prev_omcnwcode2"

    const val PROP_GSM_SIM_NUMERIC = "gsm.sim.operator.numeric"
    const val PROP_GSM_OPERATOR_NUMERIC = "gsm.operator.numeric"
    const val PROP_GSM_SIM_ISO = "gsm.sim.operator.iso-country"
    const val PROP_GSM_OPERATOR_ISO = "gsm.operator.iso-country"
    const val PROP_GSM_SIM_ALPHA = "gsm.sim.operator.alpha"
    const val PROP_GSM_OPERATOR_ALPHA = "gsm.operator.alpha"

    val SPOOFED_PROPS: Map<String, PropKind> = mapOf(
        PROP_SALES_CODE to PropKind.SALES_CODE,
        PROP_RIL_SALES_CODE to PropKind.SALES_CODE,
        PROP_PERSIST_OMC_SALES_CODE to PropKind.SALES_CODE,
        PROP_RO_OMC_SALES_CODE to PropKind.SALES_CODE,
        PROP_OMCNW_CODE to PropKind.SALES_CODE,
        PROP_OFFICIAL_CSC to PropKind.SALES_CODE,
        PROP_CSC_CODE to PropKind.SALES_CODE,
        PROP_BOOT_SALES_CODE to PropKind.SALES_CODE,
        PROP_PERSIST_SYS_OMC_SALES_CODE to PropKind.SALES_CODE,
        PROP_RIL_OFFICIAL_CSCCODE to PropKind.SALES_CODE,
        PROP_PERSIST_AUDIO_SALES_CODE to PropKind.SALES_CODE,
        PROP_PERSIST_SYS_PREV_OMCNWCODE to PropKind.SALES_CODE,
        PROP_PERSIST_SYS_PREV_OMCNWCODE2 to PropKind.SALES_CODE,

        PROP_COUNTRY_CODE to PropKind.COUNTRY_CODE,
        PROP_COUNTRYISO_CODE to PropKind.COUNTRY_CODE,
        PROP_PERSIST_OMC_COUNTRY_CODE to PropKind.COUNTRY_CODE,
        PROP_RO_OMC_COUNTRY_CODE to PropKind.COUNTRY_CODE,

        PROP_GSM_SIM_NUMERIC to PropKind.SIM_NUMERIC,
        PROP_GSM_OPERATOR_NUMERIC to PropKind.SIM_NUMERIC,
        PROP_GSM_SIM_ISO to PropKind.SIM_ISO,
        PROP_GSM_OPERATOR_ISO to PropKind.SIM_ISO,
        PROP_GSM_SIM_ALPHA to PropKind.SIM_OPERATOR,
        PROP_GSM_OPERATOR_ALPHA to PropKind.SIM_OPERATOR,
    )

    enum class PropKind {
        SALES_CODE,
        COUNTRY_CODE,
        SIM_NUMERIC,
        SIM_ISO,
        SIM_OPERATOR,
    }

    // ---- 需要 hook 的类 ----
    const val CLASS_SYSTEM_PROPERTIES = "android.os.SystemProperties"
    const val CLASS_SEM_SYSTEM_PROPERTIES = "android.os.SemSystemProperties"
    const val CLASS_SEM_CSC_FEATURE = "com.samsung.android.feature.SemCscFeature"

    val COUNTRY_CODES: Map<String, String> = mapOf(
        "CHC" to "CN", "CHM" to "CN", "CHN" to "CN", "CTC" to "CN", "CHU" to "CN",
        "TGY" to "HK", "BRI" to "TW", "CWT" to "TW", "TWM" to "TW", "FET" to "TW",
        "KOO" to "KR", "SKT" to "KR", "KTC" to "KR", "LUC" to "KR", "KTT" to "KR", "LGT" to "KR",
        "XJP" to "JP", "DCM" to "JP", "KDI" to "JP", "SBM" to "JP",
        "XSP" to "SG", "MM1" to "SG", "SIN" to "SG", "STH" to "SG",
        "XID" to "ID", "XSE" to "ID", "THL" to "TH", "THO" to "TH", "XME" to "MY",
        "XTE" to "PH", "XTC" to "PH", "GLB" to "PH", "SMA" to "PH",
        "XXV" to "VN", "XEV" to "VN", "XSG" to "AE", "XSI" to "IL",
        "INS" to "IN", "INU" to "IN", "PAK" to "PK",
        "DBT" to "DE", "XEG" to "DE", "EUX" to "DE", "EUY" to "DE",
        "BTU" to "GB", "XEU" to "GB", "ITV" to "IT", "XEF" to "FR", "XEO" to "PL",
        "XEZ" to "CZ", "AUT" to "CH", "ATO" to "AT", "LUX" to "LU", "MOB" to "AT",
        "TUR" to "TR", "PHE" to "ES", "AMO" to "ES", "XEC" to "ES", "SEB" to "SE",
        "NEE" to "SE", "TEN" to "NO", "XSK" to "SK", "PRT" to "PT", "PHN" to "NL",
        "XAA" to "US", "TMB" to "US", "VZW" to "US", "ATT" to "US", "SPR" to "US", "USC" to "US",
        "XAC" to "CA", "RWC" to "CA", "TLS" to "CA", "BMC" to "CA",
        "ZTO" to "BR", "ZTM" to "BR", "ZTA" to "BR", "ZVV" to "BR",
        "XSA" to "AU", "TEL" to "AU", "OPS" to "AU", "VAU" to "AU", "NZC" to "NZ",
        "EGY" to "EG", "KSA" to "SA", "SER" to "RU", "SEK" to "UA", "XFE" to "ZA",
    )

    /** 根据销售代码推导国家码，优先查 Catalog，兜底查内置表 */
    fun countryCodeOf(salesCode: String): String? {
        val upper = salesCode.trim().uppercase()
        CscCatalog.findByCode(upper)?.countryCode?.takeIf { it.isNotBlank() }?.let { return it }
        return COUNTRY_CODES[upper]
    }
}
