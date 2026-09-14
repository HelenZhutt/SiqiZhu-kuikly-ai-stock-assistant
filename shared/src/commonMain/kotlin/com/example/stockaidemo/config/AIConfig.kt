package com.example.stockaidemo.config

import com.tencent.kuikly.core.utils.PlatformUtils

object AIConfig {
    /** Android Emulator uses 10.0.2.2; iOS Simulator and HarmonyOS use localhost (hdc rport on OHOS). */
    private val host: String
        get() = if (PlatformUtils.isAndroid()) "10.0.2.2" else "localhost"

    val proxyUrl: String
        get() = "http://$host:8787/analyze"

    val quoteUrl: String
        get() = "http://$host:8787/quotes"

    val searchUrl: String
        get() = "http://$host:8787/stock-search"
    const val timeoutSeconds: Int = 8
}
