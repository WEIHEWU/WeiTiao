package com.weitiao.tuning.data

import android.content.Context
import org.json.JSONArray

/** 单个 CSC 条目，包含精准中文国家名称、运营商、数字代码、ISO 国家代码与 SIM MCC */
data class CscEntry(
    val code: String,
    val region: String,
    val countryCode: String,
    val mcc: String,
    val country: String = "",
    val operator: String = "",
    val numeric: String = "",
) {
    /** 预构建小写联合搜索索引，搜索时 1 次 contains 代替 6 次 contains */
    val searchKey: String = "$country $operator $numeric $code $region $countryCode".lowercase()
}

/**
 * CSC 目录：从 assets/csc_list.json 加载（80 个原版代码），进程内缓存。
 */
object CscCatalog {

    @Volatile
    private var cached: List<CscEntry>? = null
    @Volatile
    private var codeMap: Map<String, CscEntry>? = null

    fun load(context: Context): List<CscEntry> {
        cached?.let { return it }
        val text = context.assets.open(CSC_LIST_ASSET).bufferedReader().use { it.readText() }
        val array = JSONArray(text)
        val list = (0 until array.length()).map { i ->
            val obj = array.getJSONObject(i)
            CscEntry(
                code = obj.getString("code"),
                region = obj.getString("region"),
                countryCode = obj.optString("countryCode", "US"),
                mcc = obj.optString("mcc", "310"),
                country = obj.optString("country", ""),
                operator = obj.optString("operator", ""),
                numeric = obj.optString("numeric", ""),
            )
        }
        cached = list
        codeMap = list.associateBy { it.code.uppercase() }
        return list
    }

    fun findByCode(code: String): CscEntry? =
        codeMap?.get(code.trim().uppercase())

    /** 搜索：按中文国家名称、运营商名称、数字代码、CSC 代码搜索 */
    fun search(list: List<CscEntry>, query: String): List<CscEntry> {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return list
        return list.filter { it.searchKey.contains(q) }
    }

    private const val CSC_LIST_ASSET = "csc_list.json"
}
