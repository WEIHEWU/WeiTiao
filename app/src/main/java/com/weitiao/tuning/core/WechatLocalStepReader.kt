package com.weitiao.tuning.core

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Calendar
import java.util.TimeZone

/**
 * 微信本地计步状态快照（读取自微信私有 MMKV 文件 `SportKv`）。
 *
 * 用于解决临时 Root 重启空窗期或未注入期间，微信本地已自行记录步数与传感器基准导致
 * “微调与微信各走各的、重复累加超上限或恢复注入后传感器数值跳变”的问题。
 */
data class LocalWechatStepSnapshot(
    val todayKey: Int,
    val todaySteps: Long,
    val lastSensorValue: Float,
    val hasSensorValue: Boolean,
    val saveTimeMs: Long,
)

/**
 * 为 [com.weitiao.tuning.WechatHooks.WechatStepEngine] 提供微信本地已持久化步数数据。
 */
internal interface LocalWechatStepProvider {
    fun readToday(todayKey: Int): LocalWechatStepSnapshot?
}

/**
 * 直接以只读方式解析微信 `files/mmkv/SportKv`（及 `SportKv.crc`）。
 *
 * 不依赖反射调用微信内部 `com.tencent.mmkv.MMKV`，彻底规避进程初始化时机或
 * `SINGLE_PROCESS_MODE` / `MULTI_PROCESS_MODE` 冲突导致的异常，同时支持跨主进程与 `:push` 进程实时感知写入。
 */
internal object SportKvStepReader {

    private const val KEY_STEP_DETAIL_INFO = "KEY_STEP_DETAIL_INFO"
    private const val KEY_LAST_UPLOAD_STEP_TIME = "KEY_LAST_UPLOAD_DEVICE_STEP_TIME_LONG"
    private const val KEY_LAST_UPLOAD_STEP_COUNT = "KEY_LAST_UPLOAD_DEVICE_STEP_COUNT_LONG"
    private const val MAX_REASONABLE_MMKV_SIZE = 1024 * 1024

    fun fromFiles(
        kvFile: File,
        crcFile: File? = File(kvFile.parentFile, "${kvFile.name}.crc"),
        timeZone: TimeZone = TimeZone.getDefault(),
    ): LocalWechatStepProvider {
        return object : LocalWechatStepProvider {
            @Volatile private var lastSignature: String = ""
            @Volatile private var lastTodayKey: Int = 0
            @Volatile private var cachedSnapshot: LocalWechatStepSnapshot? = null

            override fun readToday(todayKey: Int): LocalWechatStepSnapshot? {
                if (!kvFile.exists() || !kvFile.isFile) return null
                val crcBytes = readCrcHeader(crcFile)
                val sig = buildHeaderSignature(kvFile, crcBytes)
                if (sig.isNotEmpty() && sig == lastSignature && todayKey == lastTodayKey) {
                    return cachedSnapshot
                }
                val kvBytes = runCatching { kvFile.readBytes() }.getOrNull() ?: return cachedSnapshot
                val parsed = parseSnapshot(kvBytes, crcBytes, todayKey, timeZone)
                lastSignature = sig
                lastTodayKey = todayKey
                cachedSnapshot = parsed
                return parsed
            }
        }
    }

    internal fun parseSnapshot(
        kvBytes: ByteArray,
        crcBytes: ByteArray?,
        todayKey: Int,
        timeZone: TimeZone = TimeZone.getDefault(),
    ): LocalWechatStepSnapshot? {
        if (kvBytes.size <= 8) return null

        val hdrActualSize = readInt32Le(kvBytes, 0)
        val crcActualSize = if (crcBytes != null && crcBytes.size >= 32) {
            readInt32Le(crcBytes, 28)
        } else {
            0
        }
        val actualSize = when {
            crcActualSize > 0 && hdrActualSize == 0 -> crcActualSize
            hdrActualSize > 0 -> hdrActualSize
            crcActualSize > 0 -> crcActualSize
            else -> return null
        }
        if (actualSize <= 0 || actualSize > MAX_REASONABLE_MMKV_SIZE) return null

        val end = minOf(kvBytes.size, 4 + actualSize)
        if (end <= 8) return null

        // MMKV v4/v5 数据区从偏移 8 开始；若老格式从偏移 4 开始则自动回退尝试 4
        val records = parseKvRecords(kvBytes, startOffset = 8, endOffset = end)
            ?: parseKvRecords(kvBytes, startOffset = 4, endOffset = end)
            ?: return null

        val detailRaw = records[KEY_STEP_DETAIL_INFO]?.let(::decodeMmkvString)
        val uploadTimeMs = records[KEY_LAST_UPLOAD_STEP_TIME]?.let(::decodeMmkvVarint) ?: 0L
        val uploadCount = records[KEY_LAST_UPLOAD_STEP_COUNT]?.let(::decodeMmkvVarint) ?: 0L

        val uploadIsToday = uploadTimeMs > 0L && dayKeyOfMillis(uploadTimeMs, timeZone) == todayKey
        val todayUploadSteps = if (uploadIsToday) uploadCount.coerceAtLeast(0L) else 0L

        if (!detailRaw.isNullOrBlank()) {
            val parts = detailRaw.split(',')
            if (parts.size >= 7) {
                val dayStartMs = parts[0].trim().toLongOrNull() ?: 0L
                val saveTimeMs = parts[1].trim().toLongOrNull() ?: 0L
                val sensorValue = parts[4].trim().toDoubleOrNull()?.toFloat()
                val detailSteps = parts[5].trim().toLongOrNull()?.coerceAtLeast(0L) ?: 0L
                val preSensorStep = parts[6].trim().toDoubleOrNull()?.toFloat()

                if (dayStartMs > 0L && dayKeyOfMillis(dayStartMs, timeZone) == todayKey) {
                    val resolvedSensor = sensorValue
                        ?: preSensorStep
                        ?: 0f
                    return LocalWechatStepSnapshot(
                        todayKey = todayKey,
                        todaySteps = maxOf(detailSteps, todayUploadSteps),
                        lastSensorValue = resolvedSensor.coerceAtLeast(0f),
                        hasSensorValue = sensorValue != null || preSensorStep != null,
                        saveTimeMs = maxOf(saveTimeMs, if (uploadIsToday) uploadTimeMs else 0L),
                    )
                }
            }
        }

        if (todayUploadSteps > 0L) {
            return LocalWechatStepSnapshot(
                todayKey = todayKey,
                todaySteps = todayUploadSteps,
                lastSensorValue = 0f,
                hasSensorValue = false,
                saveTimeMs = uploadTimeMs,
            )
        }

        return null
    }

    private fun readCrcHeader(crcFile: File?): ByteArray? {
        if (crcFile == null || !crcFile.exists() || !crcFile.isFile) return null
        return runCatching {
            RandomAccessFile(crcFile, "r").use { raf ->
                val len = minOf(raf.length().toInt(), 40)
                if (len < 32) return@use null
                ByteArray(len).also { raf.readFully(it) }
            }
        }.getOrNull()
    }

    private fun buildHeaderSignature(kvFile: File, crcBytes: ByteArray?): String {
        if (crcBytes == null || crcBytes.size < 32) {
            return "${kvFile.length()}:${kvFile.lastModified()}"
        }
        val digest = readInt32Le(crcBytes, 0)
        val version = readInt32Le(crcBytes, 4)
        val sequence = readInt32Le(crcBytes, 8)
        val actualSize = readInt32Le(crcBytes, 28)
        return "$digest:$version:$sequence:$actualSize:${kvFile.length()}"
    }

    private fun parseKvRecords(buf: ByteArray, startOffset: Int, endOffset: Int): Map<String, ByteArray>? {
        if (startOffset >= endOffset) return null
        val map = LinkedHashMap<String, ByteArray>()
        var pos = startOffset
        while (pos < endOffset) {
            val (keyLen, afterKeyLen) = readVarint32(buf, pos, endOffset) ?: return null
            if (keyLen <= 0 || keyLen > 256 || afterKeyLen + keyLen > endOffset) return null
            val keyBytes = buf.copyOfRange(afterKeyLen, afterKeyLen + keyLen)
            if (!isLikelyValidKey(keyBytes)) return null
            val key = String(keyBytes, Charsets.UTF_8)
            pos = afterKeyLen + keyLen

            val (valLen, afterValLen) = readVarint32(buf, pos, endOffset) ?: return null
            if (valLen < 0 || afterValLen + valLen > endOffset) return null
            map[key] = buf.copyOfRange(afterValLen, afterValLen + valLen)
            pos = afterValLen + valLen
        }
        return if (pos == endOffset && map.isNotEmpty()) map else null
    }

    private fun isLikelyValidKey(bytes: ByteArray): Boolean {
        for (b in bytes) {
            val c = b.toInt() and 0xFF
            if (c < 0x20 || c == 0x7F) return false
        }
        return true
    }

    private fun decodeMmkvString(valueBytes: ByteArray): String? {
        if (valueBytes.isEmpty()) return ""
        val (strLen, offset) = readVarint32(valueBytes, 0, valueBytes.size) ?: return null
        if (strLen < 0 || offset + strLen > valueBytes.size) return null
        return String(valueBytes, offset, strLen, Charsets.UTF_8)
    }

    private fun decodeMmkvVarint(valueBytes: ByteArray): Long? {
        if (valueBytes.isEmpty()) return null
        var result = 0L
        var shift = 0
        for (i in valueBytes.indices) {
            val b = valueBytes[i].toInt() and 0xFF
            result = result or ((b.toLong() and 0x7FL) shl shift)
            if ((b and 0x80) == 0) return result
            shift += 7
            if (shift >= 64) return null
        }
        return null
    }

    private fun readVarint32(buf: ByteArray, start: Int, limit: Int): Pair<Int, Int>? {
        var pos = start
        var result = 0
        var shift = 0
        while (pos < limit) {
            val b = buf[pos++].toInt() and 0xFF
            result = result or ((b and 0x7F) shl shift)
            if ((b and 0x80) == 0) return result to pos
            shift += 7
            if (shift >= 32) return null
        }
        return null
    }

    private fun readInt32Le(buf: ByteArray, offset: Int): Int {
        if (offset + 4 > buf.size) return 0
        return ByteBuffer.wrap(buf, offset, 4).order(ByteOrder.LITTLE_ENDIAN).int
    }

    internal fun dayKeyOfMillis(millis: Long, timeZone: TimeZone = TimeZone.getDefault()): Int {
        val cal = Calendar.getInstance(timeZone).apply { timeInMillis = millis }
        return cal.get(Calendar.YEAR) * 10000 +
            (cal.get(Calendar.MONTH) + 1) * 100 +
            cal.get(Calendar.DAY_OF_MONTH)
    }
}
