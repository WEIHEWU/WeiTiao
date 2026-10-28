package com.weitiao.tuning.core

import android.os.Build

/**
 * 核心欺骗逻辑：根据配置计算某个属性 key 应返回的值。
 * Hook 端在 beforeHookedMethod 中调用 [matchProp] 决定是否覆盖返回值。
 */
object CscSpoof {

    /**
     * 匹配系统属性：
     * @return 需要覆盖的新值；返回 null 表示不干预（保持原始值）。
     */
    fun matchProp(key: String, config: CscConfig): String? {
        config.customProps[key]?.let { return it }
        if (!config.enabled) return null
        val kind = CscConstants.SPOOFED_PROPS[key] ?: return null
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
     * 与商店跨区开关 (enabled) 解耦，只要开启了 spoofSemCscFeature 即可生效。
     * @param key CscFeature_* 键名
     */
    fun matchCscFeature(key: String, config: CscConfig): String? {
        if (!config.spoofSemCscFeature) return null
        return config.customCscFeatures[key]
    }

    /**
     * 解析指定包名对应的目标机型名称（优先取分应用映射 perAppDeviceModels，未配置则回退全局 deviceName）。
     */
    fun resolveDeviceModelName(config: CscConfig, packageName: String? = null): String {
        if (!packageName.isNullOrBlank()) {
            val perApp = config.perAppDeviceModels[packageName]
            if (!perApp.isNullOrBlank()) return perApp
        }
        return config.deviceName
    }

    /**
     * 判断指定包名是否应当启用机型伪装。
     */
    fun isDeviceSpoofTarget(config: CscConfig, packageName: String): Boolean {
        if (!config.spoofDevice) return false
        if (!config.perAppDeviceModels[packageName].isNullOrBlank()) return true
        if (config.deviceName.isBlank()) return false
        return !config.deviceTargetFilterEnabled ||
            config.deviceTargetPackages.isEmpty() ||
            packageName in config.deviceTargetPackages
    }

    /**
     * 匹配机型伪装相关系统属性（Build.MODEL/DEVICE/PRODUCT 等）。
     * @return 需要覆盖的新值；返回 null 表示不干预。
     */
    fun matchDeviceProp(key: String, config: CscConfig, packageName: String? = null): String? {
        if (!config.spoofDevice) return null
        val profile = deviceProfileFor(config, packageName) ?: return null
        return matchDevicePropForProfile(key, profile)
    }

    /**
     * 生成与 Bionic inline prop_info (<= 91 UTF-8 bytes) 兼容的设备指纹，
     * 保证 Build.FINGERPRINT、SystemProperties 与 NativePropCow 三端完全一致。
     */
    fun buildFingerprintForProfile(profile: DeviceProfile): String {
        val osRelease = runCatching { Build.VERSION.RELEASE }.getOrNull()?.takeIf { it.isNotBlank() } ?: "16"
        val buildId = runCatching { Build.ID }.getOrNull()?.takeIf { it.isNotBlank() } ?: "UP1A.231005.007"
        val full = "${profile.brand}/${profile.productName}/${profile.device}:$osRelease/$buildId/${profile.model}:user/release-keys"
        if (full.toByteArray(Charsets.UTF_8).size <= 91) return full
        val compact = "${profile.brand}/${profile.productName}/${profile.device}:$osRelease/$buildId/1:user/release-keys"
        return if (compact.toByteArray(Charsets.UTF_8).size <= 91) compact else compact.take(91)
    }

    private val MODEL_KEYS = listOf(
        "ro.product.model",
        "ro.product.bootimage.model",
        "ro.product.vendor.model",
        "ro.product.odm.model",
        "ro.product.product.model",
        "ro.product.system.model",
        "ro.product.system_ext.model",
        "ro.product.model_for_attestation",
        "ro.product.vendor.model_for_attestation",
        "ro.product.odm.model_for_attestation",
    )

    private val DEVICE_KEYS = listOf(
        "ro.product.device",
        "ro.build.product",
        "ro.product.bootimage.device",
        "ro.product.odm.device",
        "ro.product.vendor.device",
        "ro.product.product.device",
        "ro.product.system.device",
        "ro.product.system_ext.device",
        "ro.product.device_for_attestation",
        "ro.product.board",
        "ro.hardware",
        "ro.board.platform",
    )

    private val SOC_MODEL_KEYS = listOf(
        "ro.soc.model",
        "ro.vendor.qti.soc_name",
        "ro.vendor.qti.soc_model",
        "ro.hardware.chipname",
    )

    private val PRODUCT_NAME_KEYS = listOf(
        "ro.product.name",
        "ro.product.bootimage.name",
        "ro.product.odm.name",
        "ro.product.vendor.name",
        "ro.product.product.name",
        "ro.product.system.name",
        "ro.product.system_ext.name",
        "ro.product.name_for_attestation",
    )

    private val MARKET_NAME_KEYS = listOf(
        // 小米 / Redmi / HyperOS
        "ro.product.marketname",
        "ro.product.bootimage.marketname",
        "ro.product.odm.marketname",
        "ro.product.vendor.marketname",
        "ro.product.system.marketname",
        "ro.product.product.marketname",
        "ro.product.system_ext.marketname",
        // OPPO / 一加 / 真我 (ColorOS / OPlus)
        "ro.vendor.oplus.market.name",
        "ro.oplus.market.name",
        "ro.oppo.market.name",
        "ro.realme.market.name",
        "persist.sys.oplus.market.name",
        "persist.sys.oppo.devicename",
        "ro.common.soft",
        // vivo / iQOO (OriginOS)
        "ro.vivo.market.name",
        "ro.vivo.product.model",
        "ro.vivo.oem.name",
        "ro.product.customize.bbk",
        // 华为 / 荣耀 (HarmonyOS / MagicOS)
        "ro.config.marketing_name",
        "ro.config.devicename",
        "msc.config.marketing_name",
        "hw_sc.product.marketing_name",
        // 魅族 / 红魔 / ROG / 通用展示名
        "ro.meizu.product.model",
        "ro.product.flyme.model",
        "ro.nubia.market.name",
        "ro.asus.product.mkt_name",
        "ro.product.display",
        "ro.config.device_name",
        "persist.sys.device_name",
        "bluetooth.device.default_name",
    )

    private val BRAND_KEYS = listOf(
        "ro.product.brand",
        "ro.product.bootimage.brand",
        "ro.product.odm.brand",
        "ro.product.vendor.brand",
        "ro.product.product.brand",
        "ro.product.system.brand",
        "ro.product.system_ext.brand",
        "ro.product.brand_for_attestation",
    )

    private val MANUFACTURER_KEYS = listOf(
        "ro.product.manufacturer",
        "ro.product.bootimage.manufacturer",
        "ro.product.odm.manufacturer",
        "ro.product.vendor.manufacturer",
        "ro.product.product.manufacturer",
        "ro.product.system.manufacturer",
        "ro.product.system_ext.manufacturer",
        "ro.product.manufacturer_for_attestation",
    )

    private val FINGERPRINT_KEYS = listOf(
        "ro.build.fingerprint",
        "ro.bootimage.build.fingerprint",
        "ro.vendor.build.fingerprint",
        "ro.odm.build.fingerprint",
        "ro.system.build.fingerprint",
        "ro.system_ext.build.fingerprint",
        "ro.product.build.fingerprint",
    )

    /**
     * 为给定 [DeviceProfile] 构建全量系统属性覆盖映射表（供 Hook 查表与 NativePropCow 写入共用）。
     */
    fun buildDevicePropsForProfile(profile: DeviceProfile): Map<String, String> {
        val devMap = HashMap<String, String>(96)
        val fingerprint = buildFingerprintForProfile(profile)
        val buildId = runCatching { Build.ID }.getOrNull()?.takeIf { it.isNotBlank() } ?: "UP1A.231005.007"

        for (k in MODEL_KEYS) devMap[k] = profile.model
        for (k in DEVICE_KEYS) devMap[k] = profile.device
        for (k in SOC_MODEL_KEYS) devMap[k] = profile.socModel
        for (k in PRODUCT_NAME_KEYS) devMap[k] = profile.productName
        for (k in MARKET_NAME_KEYS) devMap[k] = profile.name
        for (k in BRAND_KEYS) devMap[k] = profile.brand
        for (k in MANUFACTURER_KEYS) devMap[k] = profile.manufacturer
        for (k in FINGERPRINT_KEYS) devMap[k] = fingerprint

        devMap["ro.build.display.id"] = "${profile.model}_$buildId"
        devMap["ro.build.flavor"] = "${profile.productName}-user"
        devMap["ro.build.characteristics"] = profile.characteristics
        devMap["ro.soc.manufacturer"] = profile.socManufacturer
        devMap["ro.miui.cust_device"] = profile.device
        return devMap
    }

    /**
     * 为给定 [DeviceProfile] 构建 Samsung SemFloatingFeature 覆盖映射表。
     */
    fun buildFloatingFeaturesForProfile(profile: DeviceProfile): Map<String, String> {
        val floatMap = HashMap<String, String>(8)
        floatMap["SEC_FLOATING_FEATURE_FRAMEWORK_SUPPORT_FOLDABLE_TYPE_FOLD"] = if (profile.isFold) "TRUE" else "FALSE"
        floatMap["SEC_FLOATING_FEATURE_FRAMEWORK_SUPPORT_FOLDABLE_TYPE_FLIP"] = if (profile.isFlip) "TRUE" else "FALSE"
        floatMap["SEC_FLOATING_FEATURE_FRAMEWORK_CONFIG_FOLD_MODEL"] = if (profile.isFold || profile.isFlip) "TRUE" else "FALSE"
        floatMap["SEC_FLOATING_FEATURE_SETTINGS_CONFIG_BRAND_NAME"] = profile.name
        floatMap["SEC_FLOATING_FEATURE_COMMON_CONFIG_BRAND_NAME"] = profile.name
        return floatMap
    }

    /**
     * 基于给定 [DeviceProfile] 计算单个系统属性的伪装值。
     */
    fun matchDevicePropForProfile(key: String, profile: DeviceProfile): String? {
        return buildDevicePropsForProfile(profile)[key]
    }

    /**
     * 匹配 Samsung SemFloatingFeature 读取（折叠屏形态、机型品牌特性等）。
     */
    fun matchFloatingFeature(key: String, config: CscConfig, packageName: String? = null): String? {
        if (!config.spoofDevice) return null
        val profile = deviceProfileFor(config, packageName) ?: return null
        return matchFloatingFeatureForProfile(key, profile)
    }

    fun matchFloatingFeatureForProfile(key: String, profile: DeviceProfile): String? {
        return buildFloatingFeaturesForProfile(profile)[key]
    }

    /**
     * 匹配 Build 类静态字段读取（android.os.Build.MODEL 等，部分 App 直接读字段）。
     * 由于 Build 字段是编译期常量，这里仅做标记，实际 hook 在 MainHook 中处理。
     */
    fun deviceProfileFor(config: CscConfig, packageName: String? = null): DeviceProfile? {
        if (!config.spoofDevice) return null
        if (!packageName.isNullOrBlank() && !isDeviceSpoofTarget(config, packageName)) return null
        val modelName = resolveDeviceModelName(config, packageName)
        if (modelName.isBlank()) return null
        return DeviceModels.findByName(modelName)
    }
}
