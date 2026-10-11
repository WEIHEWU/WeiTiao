package com.weitiao.tuning.core

import android.content.Context
import android.telephony.TelephonyManager
import java.util.Locale

/**
 * 真实设备原生出厂与硬件基准信息探测器。
 * 动态探测真实设备的 CSC 销售代码、SIM MCC 与国家代码，杜绝硬编码特定国家。
 */
object NativeDevice {

    data class Info(
        val salesCode: String,
        val countryCode: String,
        val simMcc: String,
        val simCountryIso: String,
    )

    /** 读取底层真实原生参数（模块 UI 进程未被 Hook，获取未伪装的硬件真实值） */
    fun detect(context: Context? = null): Info {
        val rawSalesCode = readFirstValidProp(
            CscConstants.PROP_SALES_CODE,
            "ril.official_csccode",
            "ril.sales_code",
            "ro.boot.sales_code",
            "ro.csc.omcnw_code",
        )

        val rawCountryCode = readFirstValidProp(
            CscConstants.PROP_COUNTRY_CODE,
            "ro.csc.countryiso_code",
            "ro.csc.country_code",
        ).let { normalizeCountryCode(it) }

        var rawSimMcc = ""
        var rawSimIso = ""

        if (context != null) {
            runCatching {
                val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
                tm?.simOperator?.takeIf { it.length >= 3 }?.let {
                    rawSimMcc = it.substring(0, 3)
                }
                tm?.simCountryIso?.takeIf { it.isNotBlank() }?.let {
                    rawSimIso = it.lowercase()
                }
            }
        }

        if (rawSimMcc.isBlank()) {
            val numeric = readFirstValidProp("gsm.sim.operator.numeric", "gsm.operator.numeric")
            if (numeric.length >= 3) {
                rawSimMcc = numeric.substring(0, 3)
            }
        }

        if (rawSimIso.isBlank()) {
            rawSimIso = readFirstValidProp("gsm.sim.operator.iso-country", "gsm.operator.iso-country").lowercase()
        }

        // 最终默认兜底：若系统均未配置，根据系统当前 Locale 推导，而非硬编码
        val fallbackCountry = Locale.getDefault().country.takeIf { it.isNotBlank() } ?: "US"
        val resolvedCountry = rawCountryCode.ifBlank { fallbackCountry }
        val resolvedSalesCode = rawSalesCode.ifBlank {
            when (resolvedCountry.uppercase()) {
                "US" -> "XAA"
                "GB" -> "BTU"
                "DE" -> "DBT"
                "FR" -> "XEF"
                "KR" -> "KOO"
                "JP" -> "XJP"
                "HK" -> "TGY"
                "TW" -> "BRI"
                "CN" -> "CHC"
                "SG" -> "XSP"
                "AU" -> "XSA"
                else -> "XAA"
            }
        }
        val resolvedSimMcc = rawSimMcc.ifBlank {
            CscSim.mccOfSalesCode(resolvedSalesCode) ?: CscSim.ISO_TO_MCC[resolvedCountry.lowercase()] ?: "310"
        }
        val resolvedSimIso = rawSimIso.ifBlank { resolvedCountry.lowercase() }

        return Info(
            salesCode = resolvedSalesCode,
            countryCode = resolvedCountry,
            simMcc = resolvedSimMcc,
            simCountryIso = resolvedSimIso,
        )
    }

    private fun readFirstValidProp(vararg keys: String): String {
        val clazz = runCatching { Class.forName("android.os.SystemProperties") }.getOrNull() ?: return ""
        val get = runCatching { clazz.getMethod("get", String::class.java, String::class.java) }.getOrNull() ?: return ""
        for (key in keys) {
            val value = runCatching { get.invoke(null, key, "") as? String }.getOrNull().orEmpty().trim()
            if (value.isNotBlank() && !value.equals("unknown", ignoreCase = true) && !value.equals("null", ignoreCase = true)) {
                return value
            }
        }
        return ""
    }

    private fun normalizeCountryCode(raw: String): String {
        if (raw.isBlank()) return ""
        if (raw.length == 2 && raw.all { it.isLetter() }) return raw.uppercase()
        return COUNTRY_NAME_MAP[raw] ?: raw.take(2).uppercase()
    }

    private val COUNTRY_NAME_MAP = mapOf(
        "China" to "CN", "Hong Kong" to "HK", "Taiwan" to "TW", "Macau" to "MO",
        "Korea" to "KR", "South Korea" to "KR", "Japan" to "JP",
        "United States" to "US", "USA" to "US", "Canada" to "CA", "Mexico" to "MX",
        "United Kingdom" to "GB", "Great Britain" to "GB", "Germany" to "DE",
        "France" to "FR", "Italy" to "IT", "Spain" to "ES", "Netherlands" to "NL",
        "Switzerland" to "CH", "Sweden" to "SE", "Norway" to "NO", "Poland" to "PL",
        "Australia" to "AU", "New Zealand" to "NZ", "Singapore" to "SG",
        "Malaysia" to "MY", "Thailand" to "TH", "Vietnam" to "VN", "Indonesia" to "ID",
        "Philippines" to "PH", "India" to "IN", "United Arab Emirates" to "AE",
        "Saudi Arabia" to "SA", "Brazil" to "BR", "Russia" to "RU",
    )
}
