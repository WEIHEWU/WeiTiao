package com.weitiao.tuning.core

import android.os.Build

/**
 * 核心欺骗逻辑：根据配置计算某个属性 key 应返回的值。
 * Hook 端在 beforeHookedMethod 中调用 [match] 决定是否覆盖返回值。
 */
object CscSpoof {

    /**
     * 匹配系统属性：
     * @return 需要覆盖的新值；返回 null 表示不干预（保持原始值）。
     */
    fun matchProp(key: String, config: CscConfig): String? {
        if (!config.enabled) return null
        val kind = CscConstants.SPOOFED_PROPS[key] ?: return config.customProps[key]
        return when (kind) {
            CscConstants.PropKind.SALES_CODE -> {
                if (config.spoofSalesCode) config.salesCode.takeIf { it.isNotBlank() } else null
            }
            CscConstants.PropKind.COUNTRY_CODE -> {
                if (config.spoofCountryCode) {
                    // 用户显式填了国家码就用它，否则尝试从销售代码推导
                    (config.countryCode.takeIf { it.isNotBlank() }
                        ?: CscConstants.countryCodeOf(config.salesCode))?.takeIf { it.isNotBlank() }
                } else null
            }
            CscConstants.PropKind.SIM_NUMERIC -> {
                if (config.spoofSimInfo) CscSim.resolveNumeric(config).takeIf { it.isNotBlank() } else null
            }
            CscConstants.PropKind.SIM_ISO -> {
                if (config.spoofSimInfo) CscSim.resolveCountryIso(config)?.takeIf { it.isNotBlank() } else null
            }
            CscConstants.PropKind.SIM_OPERATOR -> {
                if (config.spoofSimInfo) CscSim.resolveOperatorName(config).takeIf { it.isNotBlank() } else null
            }
        }
    }

    /**
     * 匹配 ril.sales_code：仅当销售代码欺骗开启时使用。
     */
    fun matchRilSalesCode(config: CscConfig): String? {
        if (!config.enabled || !config.spoofRilSalesCode) return null
        return config.salesCode.takeIf { it.isNotBlank() }
    }

    /**
     * 匹配 CscFeature 读取：
     * 先看用户自定义覆盖表，若配置了该 key 则返回覆盖值。
     * @param key CscFeature_* 键名
     */
    fun matchCscFeature(key: String, config: CscConfig): String? {
        if (!config.enabled || !config.spoofSemCscFeature) return null
        return config.customCscFeatures[key]
    }

    /**
     * 匹配机型伪装相关系统属性（Build.MODEL/DEVICE/PRODUCT 等）。
     * @return 需要覆盖的新值；返回 null 表示不干预。
     */
    fun matchDeviceProp(key: String, config: CscConfig): String? {
        if (!config.spoofDevice) return null
        val profile = DeviceModels.findByName(config.deviceName) ?: return null
        return when (key) {
            "ro.product.model", "ro.product.vendor.model", "ro.product.odm.model",
            "ro.product.product.model", "ro.product.system.model", "ro.product.system_ext.model" -> profile.model

            "ro.product.device", "ro.build.product", "ro.product.odm.device",
            "ro.product.vendor.device", "ro.product.product.device", "ro.product.system.device",
            "ro.product.system_ext.device", "ro.product.board", "ro.hardware", "ro.board.platform" -> profile.device

            "ro.product.name", "ro.product.odm.name", "ro.product.vendor.name",
            "ro.product.product.name", "ro.product.system.name", "ro.product.system_ext.name" -> profile.productName

            "ro.product.marketname", "ro.vendor.oplus.market.name", "ro.product.display" -> profile.name

            "ro.product.brand", "ro.product.odm.brand", "ro.product.vendor.brand",
            "ro.product.product.brand", "ro.product.system.brand", "ro.product.system_ext.brand" -> profile.brand

            "ro.product.manufacturer", "ro.product.odm.manufacturer", "ro.product.vendor.manufacturer",
            "ro.product.product.manufacturer", "ro.product.system.manufacturer", "ro.product.system_ext.manufacturer" -> profile.manufacturer

            "ro.build.flavor" -> "${profile.productName}-user"

            "ro.build.fingerprint", "ro.bootimage.build.fingerprint", "ro.vendor.build.fingerprint",
            "ro.odm.build.fingerprint", "ro.system.build.fingerprint", "ro.system_ext.build.fingerprint" -> {
                val osRelease = Build.VERSION.RELEASE.ifBlank { "17" }
                val buildId = Build.ID.ifBlank { "UP1A.231005.007" }
                "${profile.brand}/${profile.productName}/${profile.device}:$osRelease/$buildId/${profile.model}:user/release-keys"
            }

            "ro.soc.model", "ro.vendor.qti.soc_name" -> profile.device

            "ro.build.characteristics" -> {
                val isFold = profile.name.contains("Fold", ignoreCase = true) || profile.device.startsWith("q") || profile.model.contains("F9")
                val isFlip = profile.name.contains("Flip", ignoreCase = true) || profile.device.startsWith("b") || profile.model.contains("F7")
                val isTablet = profile.name.contains("Tab", ignoreCase = true) || profile.device.startsWith("gts") || profile.model.contains("X")
                when {
                    isFold -> "fold,nosdcard"
                    isFlip -> "flip,nosdcard"
                    isTablet -> "tablet,nosdcard"
                    else -> "nosdcard"
                }
            }

            else -> null
        }
    }

    /**
     * 匹配 Samsung SemFloatingFeature 读取（折叠屏形态、机型品牌特性等）。
     */
    fun matchFloatingFeature(key: String, config: CscConfig): String? {
        if (!config.spoofDevice) return null
        val profile = deviceProfileFor(config) ?: return null
        val isFold = profile.name.contains("Fold", ignoreCase = true) || profile.device.startsWith("q") || profile.model.contains("F9")
        val isFlip = profile.name.contains("Flip", ignoreCase = true) || profile.device.startsWith("b") || profile.model.contains("F7")
        return when (key) {
            "SEC_FLOATING_FEATURE_FRAMEWORK_SUPPORT_FOLDABLE_TYPE_FOLD" -> if (isFold) "TRUE" else null
            "SEC_FLOATING_FEATURE_FRAMEWORK_SUPPORT_FOLDABLE_TYPE_FLIP" -> if (isFlip) "TRUE" else null
            "SEC_FLOATING_FEATURE_FRAMEWORK_CONFIG_FOLD_MODEL" -> if (isFold || isFlip) "TRUE" else null
            "SEC_FLOATING_FEATURE_SETTINGS_CONFIG_BRAND_NAME" -> profile.name
            "SEC_FLOATING_FEATURE_COMMON_CONFIG_BRAND_NAME" -> profile.name
            else -> null
        }
    }

    /**
     * 匹配 Build 类静态字段读取（android.os.Build.MODEL 等，部分 App 直接读字段）。
     * 由于 Build 字段是编译期常量，这里仅做标记，实际 hook 在 MainHook 中处理。
     */
    fun deviceProfileFor(config: CscConfig): DeviceProfile? {
        if (!config.spoofDevice) return null
        return DeviceModels.findByName(config.deviceName)
    }
}
