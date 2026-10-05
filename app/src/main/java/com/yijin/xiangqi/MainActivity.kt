package com.yijin.xiangqi

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.yijin.xiangqi.engineverify.EngineVerifyScreen
import com.yijin.xiangqi.ui.theme.YijinTheme

/**
 * M0 唯一入口。
 *
 * 这里只挂「引擎验证」页面——按《03-交给DeepSeek的开发任务书》§3 的要求，
 * 验证页仅用于工程调试，**不是最终产品首页**。
 * 引擎没验证通过之前不投入业务页面。
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            YijinTheme {
                EngineVerifyScreen()
            }
        }
    }
}