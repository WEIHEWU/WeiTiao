package com.weitiao.tuning

import android.content.SharedPreferences
import android.os.Build
import android.os.SystemClock
import android.util.Log
import com.weitiao.tuning.core.CscConfig
import com.weitiao.tuning.core.CscConstants
import com.weitiao.tuning.core.CscSim
import com.weitiao.tuning.core.CscSpoof
import com.weitiao.tuning.core.DeviceModels
import com.weitiao.tuning.core.DeviceProfile
import com.weitiao.tuning.core.NativePropCow
import com.weitiao.tuning.core.TuningConfig
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam

/**
 * libxposed 现代 API（102）模块入口。
 *
 * 入口声明：`resources/META-INF/xposed/java_init.list`；
 * 作用域：`resources/META-INF/xposed/scope.list` + LSPosed 管理器配置。
 *
 * 关键改进：
 * 1. 启动时始终挂载所有基础 Hook，在拦截器内部动态评估 ConfigHolder 配置。
 *    支持在 UI 中开关/切换参数后 2 秒内热生效，无需频繁强杀应用。
 * 2. 补全 SubscriptionManager 与 SubscriptionInfo 拦截，防止现代应用通过多卡槽 API 绕过 SIM 伪装。
 * 3. 补全 SystemProperties 与 SemSystemProperties 的 get/getInt/getBoolean/getLong 全量重载。
 * 4. 完善 Build 静态常量伪装（含 FINGERPRINT / DISPLAY / ID / SOC_MODEL 等）。
 */
class MainHook : XposedModule() {

    override fun onPackageReady(param: PackageReadyParam) {
        // 排除模块自身进程：UI 进程读取属性要展示"原生真实信息"，不能被自己 hook 拦截
        if (param.packageName == CscConstants.MODULE_PACKAGE) return

        // 绝不能在系统底层框架、系统界面、电话服务、设置应用中挂载任何拦截，确保真实界面调度与基带网络 100% 不受干扰
        if (param.packageName == "android" ||
            param.packageName == "com.android.systemui" ||
            param.packageName == "com.android.phone" ||
            param.packageName == "com.android.settings" ||
            param.packageName == "com.samsung.android.app.settings" ||
            param.packageName == "com.samsung.android.app.telephonyui" ||
            param.packageName.startsWith("com.android.server")
        ) {
            return
        }

        // 微信功能挂载
        if (param.packageName == WECHAT_PACKAGE) {
            WechatHooks(
                install = { executable, interceptor ->
                    hook(executable).intercept { chain -> interceptor(chain) }
                },
                config = { TuningConfigHolder.current(prefs) },
                logger = { message, error ->
                    log(
                        if (error == null) Log.INFO else Log.WARN,
                        TAG,
                        if (error == null) "WeiTiao: $message" else "WeiTiao: $message: $error",
                    )
                },
            ).register(param.classLoader, param.packageName)
        }

        val config = ConfigHolder.current(prefs)
        val isStoreApp = param.packageName in STORE_PACKAGES
        val isDeviceSpoofTarget = isDeviceTarget(config, param.packageName)

        // 若既非应用商店/三星账号判区组件，也未被开启机型伪装，则直接跳过，不在无关进程挂载属性拦截
        if (!isStoreApp && !isDeviceSpoofTarget && param.packageName == WECHAT_PACKAGE) {
            return
        }

        // 挂载基础系统属性与CSC特性拦截器
        hookSystemProperties(param.classLoader, param.packageName)
        hookSemSystemProperties(param.classLoader, param.packageName)

        // Telephony 与 SubscriptionInfo 拦截仅对目标应用商店与三星账号判区服务生效，绝不污染系统底层与其它应用
        if (isStoreApp) {
            hookTelephony(param.classLoader)
            hookSubscriptionManager(param.classLoader)
        }

        hookSemCscFeature(param.classLoader)
        hookSemFloatingFeature(param.classLoader, param.packageName)
        hookSettingsProvider(param.classLoader, param.packageName)

        // 尝试应用 Build 类静态常量与 Native COW 内存属性伪装（与商店跨区开关独立，支持分应用独立机型）
        if (isDeviceSpoofTarget) {
            applyBuildFields(config, param.packageName)
            runCatching {
                val fastProps = ConfigHolder.currentResolved(prefs).devicePropsFor(param.packageName)
                val patchedCount = NativePropCow.applyCowSpoof(fastProps)
                log(Log.INFO, TAG, "WeiTiao: ${param.packageName} 已应用 Native COW 内存补丁，改写 $patchedCount 处底层系统属性")
            }.onFailure { err ->
                log(Log.ERROR, TAG, "WeiTiao: NativePropCow error in ${param.packageName}: $err\n${Log.getStackTraceString(err)}")
            }
        }

        log(
            Log.INFO, TAG,
            "CscChanger: hooked in ${param.packageName} " +
                "enabled=${config.enabled} salesCode=${config.salesCode} countryCode=${config.countryCode} " +
                "spoofDevice=${config.spoofDevice} deviceName=${config.deviceName}",
        )
    }

    private val prefs: SharedPreferences by lazy { getRemotePreferences(CscConstants.PREFS_NAME) }

    // ---- TelephonyManager（SIM 欺骗，影响按 SIM 判区的应用） ----

    private fun hookTelephony(cl: ClassLoader) {
        runCatching {
            val tm = Class.forName("android.telephony.TelephonyManager", false, cl)
            // getSimOperator() -> MCC+MNC（如 46001）
            safeHook(tm, "getSimOperator") { chain -> simOperatorIntercept(chain) }
            safeHook(tm, "getSimOperator", Int::class.javaPrimitiveType!!) { chain -> simOperatorIntercept(chain) }
            safeHook(tm, "getSimOperatorNumeric") { chain -> simOperatorIntercept(chain) }
            safeHook(tm, "getSimOperatorNumeric", Int::class.javaPrimitiveType!!) { chain -> simOperatorIntercept(chain) }
            safeHook(tm, "getNetworkOperator") { chain -> simOperatorIntercept(chain) }
            safeHook(tm, "getNetworkOperator", Int::class.javaPrimitiveType!!) { chain -> simOperatorIntercept(chain) }
            safeHook(tm, "getNetworkOperatorNumeric") { chain -> simOperatorIntercept(chain) }
            safeHook(tm, "getNetworkOperatorNumeric", Int::class.javaPrimitiveType!!) { chain -> simOperatorIntercept(chain) }

            // getSimCountryIso() -> cn/hk/tw/us
            safeHook(tm, "getSimCountryIso") { chain -> simIsoIntercept(chain) }
            safeHook(tm, "getSimCountryIso", Int::class.javaPrimitiveType!!) { chain -> simIsoIntercept(chain) }
            safeHook(tm, "getNetworkCountryIso") { chain -> simIsoIntercept(chain) }
            safeHook(tm, "getNetworkCountryIso", Int::class.javaPrimitiveType!!) { chain -> simIsoIntercept(chain) }

            // getSimOperatorName / getNetworkOperatorName
            safeHook(tm, "getSimOperatorName") { chain -> simOperatorNameIntercept(chain) }
            safeHook(tm, "getSimOperatorName", Int::class.javaPrimitiveType!!) { chain -> simOperatorNameIntercept(chain) }
            safeHook(tm, "getNetworkOperatorName") { chain -> simOperatorNameIntercept(chain) }
            safeHook(tm, "getNetworkOperatorName", Int::class.javaPrimitiveType!!) { chain -> simOperatorNameIntercept(chain) }
        }.onFailure {
            log(Log.WARN, TAG, "CscChanger: TelephonyManager hook failed: $it")
        }
    }

    private fun simOperatorIntercept(chain: XposedInterface.Chain): Any? {
        val cfg = ConfigHolder.current(prefs)
        if (!cfg.enabled || !cfg.spoofSimInfo) return chain.proceed()
        return CscSim.resolveNumeric(cfg)
    }

    private fun simIsoIntercept(chain: XposedInterface.Chain): Any? {
        val cfg = ConfigHolder.current(prefs)
        if (!cfg.enabled || !cfg.spoofSimInfo) return chain.proceed()
        return CscSim.resolveCountryIso(cfg) ?: chain.proceed()
    }

    private fun simOperatorNameIntercept(chain: XposedInterface.Chain): Any? {
        val cfg = ConfigHolder.current(prefs)
        if (!cfg.enabled || !cfg.spoofSimInfo) return chain.proceed()
        return CscSim.resolveOperatorName(cfg)
    }

    // ---- SubscriptionManager & SubscriptionInfo (多卡槽 SIM 完整伪装) ----

    private fun hookSubscriptionManager(cl: ClassLoader) {
        runCatching {
            val subInfoClass = Class.forName("android.telephony.SubscriptionInfo", false, cl)
            safeHook(subInfoClass, "getMccString") { chain ->
                val cfg = ConfigHolder.current(prefs)
                if (!cfg.enabled || !cfg.spoofSimInfo) return@safeHook chain.proceed()
                CscSim.resolveMcc(cfg).takeIf { it.isNotBlank() } ?: chain.proceed()
            }
            safeHook(subInfoClass, "getMcc") { chain ->
                val cfg = ConfigHolder.current(prefs)
                if (!cfg.enabled || !cfg.spoofSimInfo) return@safeHook chain.proceed()
                CscSim.resolveMcc(cfg).toIntOrNull() ?: chain.proceed()
            }
            safeHook(subInfoClass, "getMncString") { chain ->
                val cfg = ConfigHolder.current(prefs)
                if (!cfg.enabled || !cfg.spoofSimInfo) return@safeHook chain.proceed()
                CscSim.resolveMnc(cfg).takeIf { it.isNotBlank() } ?: chain.proceed()
            }
            safeHook(subInfoClass, "getMnc") { chain ->
                val cfg = ConfigHolder.current(prefs)
                if (!cfg.enabled || !cfg.spoofSimInfo) return@safeHook chain.proceed()
                CscSim.resolveMnc(cfg).toIntOrNull() ?: chain.proceed()
            }
            safeHook(subInfoClass, "getCountryIso") { chain ->
                val cfg = ConfigHolder.current(prefs)
                if (!cfg.enabled || !cfg.spoofSimInfo) return@safeHook chain.proceed()
                CscSim.resolveCountryIso(cfg) ?: chain.proceed()
            }
            safeHook(subInfoClass, "getCarrierName") { chain ->
                val cfg = ConfigHolder.current(prefs)
                if (!cfg.enabled || !cfg.spoofSimInfo) return@safeHook chain.proceed()
                CscSim.resolveOperatorName(cfg).takeIf { it.isNotBlank() } ?: chain.proceed()
            }
            safeHook(subInfoClass, "getDisplayName") { chain ->
                val cfg = ConfigHolder.current(prefs)
                if (!cfg.enabled || !cfg.spoofSimInfo) return@safeHook chain.proceed()
                CscSim.resolveOperatorName(cfg).takeIf { it.isNotBlank() } ?: chain.proceed()
            }
        }.onFailure {
            log(Log.WARN, TAG, "CscChanger: SubscriptionInfo hook failed: $it")
        }
    }

    // ---- SystemProperties ----

    private fun hookSystemProperties(cl: ClassLoader, packageName: String) {
        runCatching {
            val clazz = Class.forName(CscConstants.CLASS_SYSTEM_PROPERTIES, false, cl)
            safeHook(clazz, "get", String::class.java) { chain -> propIntercept(chain, packageName) }
            safeHook(clazz, "get", String::class.java, String::class.java) { chain -> propIntercept(chain, packageName) }
            safeHook(clazz, "getInt", String::class.java, Int::class.javaPrimitiveType!!) { chain ->
                val key = chain.getArg(0) as? String ?: return@safeHook chain.proceed()
                val spoofed = matchPropValue(key, packageName)
                spoofed?.toIntOrNull() ?: chain.proceed()
            }
            safeHook(clazz, "getLong", String::class.java, Long::class.javaPrimitiveType!!) { chain ->
                val key = chain.getArg(0) as? String ?: return@safeHook chain.proceed()
                val spoofed = matchPropValue(key, packageName)
                spoofed?.toLongOrNull() ?: chain.proceed()
            }
            safeHook(clazz, "getBoolean", String::class.java, Boolean::class.javaPrimitiveType!!) { chain ->
                val key = chain.getArg(0) as? String ?: return@safeHook chain.proceed()
                val spoofed = matchPropValue(key, packageName)
                spoofed?.toBooleanStrictOrNull() ?: chain.proceed()
            }
        }.onFailure {
            log(Log.WARN, TAG, "CscChanger: SystemProperties hook failed: $it")
        }
    }

    private fun matchPropValue(key: String, packageName: String): String? {
        val resolved = ConfigHolder.currentResolved(prefs)
        val cfg = resolved.config

        // SIM 运营商相关属性（gsm.sim.* / gsm.operator.*）仅对应用商店生效，普通应用与系统绝对保持原生真实物理卡状态
        if (packageName in STORE_PACKAGES) {
            resolved.fastSimProps[key]?.let { return it }
        } else if (key.startsWith("gsm.sim.") || key.startsWith("gsm.operator.")) {
            return null
        }

        // 常规 CSC 与自定义属性预计算匹配 (O(1))
        resolved.fastCscProps[key]?.let { return it }

        // 机型属性预计算匹配 (O(1)，支持分应用独立机型)
        if (isDeviceTarget(cfg, packageName)) {
            resolved.devicePropsFor(packageName)[key]?.let { return it }
        }
        return null
    }

    private fun propIntercept(chain: XposedInterface.Chain, packageName: String): Any? {
        val key = chain.getArg(0) as? String ?: return chain.proceed()
        matchPropValue(key, packageName)?.let { return it }
        return chain.proceed()
    }

    // ---- SemSystemProperties（三星私有扩展） ----

    private fun hookSemSystemProperties(cl: ClassLoader, packageName: String) {
        runCatching {
            val clazz = Class.forName(CscConstants.CLASS_SEM_SYSTEM_PROPERTIES, false, cl)
            safeHook(clazz, "get", String::class.java) { chain -> propIntercept(chain, packageName) }
            safeHook(clazz, "get", String::class.java, String::class.java) { chain -> propIntercept(chain, packageName) }
            safeHook(clazz, "getInt", String::class.java, Int::class.javaPrimitiveType!!) { chain ->
                val key = chain.getArg(0) as? String ?: return@safeHook chain.proceed()
                matchPropValue(key, packageName)?.toIntOrNull() ?: chain.proceed()
            }
            safeHook(clazz, "getBoolean", String::class.java, Boolean::class.javaPrimitiveType!!) { chain ->
                val key = chain.getArg(0) as? String ?: return@safeHook chain.proceed()
                matchPropValue(key, packageName)?.toBooleanStrictOrNull() ?: chain.proceed()
            }
        }.onFailure {
            // 非三星 / 旧版本系统可能没有该类，忽略即可
        }
    }

    // ---- android.os.Build 静态字段（多数第三方 App 的实际读取入口） ----

    private fun applyBuildFields(config: CscConfig, packageName: String) {
        if (!isDeviceTarget(config, packageName)) return
        val profile = CscSpoof.deviceProfileFor(config, packageName) ?: return
        val osRelease = Build.VERSION.RELEASE.ifBlank { "17" }
        val buildId = Build.ID.ifBlank { "UP1A.231005.007" }
        val fingerprint = "${profile.brand}/${profile.productName}/${profile.device}:$osRelease/$buildId/${profile.model}:user/release-keys"
        val values = mapOf(
            "MODEL" to profile.model,
            "DEVICE" to profile.device,
            "PRODUCT" to profile.productName,
            "BRAND" to profile.brand,
            "MANUFACTURER" to profile.manufacturer,
            "BOARD" to profile.device,
            "HARDWARE" to profile.device,
            "FINGERPRINT" to fingerprint,
            "ID" to buildId,
            "DISPLAY" to "${profile.model}_$buildId",
            "SOC_MODEL" to profile.device,
            "TAGS" to "release-keys",
            "TYPE" to "user",
        )
        var applied = 0
        values.forEach { (name, value) ->
            if (setStaticFinalString(Build::class.java, name, value)) applied++
        }
        log(Log.INFO, TAG, "WeiTiao: $packageName 已应用机型 ${profile.name} (${profile.model})，字段=$applied/${values.size}")
    }

    private fun isDeviceTarget(config: CscConfig, packageName: String): Boolean =
        CscSpoof.isDeviceSpoofTarget(config, packageName)

    /**
     * Android 新版本阻止普通反射写 static final；先尝试普通反射，再以 Unsafe 写入。
     */
    private fun setStaticFinalString(owner: Class<*>, name: String, value: String): Boolean {
        val field = runCatching { owner.getDeclaredField(name).apply { isAccessible = true } }
            .getOrNull() ?: return false
        runCatching {
            field.set(null, value)
            if (field.get(null) == value) return true
        }
        return runCatching {
            val unsafeClass = Class.forName("sun.misc.Unsafe")
            val theUnsafeField = runCatching { unsafeClass.getDeclaredField("theUnsafe") }
                .getOrElse {
                    runCatching { unsafeClass.getDeclaredField("THE_ONE") }
                        .getOrElse { unsafeClass.getDeclaredField("u") }
                }.apply { isAccessible = true }
            val unsafe = theUnsafeField.get(null)
            val staticFieldBase = unsafeClass.getMethod("staticFieldBase", java.lang.reflect.Field::class.java)
            val staticFieldOffset = unsafeClass.getMethod("staticFieldOffset", java.lang.reflect.Field::class.java)
            val base = staticFieldBase.invoke(unsafe, field)
            val offset = staticFieldOffset.invoke(unsafe, field) as Long
            val putObject = runCatching {
                unsafeClass.getMethod("putObjectVolatile", Any::class.java, Long::class.javaPrimitiveType, Any::class.java)
            }.getOrElse {
                unsafeClass.getMethod("putObject", Any::class.java, Long::class.javaPrimitiveType, Any::class.java)
            }
            putObject.invoke(unsafe, base, offset, value)
            field.get(null) == value
        }.getOrDefault(false)
    }

    // ---- SemCscFeature（CscFeature_* 配置读取） ----

    private fun hookSemCscFeature(cl: ClassLoader) {
        runCatching {
            val clazz = Class.forName(CscConstants.CLASS_SEM_CSC_FEATURE, false, cl)
            safeHook(clazz, "getString", String::class.java) { chain -> cscFeatureIntercept(chain) }
            safeHook(clazz, "getString", String::class.java, String::class.java) { chain -> cscFeatureIntercept(chain) }
            safeHook(clazz, "getBoolean", String::class.java, Boolean::class.javaPrimitiveType!!) { chain ->
                cscFeatureBooleanIntercept(chain)
            }
        }.onFailure {
            // SemCscFeature 不存在则忽略
        }
    }

    private fun cscFeatureIntercept(chain: XposedInterface.Chain): Any? {
        val key = chain.getArg(0) as? String ?: return chain.proceed()
        val resolved = ConfigHolder.currentResolved(prefs)
        return resolved.fastCscFeatures[key] ?: chain.proceed()
    }

    private fun cscFeatureBooleanIntercept(chain: XposedInterface.Chain): Any? {
        val key = chain.getArg(0) as? String ?: return chain.proceed()
        val resolved = ConfigHolder.currentResolved(prefs)
        val value = resolved.fastCscFeatures[key] ?: return chain.proceed()
        return value.toBooleanStrictOrNull() ?: chain.proceed()
    }

    // ---- SemFloatingFeature（三星机型形态与品牌特性读取） ----

    private fun hookSemFloatingFeature(cl: ClassLoader, packageName: String) {
        runCatching {
            val clazz = Class.forName("com.samsung.android.feature.SemFloatingFeature", false, cl)
            safeHook(clazz, "getString", String::class.java) { chain -> floatingFeatureIntercept(chain, packageName) }
            safeHook(clazz, "getString", String::class.java, String::class.java) { chain -> floatingFeatureIntercept(chain, packageName) }
            safeHook(clazz, "getBoolean", String::class.java) { chain -> floatingFeatureBooleanIntercept(chain, packageName) }
            safeHook(clazz, "getBoolean", String::class.java, Boolean::class.javaPrimitiveType!!) { chain ->
                floatingFeatureBooleanIntercept(chain, packageName)
            }
            safeHook(clazz, "getInt", String::class.java) { chain -> floatingFeatureIntIntercept(chain, packageName) }
            safeHook(clazz, "getInt", String::class.java, Int::class.javaPrimitiveType!!) { chain ->
                floatingFeatureIntIntercept(chain, packageName)
            }
        }.onFailure {
            // SemFloatingFeature 不存在则忽略
        }
    }

    private fun floatingFeatureIntercept(chain: XposedInterface.Chain, packageName: String): Any? {
        val key = chain.getArg(0) as? String ?: return chain.proceed()
        val resolved = ConfigHolder.currentResolved(prefs)
        if (!isDeviceTarget(resolved.config, packageName)) return chain.proceed()
        return resolved.floatingFeaturesFor(packageName)[key] ?: chain.proceed()
    }

    private fun floatingFeatureBooleanIntercept(chain: XposedInterface.Chain, packageName: String): Any? {
        val key = chain.getArg(0) as? String ?: return chain.proceed()
        val resolved = ConfigHolder.currentResolved(prefs)
        if (!isDeviceTarget(resolved.config, packageName)) return chain.proceed()
        val value = resolved.floatingFeaturesFor(packageName)[key] ?: return chain.proceed()
        return value.equals("TRUE", ignoreCase = true)
    }

    private fun floatingFeatureIntIntercept(chain: XposedInterface.Chain, packageName: String): Any? {
        val key = chain.getArg(0) as? String ?: return chain.proceed()
        val resolved = ConfigHolder.currentResolved(prefs)
        if (!isDeviceTarget(resolved.config, packageName)) return chain.proceed()
        val value = resolved.floatingFeaturesFor(packageName)[key] ?: return chain.proceed()
        return value.toIntOrNull() ?: chain.proceed()
    }

    // ---- Settings Provider（系统设置与蓝牙/设备名称读取） ----

    private fun hookSettingsProvider(cl: ClassLoader, packageName: String) {
        runCatching {
            val globalClass = Class.forName("android.provider.Settings\$Global", false, cl)
            safeHook(globalClass, "getString", android.content.ContentResolver::class.java, String::class.java) { chain ->
                val name = chain.getArg(1) as? String
                if (name == "device_name" || name == "device_provisioned_name") {
                    val resolved = ConfigHolder.currentResolved(prefs)
                    if (isDeviceTarget(resolved.config, packageName)) {
                        resolved.deviceProfileFor(packageName)?.name?.let { return@safeHook it }
                    }
                }
                chain.proceed()
            }
            val systemClass = Class.forName("android.provider.Settings\$System", false, cl)
            safeHook(systemClass, "getString", android.content.ContentResolver::class.java, String::class.java) { chain ->
                val name = chain.getArg(1) as? String
                if (name == "device_name") {
                    val resolved = ConfigHolder.currentResolved(prefs)
                    if (isDeviceTarget(resolved.config, packageName)) {
                        resolved.deviceProfileFor(packageName)?.name?.let { return@safeHook it }
                    }
                }
                chain.proceed()
            }
        }.onFailure {
            // 忽略非标准环境
        }
    }

    // ---------- hook 工具 ----------

    private fun safeHook(
        clazz: Class<*>,
        methodName: String,
        vararg paramTypes: Class<*>,
        interceptor: (XposedInterface.Chain) -> Any?,
    ) {
        runCatching {
            val method = clazz.getMethod(methodName, *paramTypes)
            hook(method).intercept { chain ->
                interceptor(chain)
            }
        }.onFailure {
            log(Log.WARN, TAG, "CscChanger: hook $methodName failed: $it")
        }
    }

    private class DeviceResolved(
        val profile: DeviceProfile,
        val devMap: Map<String, String>,
        val floatMap: Map<String, String>,
    )

    private class ResolvedConfig(
        val config: CscConfig,
        val fastCscProps: Map<String, String>,
        val fastSimProps: Map<String, String>,
        val fastDeviceProps: Map<String, String>,
        val fastCscFeatures: Map<String, String>,
        val fastFloatingFeatures: Map<String, String>,
        val deviceProfile: DeviceProfile?,
        val perAppDeviceMaps: Map<String, DeviceResolved> = emptyMap(),
    ) {
        fun devicePropsFor(packageName: String): Map<String, String> =
            perAppDeviceMaps[packageName]?.devMap ?: fastDeviceProps

        fun floatingFeaturesFor(packageName: String): Map<String, String> =
            perAppDeviceMaps[packageName]?.floatMap ?: fastFloatingFeatures

        fun deviceProfileFor(packageName: String): DeviceProfile? =
            perAppDeviceMaps[packageName]?.profile ?: deviceProfile
    }

    private object ConfigHolder {
        private const val RELOAD_INTERVAL_MS = 2000L

        @Volatile
        private var currentResolved: ResolvedConfig = ResolvedConfig(
            config = CscConfig(),
            fastCscProps = emptyMap(),
            fastSimProps = emptyMap(),
            fastDeviceProps = emptyMap(),
            fastCscFeatures = emptyMap(),
            fastFloatingFeatures = emptyMap(),
            deviceProfile = null,
        )

        @Volatile
        private var lastCheckElapsed = 0L

        fun current(prefs: SharedPreferences): CscConfig = currentResolved(prefs).config

        fun currentResolved(prefs: SharedPreferences): ResolvedConfig {
            val now = SystemClock.elapsedRealtime()
            if (now - lastCheckElapsed >= RELOAD_INTERVAL_MS) {
                synchronized(this) {
                    if (now - lastCheckElapsed >= RELOAD_INTERVAL_MS) {
                        lastCheckElapsed = now
                        val cfg = CscConfig.fromPrefs(prefs)
                        currentResolved = buildResolved(cfg)
                    }
                }
            }
            return currentResolved
        }

        private fun buildDeviceResolved(profile: DeviceProfile): DeviceResolved {
            val devMap = HashMap<String, String>(32)
            val floatMap = HashMap<String, String>(8)
            val fingerprint = "${profile.brand}/${profile.productName}/${profile.device}:14/UP1A.231005.007/${profile.model}:user/release-keys"
            val isFold = profile.name.contains("Fold", ignoreCase = true) || profile.device.startsWith("q") || profile.model.contains("F9")
            val isFlip = profile.name.contains("Flip", ignoreCase = true) || profile.device.startsWith("b") || profile.model.contains("F7")
            val isTablet = profile.name.contains("Tab", ignoreCase = true) || profile.device.startsWith("gts") || profile.model.contains("X")
            val characteristics = when {
                isFold -> "fold,nosdcard"
                isFlip -> "flip,nosdcard"
                isTablet -> "tablet,nosdcard"
                else -> "nosdcard"
            }

            val modelKeys = listOf("ro.product.model", "ro.product.vendor.model", "ro.product.odm.model", "ro.product.product.model", "ro.product.system.model", "ro.product.system_ext.model")
            for (k in modelKeys) devMap[k] = profile.model

            val devKeys = listOf("ro.product.device", "ro.build.product", "ro.product.odm.device", "ro.product.vendor.device", "ro.product.product.device", "ro.product.system.device", "ro.product.system_ext.device", "ro.product.board", "ro.hardware", "ro.board.platform")
            for (k in devKeys) devMap[k] = profile.device

            val nameKeys = listOf("ro.product.name", "ro.product.odm.name", "ro.product.vendor.name", "ro.product.product.name", "ro.product.system.name", "ro.product.system_ext.name")
            for (k in nameKeys) devMap[k] = profile.productName

            devMap["ro.product.marketname"] = profile.name
            devMap["ro.vendor.oplus.market.name"] = profile.name
            devMap["ro.product.display"] = profile.name

            val brandKeys = listOf("ro.product.brand", "ro.product.odm.brand", "ro.product.vendor.brand", "ro.product.product.brand", "ro.product.system.brand", "ro.product.system_ext.brand")
            for (k in brandKeys) devMap[k] = profile.brand

            val mfrKeys = listOf("ro.product.manufacturer", "ro.product.odm.manufacturer", "ro.product.vendor.manufacturer", "ro.product.product.manufacturer", "ro.product.system.manufacturer", "ro.product.system_ext.manufacturer")
            for (k in mfrKeys) devMap[k] = profile.manufacturer

            devMap["ro.build.flavor"] = "${profile.productName}-user"

            val fpKeys = listOf("ro.build.fingerprint", "ro.bootimage.build.fingerprint", "ro.vendor.build.fingerprint", "ro.odm.build.fingerprint", "ro.system.build.fingerprint", "ro.system_ext.build.fingerprint")
            for (k in fpKeys) devMap[k] = fingerprint

            devMap["ro.soc.model"] = profile.device
            devMap["ro.vendor.qti.soc_name"] = profile.device
            devMap["ro.build.characteristics"] = characteristics

            if (isFold) floatMap["SEC_FLOATING_FEATURE_FRAMEWORK_SUPPORT_FOLDABLE_TYPE_FOLD"] = "TRUE"
            if (isFlip) floatMap["SEC_FLOATING_FEATURE_FRAMEWORK_SUPPORT_FOLDABLE_TYPE_FLIP"] = "TRUE"
            if (isFold || isFlip) floatMap["SEC_FLOATING_FEATURE_FRAMEWORK_CONFIG_FOLD_MODEL"] = "TRUE"
            floatMap["SEC_FLOATING_FEATURE_SETTINGS_CONFIG_BRAND_NAME"] = profile.name
            floatMap["SEC_FLOATING_FEATURE_COMMON_CONFIG_BRAND_NAME"] = profile.name

            return DeviceResolved(profile = profile, devMap = devMap, floatMap = floatMap)
        }

        private fun buildResolved(config: CscConfig): ResolvedConfig {
            // 1. 常规 CSC 属性表预构建 (O(1) 查表，仅当商店跨区开启时填充销售码/国家码，自定义属性始终生效)
            val cscMap = HashMap<String, String>(32)
            if (config.enabled) {
                if (config.spoofSalesCode && config.salesCode.isNotBlank()) {
                    val sc = config.salesCode
                    cscMap[CscConstants.PROP_SALES_CODE] = sc
                    cscMap[CscConstants.PROP_PERSIST_OMC_SALES_CODE] = sc
                    cscMap[CscConstants.PROP_RO_OMC_SALES_CODE] = sc
                    cscMap[CscConstants.PROP_OMCNW_CODE] = sc
                    cscMap[CscConstants.PROP_OFFICIAL_CSC] = sc
                    cscMap[CscConstants.PROP_CSC_CODE] = sc
                    if (config.spoofRilSalesCode) {
                        cscMap[CscConstants.PROP_RIL_SALES_CODE] = sc
                    }
                }
                if (config.spoofCountryCode) {
                    val country = config.countryCode.takeIf { it.isNotBlank() } ?: CscConstants.countryCodeOf(config.salesCode)
                    if (!country.isNullOrBlank()) {
                        cscMap[CscConstants.PROP_COUNTRY_CODE] = country
                        cscMap[CscConstants.PROP_PERSIST_OMC_COUNTRY_CODE] = country
                        cscMap[CscConstants.PROP_RO_OMC_COUNTRY_CODE] = country
                    }
                }
            }
            // 用户自定义系统属性（与跨区开关独立）
            cscMap.putAll(config.customProps)

            // 2. SIM 属性表预构建（仅商店跨区开启且目标为应用商店时生效）
            val simMap = HashMap<String, String>(8)
            if (config.enabled && config.spoofSimInfo) {
                val numeric = CscSim.resolveNumeric(config)
                if (numeric.isNotBlank()) {
                    simMap[CscConstants.PROP_GSM_SIM_NUMERIC] = numeric
                    simMap[CscConstants.PROP_GSM_OPERATOR_NUMERIC] = numeric
                }
                val iso = CscSim.resolveCountryIso(config)
                if (!iso.isNullOrBlank()) {
                    simMap[CscConstants.PROP_GSM_SIM_ISO] = iso
                    simMap[CscConstants.PROP_GSM_OPERATOR_ISO] = iso
                }
                val opName = CscSim.resolveOperatorName(config)
                if (opName.isNotBlank()) {
                    simMap[CscConstants.PROP_GSM_SIM_ALPHA] = opName
                    simMap[CscConstants.PROP_GSM_OPERATOR_ALPHA] = opName
                }
            }

            // 3. 机型伪装与浮动特性预构建（与商店跨区开关独立，支持全局机型 + 分应用独立机型）
            val globalResolved = if (config.spoofDevice && config.deviceName.isNotBlank()) {
                DeviceModels.findByName(config.deviceName)?.let { buildDeviceResolved(it) }
            } else null

            val perAppResolved = if (config.spoofDevice && config.perAppDeviceModels.isNotEmpty()) {
                val modelCache = HashMap<String, DeviceResolved>()
                val appMap = HashMap<String, DeviceResolved>(config.perAppDeviceModels.size)
                for ((pkg, modelName) in config.perAppDeviceModels) {
                    if (modelName.isBlank()) continue
                    val dr = modelCache[modelName] ?: DeviceModels.findByName(modelName)?.let {
                        buildDeviceResolved(it).also { built -> modelCache[modelName] = built }
                    }
                    if (dr != null) {
                        appMap[pkg] = dr
                    }
                }
                appMap
            } else emptyMap()

            val cscFeatureMap = if (config.spoofSemCscFeature) config.customCscFeatures else emptyMap()

            return ResolvedConfig(
                config = config,
                fastCscProps = cscMap,
                fastSimProps = simMap,
                fastDeviceProps = globalResolved?.devMap ?: emptyMap(),
                fastCscFeatures = cscFeatureMap,
                fastFloatingFeatures = globalResolved?.floatMap ?: emptyMap(),
                deviceProfile = globalResolved?.profile,
                perAppDeviceMaps = perAppResolved,
            )
        }
    }

    private object TuningConfigHolder {
        private const val RELOAD_INTERVAL_MS = 2000L

        @Volatile private var cached = TuningConfig()
        @Volatile private var lastCheckElapsed = 0L

        fun current(prefs: SharedPreferences): TuningConfig {
            val now = SystemClock.elapsedRealtime()
            if (now - lastCheckElapsed >= RELOAD_INTERVAL_MS) {
                synchronized(this) {
                    if (now - lastCheckElapsed >= RELOAD_INTERVAL_MS) {
                        lastCheckElapsed = now
                        cached = TuningConfig.fromPrefs(prefs)
                    }
                }
            }
            return cached
        }
    }

    private companion object {
        const val TAG = "CscChanger"
        const val WECHAT_PACKAGE = "com.tencent.mm"
        val STORE_PACKAGES = setOf(
            "com.sec.android.app.samsungapps",
            "com.samsung.android.themestore",
            "com.samsung.android.themecenter",
            "com.osp.app.signin",
            "com.samsung.android.samsungaccount",
            "com.samsung.android.authfw",
        )
    }
}
