package com.weitiao.tuning.core

import com.weitiao.tuning.WechatHooks
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LsposedScopeHelperTest {

    @Test
    fun coreScopesContainEssentialPackages() {
        val core = LsposedScopeHelper.CORE_SCOPES
        assertTrue(core.contains("com.sec.android.app.samsungapps"))
        assertTrue(core.contains("com.tencent.mm"))
        assertTrue(core.contains("com.osp.app.signin"))
        assertTrue(core.contains("com.samsung.android.themestore"))
        assertTrue(core.contains("com.samsung.android.themecenter"))
        assertTrue(core.contains("com.samsung.android.samsungaccount"))
        assertTrue(core.contains("com.samsung.android.authfw"))
    }

    @Test
    fun syncScopesWithRootMethodRemoved() {
        val methods = LsposedScopeHelper::class.java.declaredMethods.map { it.name }
        assertFalse(methods.contains("syncScopesWithRoot"))
    }

    @Test
    fun wechatHooksRegisterOnlyRunsInExactMainProcess() {
        val loader = checkNotNull(javaClass.classLoader)
        for (nonMainProcess in listOf(null, "", "com.tencent.mm:push", "com.tencent.mm:support", "com.tencent.mm:appbrand0")) {
            var configCalls = 0
            val logs = mutableListOf<String>()
            val hooks = WechatHooks.forProcessTest(
                config = { configCalls++; TuningConfig() },
                logger = { msg, _ -> logs += msg },
            )
            hooks.register(loader, nonMainProcess)
            assertEquals("Expected 0 config calls for $nonMainProcess", 0, configCalls)
            assertTrue("Expected 0 logs for $nonMainProcess", logs.isEmpty())
        }

        var mainConfigCalls = 0
        val mainLogs = mutableListOf<String>()
        val mainHooks = WechatHooks.forProcessTest(
            config = { mainConfigCalls++; TuningConfig(stepBackend = TuningConfig.BACKEND_EXACT) },
            logger = { msg, _ -> mainLogs += msg },
        )
        mainHooks.register(loader, "com.tencent.mm")
        assertEquals(1, mainConfigCalls)
        assertTrue(mainLogs.isNotEmpty())
    }
}
