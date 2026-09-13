package com.example.stockaidemo.config

import com.tencent.kuikly.core.utils.PlatformUtils

object AIConfig {
    /** Android Emulator reaches the host Mac through 10.0.2.2; iOS Simulator shares localhost. */
    private val host: String
        get() = if (PlatformUtils.isIOS()) "localhost" else "10.0.2.2"

    val proxyUrl: String
        get() = "http://$host:8787/analyze"

    val quoteUrl: String
        get() = "http://$host:8787/quotes"

    val searchUrl: String
        get() = "http://$host:8787/stock-search"
    const val timeoutSeconds: Int = 8
}
