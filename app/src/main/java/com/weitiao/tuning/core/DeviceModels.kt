package com.weitiao.tuning.core

/**
 * 全球主流旗舰及最新上市机型预设库
 * 规范商业名称、官方型号及设备代号。
 */
data class DeviceProfile(
    val name: String,
    val model: String,
    val device: String,
    val productName: String,
    val family: String,
    val brand: String = "samsung",
    val manufacturer: String = "samsung",
) {
    val displayName: String get() = "$name ($model)"
}

object DeviceModels {

    private val SAMSUNG_DEVICES: List<DeviceProfile> = listOf(
        // ==== Galaxy S 系列 ====
        DeviceProfile("Galaxy S26 Ultra", "SM-S9480", "a9e", "a9ezc", "Samsung"),
        DeviceProfile("Galaxy S26+", "SM-S9460", "a9p", "a9pzc", "Samsung"),
        DeviceProfile("Galaxy S26", "SM-S9410", "a9", "a9zc", "Samsung"),
        DeviceProfile("Galaxy S25 Ultra", "SM-S9380", "e3", "e3zc", "Samsung"),
        DeviceProfile("Galaxy S25+", "SM-S9360", "e2", "e2zc", "Samsung"),
        DeviceProfile("Galaxy S25", "SM-S9310", "e1", "e1zc", "Samsung"),
        DeviceProfile("Galaxy S25 Slim", "SM-S9370", "e2s", "e2szc", "Samsung"),
        DeviceProfile("Galaxy S24 Ultra", "SM-S9280", "e3q", "e3qzc", "Samsung"),
        DeviceProfile("Galaxy S24+", "SM-S9260", "e2q", "e2qzc", "Samsung"),
        DeviceProfile("Galaxy S24", "SM-S9210", "e1q", "e1qzc", "Samsung"),
        DeviceProfile("Galaxy S24 FE", "SM-S7110", "r11", "r11zc", "Samsung"),
        DeviceProfile("Galaxy S23 Ultra", "SM-S9180", "dm3q", "dm3qzc", "Samsung"),
        DeviceProfile("Galaxy S23+", "SM-S9160", "dm2q", "dm2qzc", "Samsung"),
        DeviceProfile("Galaxy S23", "SM-S9110", "dm1q", "dm1qzc", "Samsung"),
        DeviceProfile("Galaxy S22 Ultra", "SM-S9080", "b0q", "b0qzc", "Samsung"),
        DeviceProfile("Galaxy S21 Ultra 5G", "SM-G9980", "p3q", "p3qzc", "Samsung"),
        DeviceProfile("Galaxy Note20 Ultra 5G", "SM-N9860", "c2q", "c2qzc", "Samsung"),

        // ==== Galaxy Z Fold & W 系列 ====
        DeviceProfile("Galaxy Z Fold8", "SM-F9760", "q8", "q8zc", "Samsung"),
        DeviceProfile("Galaxy Z Fold8 Ultra", "SM-F9780", "q8x", "q8xzc", "Samsung"),
        DeviceProfile("Galaxy Z Fold7", "SM-F9660", "q7", "q7zc", "Samsung"),
        DeviceProfile("Galaxy Z Fold Special Edition", "SM-F958N", "q6a", "q6akz", "Samsung"),
        DeviceProfile("Galaxy Z Fold6", "SM-F9560", "q6", "q6zc", "Samsung"),
        DeviceProfile("Galaxy Z Fold5", "SM-F9460", "q5", "q5zc", "Samsung"),
        DeviceProfile("Galaxy Z Fold4", "SM-F9360", "q4", "q4zc", "Samsung"),
        DeviceProfile("Galaxy Z Fold3 5G", "SM-F9260", "q2", "q2zc", "Samsung"),
        DeviceProfile("Galaxy W25", "SM-W9025", "w25", "w25zc", "Samsung"),
        DeviceProfile("Galaxy W24", "SM-W9024", "w24", "w24zc", "Samsung"),

        // ==== Galaxy Z Flip 系列 ====
        DeviceProfile("Galaxy Z Flip8", "SM-F7760", "b8r", "b8rzc", "Samsung"),
        DeviceProfile("Galaxy Z Flip7", "SM-F7660", "b7r", "b7rzc", "Samsung"),
        DeviceProfile("Galaxy Z Flip7 FE", "SM-F7510", "b7f", "b7fzc", "Samsung"),
        DeviceProfile("Galaxy Z Flip6", "SM-F7410", "b6q", "b6qzc", "Samsung"),
        DeviceProfile("Galaxy Z Flip5", "SM-F7310", "b5q", "b5qzc", "Samsung"),
        DeviceProfile("Galaxy Z Flip4", "SM-F7210", "b4q", "b4qzc", "Samsung"),
        DeviceProfile("Galaxy W25 Flip", "SM-W7025", "w25f", "w25fzc", "Samsung"),
        DeviceProfile("Galaxy W24 Flip", "SM-W7024", "w24f", "w24fzc", "Samsung"),

        // ==== Galaxy Tab 平板系列 ====
        DeviceProfile("Galaxy Tab S10 Ultra", "SM-X9260", "gts10u", "gts10uzc", "Samsung"),
        DeviceProfile("Galaxy Tab S10+", "SM-X8260", "gts10p", "gts10pzc", "Samsung"),
        DeviceProfile("Galaxy Tab S9 Ultra", "SM-X9160", "gts9u", "gts9uzc", "Samsung"),
        DeviceProfile("Galaxy Tab S9+", "SM-X8160", "gts9p", "gts9pzc", "Samsung"),
    )

    private val XIAOMI_DEVICES: List<DeviceProfile> = listOf(
        DeviceProfile("Xiaomi 15 Ultra", "25010PN30C", "xuanyuan", "xuanyuan", "Xiaomi", "Xiaomi", "Xiaomi"),
        DeviceProfile("Xiaomi 15 Pro", "2410DPN6CC", "haotian", "haotian", "Xiaomi", "Xiaomi", "Xiaomi"),
        DeviceProfile("Xiaomi 15", "24129PN74C", "dada", "dada", "Xiaomi", "Xiaomi", "Xiaomi"),
        DeviceProfile("Xiaomi 14 Ultra", "24030PN60C", "aurora", "aurora", "Xiaomi", "Xiaomi", "Xiaomi"),
        DeviceProfile("Xiaomi 14 Pro", "23116PN5BC", "shennong", "shennong", "Xiaomi", "Xiaomi", "Xiaomi"),
        DeviceProfile("Xiaomi 14", "23127PN0CC", "houji", "houji", "Xiaomi", "Xiaomi", "Xiaomi"),
        DeviceProfile("Xiaomi 13 Ultra", "2304FPN6DC", "ishtar", "ishtar", "Xiaomi", "Xiaomi", "Xiaomi"),
        DeviceProfile("Xiaomi MIX Fold 4", "24072PX77C", "goku", "goku", "Xiaomi", "Xiaomi", "Xiaomi"),
        DeviceProfile("Xiaomi MIX Flip", "2405CPX3DG", "ruyi", "ruyi", "Xiaomi", "Xiaomi", "Xiaomi"),
        DeviceProfile("Redmi K80 Pro", "24117RK2CC", "miro", "miro", "Xiaomi", "Redmi", "Xiaomi"),
        DeviceProfile("Redmi K80", "24122RKC7C", "zorn", "zorn", "Xiaomi", "Redmi", "Xiaomi"),
        DeviceProfile("Redmi K70 Pro", "23117RK66C", "manet", "manet", "Xiaomi", "Redmi", "Xiaomi"),
        DeviceProfile("Redmi K70 Ultra", "2407FRK8EC", "rothko", "rothko", "Xiaomi", "Redmi", "Xiaomi"),
        DeviceProfile("Redmi Turbo 4 Pro", "25032RK82C", "rodin", "rodin", "Xiaomi", "Redmi", "Xiaomi"),
        DeviceProfile("Redmi Turbo 3", "24069PC21C", "peridot", "peridot", "Xiaomi", "Redmi", "Xiaomi"),
    )

    private val VIVO_DEVICES: List<DeviceProfile> = listOf(
        DeviceProfile("vivo X200 Ultra", "V2444A", "V2444A", "V2444A", "vivo", "vivo", "vivo"),
        DeviceProfile("vivo X200 Pro", "V2415A", "V2415A", "V2415A", "vivo", "vivo", "vivo"),
        DeviceProfile("vivo X200 Pro mini", "V2405A", "V2405A", "V2405A", "vivo", "vivo", "vivo"),
        DeviceProfile("vivo X200", "V2413A", "V2413A", "V2413A", "vivo", "vivo", "vivo"),
        DeviceProfile("vivo X100 Ultra", "V2366A", "V2366A", "V2366A", "vivo", "vivo", "vivo"),
        DeviceProfile("vivo X100 Pro", "V2324A", "V2324A", "V2324A", "vivo", "vivo", "vivo"),
        DeviceProfile("vivo X Fold3 Pro", "V2337A", "V2337A", "V2337A", "vivo", "vivo", "vivo"),
        DeviceProfile("vivo X Fold3", "V2303A", "V2303A", "V2303A", "vivo", "vivo", "vivo"),
        DeviceProfile("iQOO 13", "V2408A", "V2408A", "V2408A", "vivo", "iQOO", "vivo"),
        DeviceProfile("iQOO 12 Pro", "V2329A", "V2329A", "V2329A", "vivo", "iQOO", "vivo"),
        DeviceProfile("iQOO 12", "V2307A", "V2307A", "V2307A", "vivo", "iQOO", "vivo"),
        DeviceProfile("iQOO Neo10 Pro", "V2426A", "V2426A", "V2426A", "vivo", "iQOO", "vivo"),
        DeviceProfile("iQOO Neo10", "V2425A", "V2425A", "V2425A", "vivo", "iQOO", "vivo"),
        DeviceProfile("iQOO Neo9S Pro+", "V2403A", "V2403A", "V2403A", "vivo", "iQOO", "vivo"),
    )

    private val OPPO_DEVICES: List<DeviceProfile> = listOf(
        DeviceProfile("OPPO Find X8 Ultra", "PKG110", "PKG110", "PKG110", "OPPO", "OPPO", "OPPO"),
        DeviceProfile("OPPO Find X8 Pro", "PKC110", "PKC110", "PKC110", "OPPO", "OPPO", "OPPO"),
        DeviceProfile("OPPO Find X8", "PKB110", "PKB110", "PKB110", "OPPO", "OPPO", "OPPO"),
        DeviceProfile("OPPO Find N3", "PHY110", "PHY110", "PHY110", "OPPO", "OPPO", "OPPO"),
        DeviceProfile("OPPO Find N3 Flip", "PHT110", "PHT110", "PHT110", "OPPO", "OPPO", "OPPO"),
        DeviceProfile("OPPO Reno13 Pro", "PLM110", "PLM110", "PLM110", "OPPO", "OPPO", "OPPO"),
        DeviceProfile("OnePlus 13", "PJZ110", "PJZ110", "PJZ110", "OnePlus", "OnePlus", "OnePlus"),
        DeviceProfile("OnePlus 12", "PJD110", "PJD110", "PJD110", "OnePlus", "OnePlus", "OnePlus"),
        DeviceProfile("OnePlus Ace 5 Pro", "PKR110", "PKR110", "PKR110", "OnePlus", "OnePlus", "OnePlus"),
        DeviceProfile("OnePlus Ace 5", "PKK110", "PKK110", "PKK110", "OnePlus", "OnePlus", "OnePlus"),
        DeviceProfile("OnePlus Ace 3 Pro", "PJX110", "PJX110", "PJX110", "OnePlus", "OnePlus", "OnePlus"),
        DeviceProfile("OnePlus Open", "CPH2551", "CPH2551", "CPH2551", "OnePlus", "OnePlus", "OnePlus"),
    )

    private val HUAWEI_HONOR_DEVICES: List<DeviceProfile> = listOf(
        DeviceProfile("HUAWEI Mate XT 非凡大师", "GND-AL00", "GND-AL00", "GND-AL00", "HUAWEI", "HUAWEI", "HUAWEI"),
        DeviceProfile("HUAWEI Mate 70 RS 非凡大师", "ADY-AL10", "ADY-AL10", "ADY-AL10", "HUAWEI", "HUAWEI", "HUAWEI"),
        DeviceProfile("HUAWEI Mate 70 Pro+", "ADY-AL00", "ADY-AL00", "ADY-AL00", "HUAWEI", "HUAWEI", "HUAWEI"),
        DeviceProfile("HUAWEI Mate 70 Pro", "HBN-AL00", "HBN-AL00", "HBN-AL00", "HUAWEI", "HUAWEI", "HUAWEI"),
        DeviceProfile("HUAWEI Mate 70", "HBN-AL10", "HBN-AL10", "HBN-AL10", "HUAWEI", "HUAWEI", "HUAWEI"),
        DeviceProfile("HUAWEI Pura 70 Ultra", "HBP-AL00", "HBP-AL00", "HBP-AL00", "HUAWEI", "HUAWEI", "HUAWEI"),
        DeviceProfile("HUAWEI Pura 70 Pro+", "HNE-AL00", "HNE-AL00", "HNE-AL00", "HUAWEI", "HUAWEI", "HUAWEI"),
        DeviceProfile("HUAWEI Pura 70 Pro", "HNE-AL00", "HNE-AL00", "HNE-AL00", "HUAWEI", "HUAWEI", "HUAWEI"),
        DeviceProfile("HUAWEI Mate 60 Pro+", "ALN-AL10", "ALN-AL10", "ALN-AL10", "HUAWEI", "HUAWEI", "HUAWEI"),
        DeviceProfile("HUAWEI Mate 60 Pro", "ALN-AL00", "ALN-AL00", "ALN-AL00", "HUAWEI", "HUAWEI", "HUAWEI"),
        DeviceProfile("HUAWEI Mate X5", "ALT-AL10", "ALT-AL10", "ALT-AL10", "HUAWEI", "HUAWEI", "HUAWEI"),
        DeviceProfile("Honor Magic7 RSR 保时捷设计", "PTP-AN20", "PTP-AN20", "PTP-AN20", "Honor", "HONOR", "HONOR"),
        DeviceProfile("Honor Magic7 Pro", "PTP-AN10", "PTP-AN10", "PTP-AN10", "Honor", "HONOR", "HONOR"),
        DeviceProfile("Honor Magic7", "PTP-AN00", "PTP-AN00", "PTP-AN00", "Honor", "HONOR", "HONOR"),
        DeviceProfile("Honor Magic V3", "FCP-AN10", "FCP-AN10", "FCP-AN10", "Honor", "HONOR", "HONOR"),
        DeviceProfile("Honor Magic6 Pro", "BVL-AN16", "BVL-AN16", "BVL-AN16", "Honor", "HONOR", "HONOR"),
    )

    private val GOOGLE_DEVICES: List<DeviceProfile> = listOf(
        DeviceProfile("Pixel 10 Pro XL", "Pixel 10 Pro XL", "frankel", "frankel", "Google", "Google", "Google"),
        DeviceProfile("Pixel 10 Pro", "Pixel 10 Pro", "blazer", "blazer", "Google", "Google", "Google"),
        DeviceProfile("Pixel 10", "Pixel 10", "mustang", "mustang", "Google", "Google", "Google"),
        DeviceProfile("Pixel 9 Pro XL", "Pixel 9 Pro XL", "komodo", "komodo", "Google", "Google", "Google"),
        DeviceProfile("Pixel 9 Pro Fold", "Pixel 9 Pro Fold", "comet", "comet", "Google", "Google", "Google"),
        DeviceProfile("Pixel 9 Pro", "Pixel 9 Pro", "caiman", "caiman", "Google", "Google", "Google"),
        DeviceProfile("Pixel 9", "Pixel 9", "tokay", "tokay", "Google", "Google", "Google"),
        DeviceProfile("Pixel 8 Pro", "Pixel 8 Pro", "husky", "husky", "Google", "Google", "Google"),
        DeviceProfile("Pixel 8", "Pixel 8", "shiba", "shiba", "Google", "Google", "Google"),
    )

    private val GAMING_OTHER_DEVICES: List<DeviceProfile> = listOf(
        DeviceProfile("ROG Phone 9 Pro", "ASUS_AI2501", "ASUS_AI2501", "ASUS_AI2501", "Gaming", "asus", "asus"),
        DeviceProfile("ROG Phone 9", "ASUS_AI2500", "ASUS_AI2500", "ASUS_AI2500", "Gaming", "asus", "asus"),
        DeviceProfile("ROG Phone 8 Pro", "ASUS_AI2401", "ASUS_AI2401", "ASUS_AI2401", "Gaming", "asus", "asus"),
        DeviceProfile("RedMagic 10 Pro+", "NX789J", "NX789J", "NX789J", "Gaming", "nubia", "nubia"),
        DeviceProfile("RedMagic 10 Pro", "NX779J", "NX779J", "NX779J", "Gaming", "nubia", "nubia"),
        DeviceProfile("RedMagic 9S Pro+", "NX769J-S", "NX769J-S", "NX769J-S", "Gaming", "nubia", "nubia"),
        DeviceProfile("MEIZU 21 PRO", "MEIZU 21 PRO", "meizu21Pro", "meizu21Pro", "MEIZU", "meizu", "meizu"),
        DeviceProfile("MEIZU 21", "MEIZU 21", "meizu21", "meizu21", "MEIZU", "meizu", "meizu"),
        DeviceProfile("MEIZU 20 PRO", "MEIZU 20 PRO", "meizu20Pro", "meizu20Pro", "MEIZU", "meizu", "meizu"),
        DeviceProfile("Sony Xperia 1 VI", "XQ-EC72", "XQ-EC72", "XQ-EC72", "Sony", "Sony", "Sony"),
        DeviceProfile("Sony Xperia 1 V", "XQ-DQ72", "XQ-DQ72", "XQ-DQ72", "Sony", "Sony", "Sony"),
        DeviceProfile("iPhone 17 Pro Max", "iPhone 17 Pro Max", "A3527", "A3527", "Apple", "Apple", "Apple"),
        DeviceProfile("iPhone 16 Pro Max", "iPhone 16 Pro Max", "A3297", "A3297", "Apple", "Apple", "Apple"),
        DeviceProfile("iPhone 16 Pro", "iPhone 16 Pro", "A3294", "A3294", "Apple", "Apple", "Apple"),
        DeviceProfile("iPhone 15 Pro Max", "iPhone 15 Pro Max", "A3106", "A3106", "Apple", "Apple", "Apple"),
    )

    private fun normalizeFamily(family: String): String = when (family.lowercase()) {
        "pixel", "google" -> "Google"
        "iphone", "apple" -> "Apple"
        "iqoo" -> "vivo"
        "redmi" -> "Xiaomi"
        "honor" -> "Honor"
        "huawei" -> "HUAWEI"
        "samsung" -> "Samsung"
        "oppo" -> "OPPO"
        "oneplus" -> "OnePlus"
        "meizu" -> "MEIZU"
        "sony" -> "Sony"
        "gaming", "asus", "nubia" -> "Gaming"
        else -> family
    }

    val ALL: List<DeviceProfile> = (
        SAMSUNG_DEVICES +
            XIAOMI_DEVICES +
            VIVO_DEVICES +
            OPPO_DEVICES +
            HUAWEI_HONOR_DEVICES +
            GOOGLE_DEVICES +
            GAMING_OTHER_DEVICES +
            GeneratedDeviceProfiles.ALL
        )
        .map { it.copy(name = it.name.trim(), family = normalizeFamily(it.family)) }
        .distinctBy { it.name }

    val BY_FAMILY: Map<String, List<DeviceProfile>> = ALL.groupBy { it.family }

    private val NAME_EXACT_MAP: Map<String, DeviceProfile> by lazy {
        val map = HashMap<String, DeviceProfile>(ALL.size * 3)
        for (item in ALL) {
            map.putIfAbsent(item.name.lowercase(), item)
            map.putIfAbsent(item.model.lowercase(), item)
            map.putIfAbsent(item.displayName.lowercase(), item)
        }
        map
    }

    private val NORMALIZED_NAME_MAP: Map<String, DeviceProfile> by lazy {
        val map = HashMap<String, DeviceProfile>(ALL.size)
        for (item in ALL) {
            map.putIfAbsent(normalizeKey(item.name), item)
        }
        map
    }

    private val CLEAN_MODEL_MAP: Map<String, DeviceProfile> by lazy {
        val map = HashMap<String, DeviceProfile>(ALL.size)
        for (item in ALL) {
            val q = item.model.lowercase().filter { it.isLetterOrDigit() }
            map.putIfAbsent(q, item)
        }
        map
    }

    private fun normalizeKey(s: String): String {
        var res = s.lowercase()
        val prefixes = listOf("galaxy ", "samsung ", "xiaomi ", "redmi ", "vivo ", "huawei ", "apple ", "google ", "oppo ", "oneplus ", "meizu ", "sony ")
        for (prefix in prefixes) {
            if (res.startsWith(prefix)) {
                res = res.removePrefix(prefix)
                break
            }
        }
        return res.filter { it.isLetterOrDigit() }
    }

    fun findByName(name: String): DeviceProfile? {
        val trimmed = name.trim()
        if (trimmed.isBlank()) return null

        val lower = trimmed.lowercase()
        // 1. 精确名称、型号与展示名匹配 (O(1))
        NAME_EXACT_MAP[lower]?.let { return it }

        // 2. 规范化前缀与字母数字完全对齐匹配 (O(1))
        val qKey = normalizeKey(trimmed)
        NORMALIZED_NAME_MAP[qKey]?.let { return it }

        // 3. 型号字母数字匹配（去除连字符）(O(1))
        val qModel = lower.filter { it.isLetterOrDigit() }
        CLEAN_MODEL_MAP[qModel]?.let { return it }

        // 4. 动态生成保底
        return resolveDynamic(trimmed)
    }

    fun resolveDynamic(input: String): DeviceProfile {
        val lower = input.lowercase()
        val brand = when {
            lower.contains("samsung") || lower.startsWith("galaxy") || lower.startsWith("sm-") || lower.startsWith("w2") -> "samsung"
            lower.contains("xiaomi") || lower.startsWith("mi ") || lower.contains("mix") -> "Xiaomi"
            lower.contains("redmi") -> "Redmi"
            lower.contains("vivo") -> "vivo"
            lower.contains("iqoo") -> "iQOO"
            lower.contains("oppo") -> "OPPO"
            lower.contains("oneplus") -> "OnePlus"
            lower.contains("huawei") || lower.contains("mate") || lower.contains("pura") || lower.contains("honor") -> "HUAWEI"
            lower.contains("pixel") || lower.contains("google") -> "Google"
            lower.contains("iphone") || lower.contains("apple") -> "Apple"
            lower.contains("meizu") -> "meizu"
            lower.contains("sony") || lower.contains("xperia") -> "Sony"
            lower.contains("rog") || lower.contains("asus") -> "asus"
            lower.contains("redmagic") || lower.contains("nubia") -> "nubia"
            else -> "samsung"
        }
        val mfr = when (brand.lowercase()) {
            "redmi" -> "Xiaomi"
            "iqoo" -> "vivo"
            "oneplus" -> "OnePlus"
            "asus" -> "asus"
            "nubia" -> "nubia"
            else -> brand
        }
        val family = normalizeFamily(brand)
        val cleanName = input.trim()
        val cleanDevice = cleanName.lowercase().filter { it.isLetterOrDigit() }.ifBlank { "device" }
        return DeviceProfile(
            name = cleanName,
            model = cleanName,
            device = cleanDevice,
            productName = cleanDevice,
            family = family,
            brand = brand,
            manufacturer = mfr,
        )
    }
}
