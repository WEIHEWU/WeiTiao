package com.weitiao.tuning.core

/**
 * 全球主流旗舰及最新上市机型预设库
 * 严格执行三年内发布（2023.10 - 2026.10）与纯旗舰（Pro / Pro+ / Ultra / Fold / Flip / RS / 电竞机皇）标准。
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

    val isFold: Boolean
        get() = name.contains("Fold", ignoreCase = true) ||
            name.contains("Duo", ignoreCase = true) ||
            name.contains("Open", ignoreCase = true) ||
            name.contains("Find N", ignoreCase = true) ||
            name.contains("Mate X", ignoreCase = true) ||
            name.contains("Magic V", ignoreCase = true) ||
            (brand.equals("samsung", ignoreCase = true) && (model.startsWith("SM-F9", ignoreCase = true) || model.startsWith("SM-W9", ignoreCase = true)))

    val isFlip: Boolean
        get() = !isFold && (
            name.contains("Flip", ignoreCase = true) ||
                name.contains("Pocket", ignoreCase = true) ||
                (brand.equals("samsung", ignoreCase = true) && (model.startsWith("SM-F7", ignoreCase = true) || model.startsWith("SM-W7", ignoreCase = true)))
            )

    val isTablet: Boolean
        get() = !isFold && !isFlip && (
            name.contains("Tab", ignoreCase = true) ||
                name.contains("Pad", ignoreCase = true) ||
                (brand.equals("samsung", ignoreCase = true) && model.startsWith("SM-X", ignoreCase = true))
            )

    val characteristics: String
        get() = if (isTablet) "tablet,nosdcard" else "nosdcard"

    val socModel: String
        get() {
            val n = name.lowercase()
            return when (brand.lowercase()) {
                "apple" -> when {
                    n.contains("18") || n.contains("duo") -> "Apple A20 Pro"
                    n.contains("17") -> "Apple A19 Pro"
                    n.contains("16") -> "Apple A18 Pro"
                    else -> "Apple A17 Pro"
                }
                "huawei" -> when {
                    n.contains("90") || n.contains("xt 2") -> "Kirin 9040"
                    n.contains("80") || n.contains("xts") || n.contains("x7") || n.contains("pura x") -> "Kirin 9030"
                    n.contains("xt") || n.contains("x6") || (n.contains("70") && !n.contains("pura 70")) -> "Kirin 9020"
                    n.contains("pura 70") -> "Kirin 9010"
                    else -> "Kirin 9000S"
                }
                "google" -> when {
                    n.contains("11") -> "Tensor G6"
                    n.contains("10") -> "Tensor G5"
                    n.contains("9") -> "Tensor G4"
                    n.contains("8") -> "Tensor G3"
                    else -> "Tensor G2"
                }
                else -> when {
                    // 联发科天玑旗舰 (Dimensity 9500 / 9400+ / 9400 / 9300+)
                    n.contains("x500") ||
                        (n.contains("x300") && !n.contains("ultra")) ||
                        n.contains("find x10") ||
                        (n.contains("find x9") && !n.contains("ultra")) ||
                        n.contains("k100") ||
                        n.contains("k90 至尊") ||
                        n.contains("ace 6 ultra") ||
                        n.contains("ace 5 ultra") ||
                        n.contains("neo 11 ultra") -> "MT6993"
                    (n.contains("x200") && !n.contains("ultra")) ||
                        (n.contains("find x8") && !n.contains("ultra")) ||
                        n.contains("k80 至尊") ||
                        n.contains("neo 10 pro") ||
                        n.contains("neo10 pro") ||
                        n.contains("tab s11") -> "MT6991"
                    n.contains("x100s") ||
                        n.contains("x100 pro") ||
                        n.contains("k70 至尊") ||
                        n.contains("tab s10") -> "MT6989"
                    // 高通骁龙 8 Elite Gen 2 / Gen 5 (SM8850)
                    n.contains("xiaomi 18") ||
                        n.contains("xiaomi 17") ||
                        n.contains("k90") ||
                        n.contains("oneplus 16") ||
                        n.contains("oneplus 15") ||
                        n.contains("ace 6") ||
                        n.contains("s26") ||
                        n.contains("fold8") ||
                        n.contains("flip8") ||
                        n.contains("w26") ||
                        n.contains("x300 ultra") ||
                        n.contains("x fold6") ||
                        n.contains("iqoo 16") ||
                        n.contains("iqoo 15") ||
                        n.contains("neo 11") ||
                        n.contains("find x9 ultra") ||
                        n.contains("find n6") ||
                        n.contains("gt 8") ||
                        n.contains("magic9") ||
                        n.contains("magic8") ||
                        n.contains("magic v6") ||
                        n.contains("redmagic 11") ||
                        n.contains("meizu 22") ||
                        n.contains("xperia 1 viii") ||
                        n.contains("xperia 1 vii") -> "SM8850"
                    // 高通骁龙 8 Elite (SM8750-AB)
                    n.contains("xiaomi 15") ||
                        n.contains("mix flip 2") ||
                        n.contains("k80 pro") ||
                        n.contains("oneplus 13") ||
                        n.contains("ace 5 pro") ||
                        n.contains("s25") ||
                        n.contains("fold7") ||
                        n.contains("flip7") ||
                        n.contains("x200 ultra") ||
                        n.contains("x fold5") ||
                        n.contains("iqoo 13") ||
                        n.contains("find x8 ultra") ||
                        n.contains("find n5") ||
                        n.contains("gt7 pro") ||
                        n.contains("gt 7 pro") ||
                        n.contains("magic7") ||
                        n.contains("magic v5") ||
                        n.contains("magic v flip2") ||
                        n.contains("redmagic 10") ||
                        n.contains("rog phone 9") -> "SM8750-AB"
                    // 高通骁龙 8 Gen 3 (SM8650-AB)
                    else -> "SM8650-AB"
                }
            }
        }

    val socManufacturer: String
        get() = when {
            brand.equals("apple", ignoreCase = true) -> "Apple"
            brand.equals("huawei", ignoreCase = true) -> "HiSilicon"
            brand.equals("google", ignoreCase = true) -> "Google"
            socModel.startsWith("MT") -> "MediaTek"
            else -> "Qualcomm"
        }
}

object DeviceModels {

    private val APPLE_DEVICES: List<DeviceProfile> = listOf(
        // 2026.09 首款双屏折叠旗舰 (标识符 iPhone19,4 / 设备 iPhone Duo / 产品 A3720)
        DeviceProfile("iPhone Duo", "iPhone19,4", "iPhone Duo", "A3720", "Apple", "Apple", "Apple"),
        // 2026.09 旗舰系列 (国行 A3718 / A3715)
        DeviceProfile("iPhone 18 Pro Max", "iPhone 18 Pro Max", "A3718", "A3718", "Apple", "Apple", "Apple"),
        DeviceProfile("iPhone 18 Pro", "iPhone 18 Pro", "A3715", "A3715", "Apple", "Apple", "Apple"),
        // 2025.09 旗舰系列 (国行 A3527 / A3524 / A3515)
        DeviceProfile("iPhone 17 Pro Max", "iPhone 17 Pro Max", "A3527", "A3527", "Apple", "Apple", "Apple"),
        DeviceProfile("iPhone 17 Pro", "iPhone 17 Pro", "A3524", "A3524", "Apple", "Apple", "Apple"),
        DeviceProfile("iPhone 17 Air", "iPhone 17 Air", "A3515", "A3515", "Apple", "Apple", "Apple"),
        // 2024.09 旗舰系列 (国行 A3297 / A3294)
        DeviceProfile("iPhone 16 Pro Max", "iPhone 16 Pro Max", "A3297", "A3297", "Apple", "Apple", "Apple"),
        DeviceProfile("iPhone 16 Pro", "iPhone 16 Pro", "A3294", "A3294", "Apple", "Apple", "Apple"),
        // 2023.09/10 钛金属旗舰 (国行 A3106 / A3102)
        DeviceProfile("iPhone 15 Pro Max", "iPhone 15 Pro Max", "A3106", "A3106", "Apple", "Apple", "Apple"),
        DeviceProfile("iPhone 15 Pro", "iPhone 15 Pro", "A3102", "A3102", "Apple", "Apple", "Apple"),
    )

    private val ONEPLUS_DEVICES: List<DeviceProfile> = listOf(
        // 2026.10 最新发布机皇 185Hz 东方屏
        DeviceProfile("OnePlus 16", "PYB110", "pokemon", "PYB110", "OnePlus", "OnePlus", "OnePlus"),
        // 2025/2026 数字旗舰
        DeviceProfile("OnePlus 15", "PLK110", "OP60FFL1", "PLK110", "OnePlus", "OnePlus", "OnePlus"),
        DeviceProfile("OnePlus 15T", "PLZ110", "fairlady", "PLZ110", "OnePlus", "OnePlus", "OnePlus"),
        // 2024/2025 数字旗舰
        DeviceProfile("OnePlus 13", "PJZ110", "OP5D0DL1", "PJZ110", "OnePlus", "OnePlus", "OnePlus"),
        DeviceProfile("OnePlus 13T", "PKX110", "pagani", "PKX110", "OnePlus", "OnePlus", "OnePlus"),
        // 2023.12 十周年旗舰
        DeviceProfile("OnePlus 12", "PJD110", "waffle", "PJD110", "OnePlus", "OnePlus", "OnePlus"),
        // 折叠屏旗舰系列
        DeviceProfile("OnePlus Open", "CPH2551", "OP594DL1", "CPH2551", "OnePlus", "OnePlus", "OnePlus"),
        // Ace 性能旗舰
        DeviceProfile("OnePlus Ace 6 Ultra", "PMB110", "roadster", "PMB110", "OnePlus", "OnePlus", "OnePlus"),
        DeviceProfile("OnePlus Ace 6", "PLQ110", "ktm", "PLQ110", "OnePlus", "OnePlus", "OnePlus"),
        DeviceProfile("OnePlus Ace 5 Ultra", "PLC110", "emira", "PLC110", "OnePlus", "OnePlus", "OnePlus"),
        DeviceProfile("OnePlus Ace 5 Pro", "PKR110", "hummer", "PKR110", "OnePlus", "OnePlus", "OnePlus"),
        DeviceProfile("OnePlus Ace 5", "PKG110", "giulia", "PKG110", "OnePlus", "OnePlus", "OnePlus"),
        DeviceProfile("OnePlus Ace 3 Pro", "PJX110", "corvette", "PJX110", "OnePlus", "OnePlus", "OnePlus"),
    )

    private val XIAOMI_DEVICES: List<DeviceProfile> = listOf(
        // 2026 旗舰系列
        DeviceProfile("Xiaomi 18 Pro Max", "M154FF", "madrid", "madrid", "Xiaomi", "Xiaomi", "Xiaomi"),
        DeviceProfile("Xiaomi 18 Pro", "M610BB", "hongkong", "hongkong", "Xiaomi", "Xiaomi", "Xiaomi"),
        DeviceProfile("Xiaomi 18 Fold", "2608BPX34C", "lhasa", "lhasa", "Xiaomi", "Xiaomi", "Xiaomi"),
        // 2025/2026 旗舰系列
        DeviceProfile("Xiaomi 17 Ultra", "2512BPNDAC", "nezha", "nezha", "Xiaomi", "Xiaomi", "Xiaomi"),
        DeviceProfile("Xiaomi 17 Ultra by Leica", "25128PNA1C", "nezha", "nezha", "Xiaomi", "Xiaomi", "Xiaomi"),
        DeviceProfile("Xiaomi 17 Pro Max", "2509FPN0BC", "popsicle", "popsicle", "Xiaomi", "Xiaomi", "Xiaomi"),
        DeviceProfile("Xiaomi 17 Pro", "25098PN5AC", "pandora", "pandora", "Xiaomi", "Xiaomi", "Xiaomi"),
        DeviceProfile("Xiaomi 17 Max", "2605EPN8EC", "byron", "byron", "Xiaomi", "Xiaomi", "Xiaomi"),
        DeviceProfile("Xiaomi 17", "25113PN0EC", "pudding", "pudding", "Xiaomi", "Xiaomi", "Xiaomi"),
        // 2024/2025 旗舰系列
        DeviceProfile("Xiaomi 15 Ultra", "25019PNF3C", "xuanyuan", "xuanyuan", "Xiaomi", "Xiaomi", "Xiaomi"),
        DeviceProfile("Xiaomi 15S Pro", "25042PN24C", "dijun", "dijun", "Xiaomi", "Xiaomi", "Xiaomi"),
        DeviceProfile("Xiaomi 15 Pro", "2410DPN6CC", "haotian", "haotian", "Xiaomi", "Xiaomi", "Xiaomi"),
        DeviceProfile("Xiaomi 15", "24129PN74C", "dada", "dada", "Xiaomi", "Xiaomi", "Xiaomi"),
        // 2023/2024 旗舰系列
        DeviceProfile("Xiaomi 14 Ultra", "24031PN0DC", "aurora", "aurora", "Xiaomi", "Xiaomi", "Xiaomi"),
        DeviceProfile("Xiaomi 14 Pro", "23116PN5BC", "shennong", "shennong", "Xiaomi", "Xiaomi", "Xiaomi"),
        DeviceProfile("Xiaomi 14", "23127PN0CC", "houji", "houji", "Xiaomi", "Xiaomi", "Xiaomi"),
        // MIX 折叠旗舰系列
        DeviceProfile("Xiaomi MIX Fold 4", "24072PX77C", "goku", "goku", "Xiaomi", "Xiaomi", "Xiaomi"),
        DeviceProfile("Xiaomi MIX Flip 2", "2505APX7BC", "bixi", "bixi", "Xiaomi", "Xiaomi", "Xiaomi"),
        DeviceProfile("Xiaomi MIX Flip", "2405CPX3DC", "ruyi", "ruyi", "Xiaomi", "Xiaomi", "Xiaomi"),
        // Redmi 旗舰系列 (K系列旗舰)
        DeviceProfile("REDMI K100 Pro Max", "M098FE", "songyuan", "songyuan", "Xiaomi", "Redmi", "Xiaomi"),
        DeviceProfile("REDMI K100 Pro", "M511CD", "athens", "athens", "Xiaomi", "Redmi", "Xiaomi"),
        DeviceProfile("REDMI K90 至尊版", "M332BF", "warsaw", "warsaw", "Xiaomi", "Redmi", "Xiaomi"),
        DeviceProfile("REDMI K90 Pro Max", "25102RKBEC", "myron", "myron", "Xiaomi", "Redmi", "Xiaomi"),
        DeviceProfile("REDMI K90", "2510DRK44C", "annibale", "annibale", "Xiaomi", "Redmi", "Xiaomi"),
        DeviceProfile("Redmi K80 至尊版", "25060RK16C", "dali", "dali", "Xiaomi", "Redmi", "Xiaomi"),
        DeviceProfile("Redmi K80 Pro", "24122RKC7C", "miro", "miro", "Xiaomi", "Redmi", "Xiaomi"),
        DeviceProfile("Redmi K80", "24117RK2CC", "zorn", "zorn", "Xiaomi", "Redmi", "Xiaomi"),
        DeviceProfile("Redmi K70 至尊版", "2407FRK8EC", "rothko", "rothko", "Xiaomi", "Redmi", "Xiaomi"),
        DeviceProfile("Redmi K70 Pro", "23117RK66C", "manet", "manet", "Xiaomi", "Redmi", "Xiaomi"),
    )

    private val SAMSUNG_DEVICES: List<DeviceProfile> = listOf(
        // ==== Galaxy S26 系列 (2026) ====
        DeviceProfile("Galaxy S26 Ultra", "SM-S9480", "a9e", "a9ezc", "Samsung"),
        DeviceProfile("Galaxy S26+", "SM-S9460", "a9p", "a9pzc", "Samsung"),
        DeviceProfile("Galaxy S26", "SM-S9410", "a9", "a9zc", "Samsung"),

        // ==== Galaxy S25 系列 (2025) ====
        DeviceProfile("Galaxy S25 Ultra", "SM-S9380", "e3", "e3zc", "Samsung"),
        DeviceProfile("Galaxy S25+", "SM-S9360", "e2", "e2zc", "Samsung"),
        DeviceProfile("Galaxy S25", "SM-S9310", "e1", "e1zc", "Samsung"),
        DeviceProfile("Galaxy S25 Edge", "SM-S9370", "pa1q", "pa1qzc", "Samsung"),

        // ==== Galaxy S24 系列 (2024.01) ====
        DeviceProfile("Galaxy S24 Ultra", "SM-S9280", "e3q", "e3qzc", "Samsung"),
        DeviceProfile("Galaxy S24+", "SM-S9260", "e2q", "e2qzc", "Samsung"),
        DeviceProfile("Galaxy S24", "SM-S9210", "e1q", "e1qzc", "Samsung"),

        // ==== Galaxy Z Fold & W 系列 ====
        DeviceProfile("Galaxy Z Fold8", "SM-F9760", "q8", "q8zc", "Samsung"),
        DeviceProfile("Galaxy Z Fold8 Ultra", "SM-F9780", "q8x", "q8xzc", "Samsung"),
        DeviceProfile("Galaxy Z Fold7", "SM-F9660", "q7", "q7zc", "Samsung"),
        DeviceProfile("Galaxy Z Fold Special Edition", "SM-F958N", "q6a", "q6akz", "Samsung"),
        DeviceProfile("Galaxy Z Fold6", "SM-F9560", "q6", "q6zc", "Samsung"),
        DeviceProfile("Galaxy W26", "SM-W9026", "w26", "w26zc", "Samsung"),
        DeviceProfile("Galaxy W25", "SM-W9025", "w25", "w25zc", "Samsung"),

        // ==== Galaxy Z Flip 系列 ====
        DeviceProfile("Galaxy Z Flip8", "SM-F7760", "b8r", "b8rzc", "Samsung"),
        DeviceProfile("Galaxy Z Flip7", "SM-F7660", "b7r", "b7rzc", "Samsung"),
        DeviceProfile("Galaxy Z Flip6", "SM-F7410", "b6q", "b6qzc", "Samsung"),
        DeviceProfile("Galaxy W26 Flip", "SM-W7026", "w26f", "w26fzc", "Samsung"),
        DeviceProfile("Galaxy W25 Flip", "SM-W7025", "w25f", "w25fzc", "Samsung"),

        // ==== Galaxy Tab 旗舰平板系列 ====
        DeviceProfile("Galaxy Tab S11 Ultra", "SM-X9360", "gts11u", "gts11uzc", "Samsung"),
        DeviceProfile("Galaxy Tab S11+", "SM-X8360", "gts11p", "gts11pzc", "Samsung"),
        DeviceProfile("Galaxy Tab S10 Ultra", "SM-X9260", "gts10u", "gts10uzc", "Samsung"),
        DeviceProfile("Galaxy Tab S10+", "SM-X8260", "gts10p", "gts10pzc", "Samsung"),
    )

    private val VIVO_DEVICES: List<DeviceProfile> = listOf(
        // ==== vivo X 系列影像旗舰 ====
        DeviceProfile("vivo X500 Pro Max", "V2602DA", "PD2602", "PD2602", "vivo", "vivo", "vivo"),
        DeviceProfile("vivo X500 Pro", "V2608DA", "PD2608", "PD2608", "vivo", "vivo", "vivo"),
        DeviceProfile("vivo X500", "V2609A", "PD2609", "PD2609", "vivo", "vivo", "vivo"),
        DeviceProfile("vivo X300 Ultra", "V2547DA", "PD2547", "PD2547", "vivo", "vivo", "vivo"),
        DeviceProfile("vivo X300s", "V2548A", "PD2548", "PD2548", "vivo", "vivo", "vivo"),
        DeviceProfile("vivo X300 Pro", "V2502DA", "PD2502", "PD2502", "vivo", "vivo", "vivo"),
        DeviceProfile("vivo X300", "V2509A", "PD2509", "PD2509", "vivo", "vivo", "vivo"),
        DeviceProfile("vivo X200 Ultra", "V2454DA", "PD2454", "PD2454", "vivo", "vivo", "vivo"),
        DeviceProfile("vivo X200s", "V2458A", "PD2458", "PD2458", "vivo", "vivo", "vivo"),
        DeviceProfile("vivo X200 Pro", "V2405DA", "PD2405", "PD2405", "vivo", "vivo", "vivo"),
        DeviceProfile("vivo X200 Pro mini", "V2419A", "PD2419", "PD2419", "vivo", "vivo", "vivo"),
        DeviceProfile("vivo X200", "V2415A", "PD2415", "PD2415", "vivo", "vivo", "vivo"),
        DeviceProfile("vivo X100 Ultra", "V2366HA", "PD2366", "PD2366", "vivo", "vivo", "vivo"),
        DeviceProfile("vivo X100s Pro", "V2324HA", "PD2324", "PD2324", "vivo", "vivo", "vivo"),
        DeviceProfile("vivo X100 Pro", "V2324A", "PD2324", "PD2324", "vivo", "vivo", "vivo"),

        // ==== vivo X Fold 折叠旗舰 ====
        DeviceProfile("vivo X Fold6", "V2545A", "PD2545", "PD2545", "vivo", "vivo", "vivo"),
        DeviceProfile("vivo X Fold5", "V2436A", "PD2436", "PD2436", "vivo", "vivo", "vivo"),
        DeviceProfile("vivo X Fold3 Pro", "V2337A", "PD2337", "PD2337", "vivo", "vivo", "vivo"),
        DeviceProfile("vivo X Fold3", "V2303A", "PD2303", "PD2303", "vivo", "vivo", "vivo"),

        // ==== iQOO 旗舰系列 ====
        DeviceProfile("iQOO 16", "V2606A", "PD2606", "PD2606", "vivo", "iQOO", "vivo"),
        DeviceProfile("iQOO 15 Ultra", "V2546A", "PD2546", "PD2546", "vivo", "iQOO", "vivo"),
        DeviceProfile("iQOO 15", "V2505A", "PD2505", "PD2505", "vivo", "iQOO", "vivo"),
        DeviceProfile("iQOO 13", "V2408A", "PD2408", "PD2408", "vivo", "iQOO", "vivo"),
        DeviceProfile("iQOO 12 Pro", "V2329A", "PD2329", "PD2329", "vivo", "iQOO", "vivo"),
        DeviceProfile("iQOO 12", "V2307A", "PD2307", "PD2307", "vivo", "iQOO", "vivo"),
        DeviceProfile("iQOO Neo 11 Ultra", "V2573A", "PD2573", "PD2573", "vivo", "iQOO", "vivo"),
        DeviceProfile("iQOO Neo 11", "V2520A", "PD2520", "PD2520", "vivo", "iQOO", "vivo"),
        DeviceProfile("iQOO Neo 10 Pro+", "V2463A", "PD2463", "PD2463", "vivo", "iQOO", "vivo"),
        DeviceProfile("iQOO Neo 10 Pro", "V2426A", "PD2426", "PD2426", "vivo", "iQOO", "vivo"),
    )

    private val OPPO_DEVICES: List<DeviceProfile> = listOf(
        // ==== OPPO Find X 旗舰系列 ====
        DeviceProfile("OPPO Find X10 Pro Max", "PMX110", "mars", "PMX110", "OPPO", "OPPO", "OPPO"),
        DeviceProfile("OPPO Find X10", "PMW110", "uranus", "PMW110", "OPPO", "OPPO", "OPPO"),
        DeviceProfile("OPPO Find X9 Ultra", "PMA120", "lighthouse", "PMA120", "OPPO", "OPPO", "OPPO"),
        DeviceProfile("OPPO Find X9s Pro", "PME110", "venus", "PME110", "OPPO", "OPPO", "OPPO"),
        DeviceProfile("OPPO Find X9 Pro", "PLG120", "changjiang", "PLG120", "OPPO", "OPPO", "OPPO"),
        DeviceProfile("OPPO Find X9", "PLJ110", "zhujiang", "PLJ110", "OPPO", "OPPO", "OPPO"),
        DeviceProfile("OPPO Find X8 Ultra", "PKJ110", "PKJ110", "PKJ110", "OPPO", "OPPO", "OPPO"),
        DeviceProfile("OPPO Find X8s+", "PLB110", "slmh", "PLB110", "OPPO", "OPPO", "OPPO"),
        DeviceProfile("OPPO Find X8s", "PKT110", "koktokay", "PKT110", "OPPO", "OPPO", "OPPO"),
        DeviceProfile("OPPO Find X8 Pro", "PKC130", "konka", "PKC130", "OPPO", "OPPO", "OPPO"),
        DeviceProfile("OPPO Find X8", "PKB110", "yala", "PKB110", "OPPO", "OPPO", "OPPO"),
        DeviceProfile("OPPO Find X7 Ultra", "PHY120", "pangu", "PHY120", "OPPO", "OPPO", "OPPO"),

        // ==== OPPO Find N 折叠旗舰 ====
        DeviceProfile("OPPO Find N6", "PLP120", "swangoose", "PLP120", "OPPO", "OPPO", "OPPO"),
        DeviceProfile("OPPO Find N5", "PKH120", "petrel", "PKH120", "OPPO", "OPPO", "OPPO"),
        DeviceProfile("OPPO Find N3", "PHN110", "xueying", "PHN110", "OPPO", "OPPO", "OPPO"),

        // ==== realme 性能旗舰 ====
        DeviceProfile("realme GT 8 Pro", "RMX5210", "RE611CL1", "RMX5210", "OPPO", "realme", "realme"),
        DeviceProfile("realme GT7 Pro", "RMX5010", "RE601CL1", "RMX5010", "OPPO", "realme", "realme"),
        DeviceProfile("realme GT5 Pro", "RMX3888", "RE5C4FL1", "RMX3888", "OPPO", "realme", "realme"),
    )

    private val HUAWEI_HONOR_DEVICES: List<DeviceProfile> = listOf(
        // ==== HUAWEI 顶级旗舰系列 ====
        DeviceProfile("HUAWEI Mate XT 2 非凡大师", "LAP-AL10", "Laptua", "LAP-AL10", "HUAWEI", "HUAWEI", "HUAWEI"),
        DeviceProfile("HUAWEI Mate XTs 非凡大师", "GRL-AL20", "Greenlan", "GRL-AL20", "HUAWEI", "HUAWEI", "HUAWEI"),
        DeviceProfile("HUAWEI Mate XT 非凡大师", "GRL-AL10", "Greenlan", "GRL-AL10", "HUAWEI", "HUAWEI", "HUAWEI"),
        DeviceProfile("HUAWEI Mate 90 RS 非凡大师", "CMU-AL10", "CosmosU", "CMU-AL10", "HUAWEI", "HUAWEI", "HUAWEI"),
        DeviceProfile("HUAWEI Mate 90 Pro Max", "CMM-AL10", "CosmosM", "CMM-AL10", "HUAWEI", "HUAWEI", "HUAWEI"),
        DeviceProfile("HUAWEI Mate 90 Pro", "CMS-AL20", "Cosmos", "CMS-AL20", "HUAWEI", "HUAWEI", "HUAWEI"),
        DeviceProfile("HUAWEI Mate 80 RS 非凡大师", "SGU-AL10", "SagittariusU", "SGU-AL10", "HUAWEI", "HUAWEI", "HUAWEI"),
        DeviceProfile("HUAWEI Mate 80 Pro Max", "SGT-AL10", "Sagittarius", "SGT-AL10", "HUAWEI", "HUAWEI", "HUAWEI"),
        DeviceProfile("HUAWEI Mate 80 Pro", "SGT-AL00", "Sagittarius", "SGT-AL00", "HUAWEI", "HUAWEI", "HUAWEI"),
        DeviceProfile("HUAWEI Mate 80", "VYG-AL30", "Voyager", "VYG-AL30", "HUAWEI", "HUAWEI", "HUAWEI"),
        DeviceProfile("HUAWEI Mate 70 RS 非凡大师", "PLU-AL10", "PillarU", "PLU-AL10", "HUAWEI", "HUAWEI", "HUAWEI"),
        DeviceProfile("HUAWEI Mate 70 Pro+", "PLA-AL10", "Pillar+", "PLA-AL10", "HUAWEI", "HUAWEI", "HUAWEI"),
        DeviceProfile("HUAWEI Mate 70 Pro", "PLR-AL30", "Pillar", "PLR-AL30", "HUAWEI", "HUAWEI", "HUAWEI"),
        DeviceProfile("HUAWEI Mate 70", "CLS-AL30", "Celestial", "CLS-AL30", "HUAWEI", "HUAWEI", "HUAWEI"),
        DeviceProfile("HUAWEI Mate 60 Pro+", "ALN-AL10", "Allen", "ALN-AL10", "HUAWEI", "HUAWEI", "HUAWEI"),
        DeviceProfile("HUAWEI Mate 60 Pro", "ALN-AL80", "Allen", "ALN-AL80", "HUAWEI", "HUAWEI", "HUAWEI"),
        DeviceProfile("HUAWEI Mate X7", "DEL-AL20", "Delphi", "DEL-AL20", "HUAWEI", "HUAWEI", "HUAWEI"),
        DeviceProfile("HUAWEI Mate X6", "ICL-AL20", "Icelan", "ICL-AL20", "HUAWEI", "HUAWEI", "HUAWEI"),
        DeviceProfile("HUAWEI Mate X5", "ALT-AL10", "Alta", "ALT-AL10", "HUAWEI", "HUAWEI", "HUAWEI"),
        DeviceProfile("HUAWEI Pura 90 Pro Max", "SCA-AL00", "Sicilia", "SCA-AL00", "HUAWEI", "HUAWEI", "HUAWEI"),
        DeviceProfile("HUAWEI Pura 90 Pro", "MLN-AL00", "Malena", "MLN-AL00", "HUAWEI", "HUAWEI", "HUAWEI"),
        DeviceProfile("HUAWEI Pura 80 Ultra", "LMU-AL00", "LamarrU", "LMU-AL00", "HUAWEI", "HUAWEI", "HUAWEI"),
        DeviceProfile("HUAWEI Pura 80 Pro+", "LMR-AL10", "Lamarr", "LMR-AL10", "HUAWEI", "HUAWEI", "HUAWEI"),
        DeviceProfile("HUAWEI Pura 80 Pro", "LMR-AL00", "Lamarr", "LMR-AL00", "HUAWEI", "HUAWEI", "HUAWEI"),
        DeviceProfile("HUAWEI Pura 70 Ultra", "HBP-AL00", "HWHBP", "HBP-AL00", "HUAWEI", "HUAWEI", "HUAWEI"),
        DeviceProfile("HUAWEI Pura 70 Pro+", "HBN-AL80", "HWHBN", "HBN-AL80", "HUAWEI", "HUAWEI", "HUAWEI"),
        DeviceProfile("HUAWEI Pura 70 Pro", "HBN-AL00", "HWHBN", "HBN-AL00", "HUAWEI", "HUAWEI", "HUAWEI"),
        DeviceProfile("HUAWEI Pura X", "VDE-AL10", "Verde", "VDE-AL10", "HUAWEI", "HUAWEI", "HUAWEI"),
        DeviceProfile("HUAWEI Pocket 2", "LEM-AL00", "Lem", "LEM-AL00", "HUAWEI", "HUAWEI", "HUAWEI"),

        // ==== Honor 荣耀旗舰系列 ====
        DeviceProfile("Honor Magic9 Pro Max", "WKL-AN80", "Twinkle", "WKL-AN80", "Honor", "HONOR", "HONOR"),
        DeviceProfile("Honor Magic9", "MLY-AN00", "Molly", "MLY-AN00", "Honor", "HONOR", "HONOR"),
        DeviceProfile("Honor Magic8 RSR 保时捷设计", "BKQ-AN20", "Kurt Geiger", "BKQ-AN20", "Honor", "HONOR", "HONOR"),
        DeviceProfile("Honor Magic8 Pro", "BKQ-AN90", "Beryl", "BKQ-AN90", "Honor", "HONOR", "HONOR"),
        DeviceProfile("Honor Magic8", "BKQ-AN80", "Bacchus", "BKQ-AN80", "Honor", "HONOR", "HONOR"),
        DeviceProfile("Honor Magic7 RSR 保时捷设计", "PTP-AN20", "Patek", "PTP-AN20", "Honor", "HONOR", "HONOR"),
        DeviceProfile("Honor Magic7 Pro", "PTP-AN70", "Patek", "PTP-AN70", "Honor", "HONOR", "HONOR"),
        DeviceProfile("Honor Magic7", "PTP-AN60", "Philippe", "PTP-AN60", "Honor", "HONOR", "HONOR"),
        DeviceProfile("Honor Magic6 RSR 保时捷设计", "BVL-AN20", "Bvlgari", "BVL-AN20", "Honor", "HONOR", "HONOR"),
        DeviceProfile("Honor Magic6 Pro", "BVL-AN16", "Bvlgari", "BVL-AN16", "Honor", "HONOR", "HONOR"),
        DeviceProfile("Honor Magic V6", "PNM-AN20", "Phenom", "PNM-AN20", "Honor", "HONOR", "HONOR"),
        DeviceProfile("Honor Magic V5", "MBH-AN10", "Maybach", "MBH-AN10", "Honor", "HONOR", "HONOR"),
        DeviceProfile("Honor Magic V3", "FCP-AN20", "FalconP", "FCP-AN20", "Honor", "HONOR", "HONOR"),
        DeviceProfile("Honor Magic V Flip2", "CLE-AN00", "Celine", "CLE-AN00", "Honor", "HONOR", "HONOR"),
    )

    private val GOOGLE_DEVICES: List<DeviceProfile> = listOf(
        // 2026 旗舰系列
        DeviceProfile("Pixel 11 Pro Fold", "Pixel 11 Pro Fold", "rango2", "rango2", "Google", "Google", "Google"),
        DeviceProfile("Pixel 11 Pro XL", "Pixel 11 Pro XL", "mustang2", "mustang2", "Google", "Google", "Google"),
        DeviceProfile("Pixel 11 Pro", "Pixel 11 Pro", "blazer2", "blazer2", "Google", "Google", "Google"),
        // 2025 旗舰系列
        DeviceProfile("Pixel 10 Pro Fold", "Pixel 10 Pro Fold", "rango", "rango", "Google", "Google", "Google"),
        DeviceProfile("Pixel 10 Pro XL", "Pixel 10 Pro XL", "frankel", "frankel", "Google", "Google", "Google"),
        DeviceProfile("Pixel 10 Pro", "Pixel 10 Pro", "blazer", "blazer", "Google", "Google", "Google"),
        DeviceProfile("Pixel 10", "Pixel 10", "mustang", "mustang", "Google", "Google", "Google"),
        // 2024 旗舰系列
        DeviceProfile("Pixel 9 Pro Fold", "Pixel 9 Pro Fold", "comet", "comet", "Google", "Google", "Google"),
        DeviceProfile("Pixel 9 Pro XL", "Pixel 9 Pro XL", "komodo", "komodo", "Google", "Google", "Google"),
        DeviceProfile("Pixel 9 Pro", "Pixel 9 Pro", "caiman", "caiman", "Google", "Google", "Google"),
        DeviceProfile("Pixel 9", "Pixel 9", "tokay", "tokay", "Google", "Google", "Google"),
        // 2023 旗舰系列（三年内）
        DeviceProfile("Pixel Fold", "Pixel Fold", "felix", "felix", "Google", "Google", "Google"),
        DeviceProfile("Pixel 8 Pro", "Pixel 8 Pro", "husky", "husky", "Google", "Google", "Google"),
    )

    private val GAMING_OTHER_DEVICES: List<DeviceProfile> = listOf(
        // ==== 红魔电竞旗舰 ====
        DeviceProfile("RedMagic 11 Pro+", "NX809J", "REDMAGIC 11 PRO", "NX809J", "Gaming", "REDMAGIC", "Nubia"),
        DeviceProfile("RedMagic 10 Pro+", "NX789J", "NX789J", "NX789J", "Gaming", "nubia", "nubia"),
        DeviceProfile("RedMagic 10 Pro", "NX779J", "NX779J", "NX779J", "Gaming", "nubia", "nubia"),
        DeviceProfile("RedMagic 9S Pro+", "NX769J", "NX769J", "NX769J", "Gaming", "nubia", "nubia"),
        DeviceProfile("RedMagic 9 Pro", "NX769J", "NX769J", "NX769J", "Gaming", "nubia", "nubia"),

        // ==== ROG 游戏手机旗舰 ====
        DeviceProfile("ROG Phone 9 Pro", "ASUS_AI2501", "AI2501", "ASUS_AI2501", "Gaming", "asus", "asus"),
        DeviceProfile("ROG Phone 8 Pro", "ASUS_AI2401", "AI2401", "ASUS_AI2401", "Gaming", "asus", "asus"),

        // ==== 魅族数字旗舰 ====
        DeviceProfile("MEIZU 22 PRO", "MEIZU 22 Pro", "meizu22Pro", "meizu_22Pro_CN", "MEIZU", "meizu", "meizu"),
        DeviceProfile("MEIZU 22", "MEIZU 22", "meizu22", "meizu_22_CN", "MEIZU", "meizu", "meizu"),
        DeviceProfile("MEIZU 21 PRO", "MEIZU 21 PRO", "meizu21Pro", "meizu_21Pro_CN", "MEIZU", "meizu", "meizu"),
        DeviceProfile("MEIZU 21", "MEIZU 21", "meizu21", "meizu_21_CN", "MEIZU", "meizu", "meizu"),

        // ==== 索尼微单影像旗舰 ====
        DeviceProfile("Sony Xperia 1 VIII", "XQ-GE74", "XQ-GE74", "XQ-GE74", "Sony", "Sony", "Sony"),
        DeviceProfile("Sony Xperia 1 VII", "XQ-FS72", "pdx256", "XQ-FS72", "Sony", "Sony", "Sony"),
        DeviceProfile("Sony Xperia 1 VI", "XQ-EC72", "pdx245", "XQ-EC72", "Sony", "Sony", "Sony"),
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
        APPLE_DEVICES +
            ONEPLUS_DEVICES +
            XIAOMI_DEVICES +
            SAMSUNG_DEVICES +
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
        val map = HashMap<String, DeviceProfile>(ALL.size * 5)
        for (item in ALL) {
            map.putIfAbsent(item.name.lowercase(), item)
            map.putIfAbsent(item.model.lowercase(), item)
            map.putIfAbsent(item.displayName.lowercase(), item)
            map.putIfAbsent(item.device.lowercase(), item)
            map.putIfAbsent(item.productName.lowercase(), item)
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
        var res = s.trim().lowercase()
        val prefixes = listOf(
            "samsung ", "galaxy ", "xiaomi ", "redmi ", "vivo ", "iqoo ",
            "huawei ", "honor ", "apple ", "iphone ", "google ", "pixel ",
            "oppo ", "oneplus ", "realme ", "meizu ", "sony ", "xperia ",
            "nubia ", "redmagic ", "asus ", "rog ",
        )
        var changed = true
        while (changed) {
            changed = false
            for (prefix in prefixes) {
                if (res.startsWith(prefix)) {
                    res = res.removePrefix(prefix).trimStart()
                    changed = true
                }
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
            lower.contains("huawei") || lower.contains("mate") || lower.contains("pura") -> "HUAWEI"
            lower.contains("honor") || lower.contains("magic") -> "Honor"
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
            "honor" -> "HONOR"
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
