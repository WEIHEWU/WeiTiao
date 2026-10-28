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
        get() = when {
            isFold -> "fold,nosdcard"
            isFlip -> "flip,nosdcard"
            isTablet -> "tablet,nosdcard"
            else -> "nosdcard"
        }

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
                    n.contains("80") -> "Kirin 9030"
                    n.contains("xt") || n.contains("x6") || (n.contains("70") && !n.contains("pura 70")) -> "Kirin 9020"
                    n.contains("pura 70") -> "Kirin 9010"
                    else -> "Kirin 9000S"
                }
                "google" -> when {
                    n.contains("10") -> "Tensor G5"
                    n.contains("9") -> "Tensor G4"
                    n.contains("8") -> "Tensor G3"
                    else -> "Tensor G2"
                }
                else -> when {
                    // 联发科天玑旗舰 (Dimensity 9500 / 9400 / 9300+)
                    (n.contains("x300") && !n.contains("ultra")) ||
                        (n.contains("find x9") && !n.contains("ultra")) ||
                        n.contains("k90 至尊") ||
                        n.contains("neo11 pro") -> "MT6993"
                    (n.contains("x200") && !n.contains("ultra")) ||
                        (n.contains("find x8") && !n.contains("ultra")) ||
                        n.contains("k80 至尊") ||
                        n.contains("neo10 pro") ||
                        n.contains("tab s11") -> "MT6991"
                    n.contains("x100 pro") ||
                        n.contains("k70 至尊") ||
                        n.contains("tab s10") -> "MT6989"
                    // 高通骁龙 8 Elite Gen 2 / Gen 5 (SM8850)
                    n.contains("xiaomi 17") ||
                        n.contains("mix fold 5") ||
                        n.contains("k90") ||
                        n.contains("oneplus 16") ||
                        n.contains("oneplus 15") ||
                        n.contains("s26") ||
                        n.contains("fold8") ||
                        n.contains("flip8") ||
                        n.contains("w26") ||
                        n.contains("x300 ultra") ||
                        n.contains("iqoo 15") ||
                        n.contains("find x9 ultra") ||
                        n.contains("gt 8") ||
                        n.contains("magic8") ||
                        n.contains("redmagic 11") ||
                        n.contains("rog phone 10") ||
                        n.contains("xperia 1 vii") -> "SM8850"
                    // 高通骁龙 8 Elite (SM8750-AB)
                    n.contains("xiaomi 15") ||
                        n.contains("mix flip 2") ||
                        n.contains("k80 pro") ||
                        n.contains("oneplus 13") ||
                        n.contains("ace 5 pro") ||
                        n.contains("open 2") ||
                        n.contains("s25") ||
                        n.contains("fold7") ||
                        n.contains("flip7") ||
                        n.contains("x200 ultra") ||
                        n.contains("x fold4") ||
                        n.contains("iqoo 13") ||
                        n.contains("find x8 ultra") ||
                        n.contains("find n5") ||
                        n.contains("gt7 pro") ||
                        n.contains("gt 7 pro") ||
                        n.contains("magic7") ||
                        n.contains("magic v4") ||
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
        // 2026.09 首款双屏折叠旗舰
        DeviceProfile("iPhone Duo", "iPhone Duo", "A3600", "A3600", "Apple", "Apple", "Apple"),
        // 2026.09 旗舰系列
        DeviceProfile("iPhone 18 Pro Max", "iPhone 18 Pro Max", "A3627", "A3627", "Apple", "Apple", "Apple"),
        DeviceProfile("iPhone 18 Pro", "iPhone 18 Pro", "A3624", "A3624", "Apple", "Apple", "Apple"),
        // 2025.09 旗舰系列
        DeviceProfile("iPhone 17 Pro Max", "iPhone 17 Pro Max", "A3527", "A3527", "Apple", "Apple", "Apple"),
        DeviceProfile("iPhone 17 Pro", "iPhone 17 Pro", "A3524", "A3524", "Apple", "Apple", "Apple"),
        DeviceProfile("iPhone 17 Air", "iPhone 17 Air", "A3515", "A3515", "Apple", "Apple", "Apple"),
        // 2024.09 旗舰系列
        DeviceProfile("iPhone 16 Pro Max", "iPhone 16 Pro Max", "A3297", "A3297", "Apple", "Apple", "Apple"),
        DeviceProfile("iPhone 16 Pro", "iPhone 16 Pro", "A3294", "A3294", "Apple", "Apple", "Apple"),
        // 2023.09/10 钛金属旗舰
        DeviceProfile("iPhone 15 Pro Max", "iPhone 15 Pro Max", "A3106", "A3106", "Apple", "Apple", "Apple"),
        DeviceProfile("iPhone 15 Pro", "iPhone 15 Pro", "A3102", "A3102", "Apple", "Apple", "Apple"),
    )

    private val ONEPLUS_DEVICES: List<DeviceProfile> = listOf(
        // 2026.10.12 最新发布机皇 185Hz 东方屏
        DeviceProfile("OnePlus 16", "PJZ130", "PJZ130", "PJZ130", "OnePlus", "OnePlus", "OnePlus"),
        // 2025.10 数字旗舰
        DeviceProfile("OnePlus 15 Pro", "PJZ120", "PJZ120", "PJZ120", "OnePlus", "OnePlus", "OnePlus"),
        DeviceProfile("OnePlus 15", "PLK110", "PLK110", "PLK110", "OnePlus", "OnePlus", "OnePlus"),
        // 2024.10 数字旗舰
        DeviceProfile("OnePlus 13", "PJZ110", "PJZ110", "PJZ110", "OnePlus", "OnePlus", "OnePlus"),
        // 2023.12 十周年旗舰
        DeviceProfile("OnePlus 12", "PJD110", "PJD110", "PJD110", "OnePlus", "OnePlus", "OnePlus"),
        // 折叠屏旗舰系列
        DeviceProfile("OnePlus Open 2", "CPH2651", "CPH2651", "CPH2651", "OnePlus", "OnePlus", "OnePlus"),
        DeviceProfile("OnePlus Open", "CPH2551", "CPH2551", "CPH2551", "OnePlus", "OnePlus", "OnePlus"),
        // Ace 性能旗舰
        DeviceProfile("OnePlus Ace 5 Pro", "PKR110", "PKR110", "PKR110", "OnePlus", "OnePlus", "OnePlus"),
        DeviceProfile("OnePlus Ace 5", "PKK110", "PKK110", "PKK110", "OnePlus", "OnePlus", "OnePlus"),
        DeviceProfile("OnePlus Ace 3 Pro", "PJX110", "PJX110", "PJX110", "OnePlus", "OnePlus", "OnePlus"),
    )

    private val XIAOMI_DEVICES: List<DeviceProfile> = listOf(
        // 2025/2026 旗舰系列
        DeviceProfile("Xiaomi 17 Ultra", "26020PN00C", "tianji", "tianji", "Xiaomi", "Xiaomi", "Xiaomi"),
        DeviceProfile("Xiaomi 17 Pro Max", "25090PN00C", "chiyou", "chiyou", "Xiaomi", "Xiaomi", "Xiaomi"),
        DeviceProfile("Xiaomi 17 Pro", "25080PN00C", "fuxi", "fuxi", "Xiaomi", "Xiaomi", "Xiaomi"),
        DeviceProfile("Xiaomi 17", "25070PN00C", "dada2", "dada2", "Xiaomi", "Xiaomi", "Xiaomi"),
        // 2024/2025 旗舰系列
        DeviceProfile("Xiaomi 15 Ultra", "25010PN30C", "xuanyuan", "xuanyuan", "Xiaomi", "Xiaomi", "Xiaomi"),
        DeviceProfile("Xiaomi 15 Pro", "2410DPN6CC", "haotian", "haotian", "Xiaomi", "Xiaomi", "Xiaomi"),
        DeviceProfile("Xiaomi 15", "24129PN74C", "dada", "dada", "Xiaomi", "Xiaomi", "Xiaomi"),
        // 2023/2024 旗舰系列
        DeviceProfile("Xiaomi 14 Ultra", "24030PN60C", "aurora", "aurora", "Xiaomi", "Xiaomi", "Xiaomi"),
        DeviceProfile("Xiaomi 14 Pro", "23116PN5BC", "shennong", "shennong", "Xiaomi", "Xiaomi", "Xiaomi"),
        DeviceProfile("Xiaomi 14", "23127PN0CC", "houji", "houji", "Xiaomi", "Xiaomi", "Xiaomi"),
        // MIX 折叠旗舰系列
        DeviceProfile("Xiaomi MIX Fold 5", "25072PX00C", "goku2", "goku2", "Xiaomi", "Xiaomi", "Xiaomi"),
        DeviceProfile("Xiaomi MIX Fold 4", "24072PX77C", "goku", "goku", "Xiaomi", "Xiaomi", "Xiaomi"),
        DeviceProfile("Xiaomi MIX Flip 2", "2505CPX00C", "ruyi2", "ruyi2", "Xiaomi", "Xiaomi", "Xiaomi"),
        DeviceProfile("Xiaomi MIX Flip", "2405CPX3DG", "ruyi", "ruyi", "Xiaomi", "Xiaomi", "Xiaomi"),
        // Redmi 旗舰系列 (K系列旗舰)
        DeviceProfile("Redmi K80 至尊版", "2507FRK00C", "dali", "dali", "Xiaomi", "Redmi", "Xiaomi"),
        DeviceProfile("Redmi K80 Pro", "24117RK2CC", "miro", "miro", "Xiaomi", "Redmi", "Xiaomi"),
        DeviceProfile("Redmi K80", "24122RKC7C", "zorn", "zorn", "Xiaomi", "Redmi", "Xiaomi"),
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
        DeviceProfile("Galaxy S25 Slim", "SM-S9370", "e2s", "e2szc", "Samsung"),

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
        DeviceProfile("Galaxy Tab S10 Ultra", "SM-X9260", "gts10u", "gts10uzc", "Samsung"),
        DeviceProfile("Galaxy Tab S10+", "SM-X8260", "gts10p", "gts10pzc", "Samsung"),
    )

    private val VIVO_DEVICES: List<DeviceProfile> = listOf(
        // ==== vivo X 系列影像旗舰 ====
        DeviceProfile("vivo X300 Ultra", "V2544A", "V2544A", "V2544A", "vivo", "vivo", "vivo"),
        DeviceProfile("vivo X300 Pro", "V2515A", "V2515A", "V2515A", "vivo", "vivo", "vivo"),
        DeviceProfile("vivo X300", "V2513A", "V2513A", "V2513A", "vivo", "vivo", "vivo"),
        DeviceProfile("vivo X200 Ultra", "V2444A", "V2444A", "V2444A", "vivo", "vivo", "vivo"),
        DeviceProfile("vivo X200 Pro", "V2415A", "V2415A", "V2415A", "vivo", "vivo", "vivo"),
        DeviceProfile("vivo X200 Pro mini", "V2405A", "V2405A", "V2405A", "vivo", "vivo", "vivo"),
        DeviceProfile("vivo X200", "V2413A", "V2413A", "V2413A", "vivo", "vivo", "vivo"),
        DeviceProfile("vivo X100 Ultra", "V2366A", "V2366A", "V2366A", "vivo", "vivo", "vivo"),
        DeviceProfile("vivo X100 Pro", "V2324A", "V2324A", "V2324A", "vivo", "vivo", "vivo"),

        // ==== vivo X Fold 折叠旗舰 ====
        DeviceProfile("vivo X Fold4 Pro", "V2437A", "V2437A", "V2437A", "vivo", "vivo", "vivo"),
        DeviceProfile("vivo X Fold3 Pro", "V2337A", "V2337A", "V2337A", "vivo", "vivo", "vivo"),
        DeviceProfile("vivo X Fold3", "V2303A", "V2303A", "V2303A", "vivo", "vivo", "vivo"),

        // ==== iQOO 旗舰系列 ====
        DeviceProfile("iQOO 15 Pro", "V2529A", "V2529A", "V2529A", "vivo", "iQOO", "vivo"),
        DeviceProfile("iQOO 15", "V2508A", "V2508A", "V2508A", "vivo", "iQOO", "vivo"),
        DeviceProfile("iQOO 13", "V2408A", "V2408A", "V2408A", "vivo", "iQOO", "vivo"),
        DeviceProfile("iQOO 12 Pro", "V2329A", "V2329A", "V2329A", "vivo", "iQOO", "vivo"),
        DeviceProfile("iQOO 12", "V2307A", "V2307A", "V2307A", "vivo", "iQOO", "vivo"),
        DeviceProfile("iQOO Neo11 Pro", "V2526A", "V2526A", "V2526A", "vivo", "iQOO", "vivo"),
        DeviceProfile("iQOO Neo10 Pro", "V2426A", "V2426A", "V2426A", "vivo", "iQOO", "vivo"),
    )

    private val OPPO_DEVICES: List<DeviceProfile> = listOf(
        // ==== OPPO Find X 旗舰系列 ====
        DeviceProfile("OPPO Find X9 Ultra", "PKG120", "PKG120", "PKG120", "OPPO", "OPPO", "OPPO"),
        DeviceProfile("OPPO Find X9 Pro", "PKC120", "PKC120", "PKC120", "OPPO", "OPPO", "OPPO"),
        DeviceProfile("OPPO Find X9", "PKB120", "PKB120", "PKB120", "OPPO", "OPPO", "OPPO"),
        DeviceProfile("OPPO Find X8 Ultra", "PKG110", "PKG110", "PKG110", "OPPO", "OPPO", "OPPO"),
        DeviceProfile("OPPO Find X8 Pro", "PKC110", "PKC110", "PKC110", "OPPO", "OPPO", "OPPO"),
        DeviceProfile("OPPO Find X8", "PKB110", "PKB110", "PKB110", "OPPO", "OPPO", "OPPO"),
        DeviceProfile("OPPO Find X7 Ultra", "PHY110", "PHY110", "PHY110", "OPPO", "OPPO", "OPPO"),

        // ==== OPPO Find N 折叠旗舰 ====
        DeviceProfile("OPPO Find N5", "PHY120", "PHY120", "PHY120", "OPPO", "OPPO", "OPPO"),
        DeviceProfile("OPPO Find N3", "PHN110", "PHN110", "PHN110", "OPPO", "OPPO", "OPPO"),

        // ==== realme 性能旗舰 ====
        DeviceProfile("realme GT7 Pro", "RMX5010", "RMX5010", "RMX5010", "OPPO", "realme", "OPPO"),
        DeviceProfile("realme GT5 Pro", "RMX3888", "RMX3888", "RMX3888", "OPPO", "realme", "OPPO"),
    )

    private val HUAWEI_HONOR_DEVICES: List<DeviceProfile> = listOf(
        // ==== HUAWEI 顶级旗舰系列 ====
        DeviceProfile("HUAWEI Mate XT 非凡大师", "GND-AL00", "GND-AL00", "GND-AL00", "HUAWEI", "HUAWEI", "HUAWEI"),
        DeviceProfile("HUAWEI Mate 80 RS 非凡大师", "ADY-AL20", "ADY-AL20", "ADY-AL20", "HUAWEI", "HUAWEI", "HUAWEI"),
        DeviceProfile("HUAWEI Mate 80 Pro+", "ADY-AL30", "ADY-AL30", "ADY-AL30", "HUAWEI", "HUAWEI", "HUAWEI"),
        DeviceProfile("HUAWEI Mate 80 Pro", "HBN-AL20", "HBN-AL20", "HBN-AL20", "HUAWEI", "HUAWEI", "HUAWEI"),
        DeviceProfile("HUAWEI Mate 70 RS 非凡大师", "ADY-AL10", "ADY-AL10", "ADY-AL10", "HUAWEI", "HUAWEI", "HUAWEI"),
        DeviceProfile("HUAWEI Mate 70 Pro+", "ADY-AL00", "ADY-AL00", "ADY-AL00", "HUAWEI", "HUAWEI", "HUAWEI"),
        DeviceProfile("HUAWEI Mate 70 Pro", "HBN-AL00", "HBN-AL00", "HBN-AL00", "HUAWEI", "HUAWEI", "HUAWEI"),
        DeviceProfile("HUAWEI Mate 70", "HBN-AL10", "HBN-AL10", "HBN-AL10", "HUAWEI", "HUAWEI", "HUAWEI"),
        DeviceProfile("HUAWEI Mate 60 Pro+", "ALN-AL10", "ALN-AL10", "ALN-AL10", "HUAWEI", "HUAWEI", "HUAWEI"),
        DeviceProfile("HUAWEI Mate 60 Pro", "ALN-AL00", "ALN-AL00", "ALN-AL00", "HUAWEI", "HUAWEI", "HUAWEI"),
        DeviceProfile("HUAWEI Mate X6", "ALT-AL20", "ALT-AL20", "ALT-AL20", "HUAWEI", "HUAWEI", "HUAWEI"),
        DeviceProfile("HUAWEI Mate X5", "ALT-AL10", "ALT-AL10", "ALT-AL10", "HUAWEI", "HUAWEI", "HUAWEI"),
        DeviceProfile("HUAWEI Pura 80 Ultra", "HBP-AL10", "HBP-AL10", "HBP-AL10", "HUAWEI", "HUAWEI", "HUAWEI"),
        DeviceProfile("HUAWEI Pura 80 Pro+", "HNE-AL10", "HNE-AL10", "HNE-AL10", "HUAWEI", "HUAWEI", "HUAWEI"),
        DeviceProfile("HUAWEI Pura 70 Ultra", "HBP-AL00", "HBP-AL00", "HBP-AL00", "HUAWEI", "HUAWEI", "HUAWEI"),
        DeviceProfile("HUAWEI Pura 70 Pro+", "HBN-AL80", "HBN-AL80", "HBN-AL80", "HUAWEI", "HUAWEI", "HUAWEI"),
        DeviceProfile("HUAWEI Pura 70 Pro", "HNE-AL00", "HNE-AL00", "HNE-AL00", "HUAWEI", "HUAWEI", "HUAWEI"),
        DeviceProfile("HUAWEI Pocket 2", "LEM-AL00", "LEM-AL00", "LEM-AL00", "HUAWEI", "HUAWEI", "HUAWEI"),

        // ==== Honor 荣耀旗舰系列 ====
        DeviceProfile("Honor Magic8 RSR 保时捷设计", "PTP-AN30", "PTP-AN30", "PTP-AN30", "Honor", "HONOR", "HONOR"),
        DeviceProfile("Honor Magic8 Pro", "PTP-AN20", "PTP-AN20", "PTP-AN20", "Honor", "HONOR", "HONOR"),
        DeviceProfile("Honor Magic7 RSR 保时捷设计", "PTP-AN26", "PTP-AN26", "PTP-AN26", "Honor", "HONOR", "HONOR"),
        DeviceProfile("Honor Magic7 Pro", "PTP-AN10", "PTP-AN10", "PTP-AN10", "Honor", "HONOR", "HONOR"),
        DeviceProfile("Honor Magic7", "PTP-AN00", "PTP-AN00", "PTP-AN00", "Honor", "HONOR", "HONOR"),
        DeviceProfile("Honor Magic6 RSR 保时捷设计", "BVL-AN20", "BVL-AN20", "BVL-AN20", "Honor", "HONOR", "HONOR"),
        DeviceProfile("Honor Magic6 Pro", "BVL-AN16", "BVL-AN16", "BVL-AN16", "Honor", "HONOR", "HONOR"),
        DeviceProfile("Honor Magic V4", "FCP-AN20", "FCP-AN20", "FCP-AN20", "Honor", "HONOR", "HONOR"),
        DeviceProfile("Honor Magic V3", "FCP-AN10", "FCP-AN10", "FCP-AN10", "Honor", "HONOR", "HONOR"),
    )

    private val GOOGLE_DEVICES: List<DeviceProfile> = listOf(
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
        DeviceProfile("RedMagic 11 Pro+", "NX799J", "NX799J", "NX799J", "Gaming", "nubia", "nubia"),
        DeviceProfile("RedMagic 10 Pro+", "NX789J", "NX789J", "NX789J", "Gaming", "nubia", "nubia"),
        DeviceProfile("RedMagic 10 Pro", "NX779J", "NX779J", "NX779J", "Gaming", "nubia", "nubia"),
        DeviceProfile("RedMagic 9S Pro+", "NX769J-S", "NX769J-S", "NX769J-S", "Gaming", "nubia", "nubia"),
        DeviceProfile("RedMagic 9 Pro", "NX769J", "NX769J", "NX769J", "Gaming", "nubia", "nubia"),

        // ==== ROG 游戏手机旗舰 ====
        DeviceProfile("ROG Phone 10 Pro", "ASUS_AI2601", "ASUS_AI2601", "ASUS_AI2601", "Gaming", "asus", "asus"),
        DeviceProfile("ROG Phone 9 Pro", "ASUS_AI2501", "ASUS_AI2501", "ASUS_AI2501", "Gaming", "asus", "asus"),
        DeviceProfile("ROG Phone 8 Pro", "ASUS_AI2401", "ASUS_AI2401", "ASUS_AI2401", "Gaming", "asus", "asus"),

        // ==== 魅族数字旗舰 ====
        DeviceProfile("MEIZU 21 PRO", "MEIZU 21 PRO", "meizu21Pro", "meizu21Pro", "MEIZU", "meizu", "meizu"),
        DeviceProfile("MEIZU 21", "MEIZU 21", "meizu21", "meizu21", "MEIZU", "meizu", "meizu"),

        // ==== 索尼微单影像旗舰 ====
        DeviceProfile("Sony Xperia 1 VII", "XQ-FC72", "XQ-FC72", "XQ-FC72", "Sony", "Sony", "Sony"),
        DeviceProfile("Sony Xperia 1 VI", "XQ-EC72", "XQ-EC72", "XQ-EC72", "Sony", "Sony", "Sony"),
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
