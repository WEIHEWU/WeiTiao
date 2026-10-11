package com.weitiao.tuning

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.weitiao.tuning.core.LanguageManager
import com.weitiao.tuning.core.TuningPrefs
import kotlinx.coroutines.launch
import com.weitiao.tuning.ui.CscChangerApp
import com.weitiao.tuning.ui.theme.CscChangerTheme

class MainActivity : ComponentActivity() {

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LanguageManager.apply(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        LanguageManager.apply(this)
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            CscChangerTheme {
                CscChangerApp()
            }
        }
        TuningPrefs.resyncServices(this)
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            com.weitiao.tuning.core.StoreCleaner.cleanupPollutedOmcProps()
        }
    }

    override fun onResume() {
        super.onResume()
        // 进程曾被划掉/回收后，回到前台立刻重新武装常驻充电服务
        TuningPrefs.resyncServices(this)
    }
}
