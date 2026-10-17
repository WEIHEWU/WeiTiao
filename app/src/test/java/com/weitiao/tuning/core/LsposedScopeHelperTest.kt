package com.weitiao.tuning.core

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
}
