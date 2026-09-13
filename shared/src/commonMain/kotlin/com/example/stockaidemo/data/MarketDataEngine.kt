package com.example.stockaidemo.data

import com.example.stockaidemo.config.AIConfig
import com.example.stockaidemo.model.Stock
import com.example.stockaidemo.model.PricePoint
import com.example.stockaidemo.model.StockSearchResult
import com.example.stockaidemo.model.StockQuestionResolution
import com.tencent.kuikly.core.module.NetworkModule
import com.tencent.kuikly.core.nvi.serialization.json.JSONObject

/** 真实行情服务层。页面不依赖供应商字段，失败时保留仓库中的演示快照。 */
object MarketDataEngine {

    private val stockNameSuffixes = listOf(
        "银行", "股份", "控股", "科技", "矿业", "重工", "时代", "证券",
        "能源", "汽车", "集团", "药业", "电器", "地产", "通信"
    )

    fun refresh(
        networkModule: NetworkModule,
        callback: (stocks: List<Stock>?, message: String) -> Unit
    ) {
        networkModule.httpRequest(
            url = AIConfig.quoteUrl,
            isPost = false,
            param = JSONObject(),
            timeout = 8,
            responseCallback = { data: JSONObject, success: Boolean, error: String, _ ->
                if (!success || !data.optBoolean("ok")) {
                    callback(null, error.ifEmpty { data.optString("error", "真实行情暂时不可用") })
                    return@httpRequest
                }

                val stocks = parseMarketResponse(data)

                if (stocks.isEmpty()) callback(null, "行情数据为空")
                else callback(stocks, "")
            }
        )
    }

    fun search(
        networkModule: NetworkModule,
        query: String,
        callback: (results: List<StockSearchResult>?, message: String) -> Unit
    ) {
        val queries = searchQueryVariants(query)
        if (queries.isEmpty()) {
            callback(emptyList(), "")
            return
        }

        val merged = mutableListOf<StockSearchResult>()
        var remaining = queries.size
        var lastError = ""
        queries.forEach { searchQuery ->
            networkModule.httpRequest(
                url = AIConfig.searchUrl,
                isPost = true,
                param = JSONObject().apply { put("query", searchQuery) },
                headers = JSONObject().apply { put("Content-Type", "application/json") },
                timeout = 8,
                responseCallback = { data: JSONObject, success: Boolean, error: String, _ ->
                    if (!success || !data.optBoolean("ok")) {
                        lastError = error.ifEmpty { data.optString("error", "搜索暂时不可用") }
                    } else {
                        merged.addAll(parseSearchResponse(data))
                    }
                    remaining -= 1
                    if (remaining == 0) {
                        val results = merged
                            .distinctBy { it.symbol.lowercase() }
                            .filter { resultMatchesTerm(it, query) }
                        if (results.isEmpty() && lastError.isNotEmpty()) callback(null, lastError)
                        else callback(rankSearchResults(query, results).take(16), "")
                    }
                }
            )
        }
    }

    /**
     * Search the quotes already held by the app. This gives the search box an
     * immediate, useful result even while the remote symbol lookup is pending
     * or temporarily unavailable.
     */
    internal fun searchCachedStocks(query: String, stocks: List<Stock>): List<StockSearchResult> {
        val normalized = normalizeSearchToken(query)
        if (normalized.isEmpty()) return emptyList()
        return stocks.filter { stock ->
            normalizeSearchToken(stock.name).contains(normalized) ||
                stock.code.contains(normalized) ||
                stock.marketSymbol.lowercase().contains(normalized)
        }.map { stock ->
            val symbol = stock.marketSymbol.ifEmpty { marketSymbolForCode(stock.code).orEmpty() }
            StockSearchResult(
                symbol = symbol,
                code = stock.code,
                name = stock.name,
                market = symbol.take(2).uppercase()
            )
        }.filter { isSupportedSymbol(it.symbol) }
    }

    fun loadSymbols(
        networkModule: NetworkModule,
        symbols: List<String>,
        callback: (stocks: List<Stock>?, message: String) -> Unit
    ) {
        val safeSymbols = symbols.filter { isSupportedSymbol(it) }.distinct()
        if (safeSymbols.isEmpty()) {
            callback(null, "没有可加载的股票代码")
            return
        }
        if (safeSymbols.size > 12) {
            val batches = safeSymbols.chunked(12)
            val loaded = mutableListOf<Stock>()
            var remaining = batches.size
            var lastError = ""
            batches.forEach { batch ->
                loadSymbols(networkModule, batch) { stocks, message ->
                    if (stocks != null) loaded.addAll(stocks) else if (message.isNotEmpty()) lastError = message
                    remaining -= 1
                    if (remaining == 0) {
                        callback(loaded.takeIf { it.isNotEmpty() }, if (loaded.isEmpty()) lastError else "")
                    }
                }
            }
            return
        }
        networkModule.httpRequest(
            url = AIConfig.quoteUrl + "?symbols=" + safeSymbols.joinToString(","),
            isPost = false,
            param = JSONObject(),
            timeout = 8,
            responseCallback = { data: JSONObject, success: Boolean, error: String, _ ->
                if (!success || !data.optBoolean("ok")) {
                    callback(null, error.ifEmpty { data.optString("error", "行情加载失败") })
                } else {
                    val stocks = parseMarketResponse(data)
                    callback(stocks.takeIf { it.isNotEmpty() }, if (stocks.isEmpty()) "未取得行情" else "")
                }
            }
        )
    }

    /**
     * A comparison must be atomic: a partial quote response is not a successful
     * comparison context. Retry only the missing symbols once, preserve the
     * requested order, and report the exact codes that are still unavailable.
     */
    fun loadAllSymbols(
        networkModule: NetworkModule,
        symbols: List<String>,
        requireHistory: Boolean = false,
        callback: (stocks: List<Stock>, message: String) -> Unit
    ) {
        val expected = symbols.filter { isSupportedSymbol(it) }.distinct()
        if (expected.isEmpty()) {
            callback(emptyList(), "没有可加载的股票代码")
            return
        }

        fun matches(stock: Stock, symbol: String): Boolean {
            return stock.marketSymbol.equals(symbol, ignoreCase = true) ||
                stock.code == symbol.drop(2)
        }

        fun isReady(stock: Stock, symbol: String): Boolean {
            return matches(stock, symbol) && (!requireHistory || stock.history.size >= 5)
        }

        loadSymbols(networkModule, expected) { firstStocks, firstError ->
            val merged = firstStocks.orEmpty().distinctBy { it.code }.toMutableList()
            val missing = expected.filter { symbol -> merged.none { isReady(it, symbol) } }
            if (missing.isEmpty()) {
                callback(expected.mapNotNull { symbol -> merged.firstOrNull { matches(it, symbol) } }, "")
                return@loadSymbols
            }
            loadSymbols(networkModule, missing) { retriedStocks, retryError ->
                retriedStocks.orEmpty().forEach { retried ->
                    val existingIndex = merged.indexOfFirst { it.code == retried.code }
                    if (existingIndex < 0) merged.add(retried)
                    else if (retried.history.size >= merged[existingIndex].history.size) merged[existingIndex] = retried
                }
                val stillMissing = expected.filter { symbol -> merged.none { isReady(it, symbol) } }
                if (stillMissing.isNotEmpty()) {
                    val codes = stillMissing.joinToString("、") { it.drop(2) }
                    val detail = retryError.ifEmpty { firstError }
                    callback(merged, "$codes 行情暂时获取失败，已自动重试${if (detail.isEmpty()) "" else "：$detail"}")
                } else {
                    callback(expected.mapNotNull { symbol -> merged.firstOrNull { matches(it, symbol) } }, "")
                }
            }
        }
    }

    /**
     * Resolve explicit stocks mentioned in a chat question and load their real
     * quote/history on demand. This never mutates the watchlist.
     */
    fun loadQuestionStocks(
        networkModule: NetworkModule,
        question: String,
        callback: (stocks: List<Stock>) -> Unit
    ) {
        resolveQuestionStocks(networkModule, question) { resolution ->
            callback(if (resolution.errorMessage.isEmpty()) resolution.stocks else emptyList())
        }
    }

    /**
     * Resolves names mentioned in a question before the AI request. A unique
     * result is loaded automatically; genuine ambiguity is returned to the UI
     * so the user can choose without losing the original question.
     */
    fun resolveQuestionStocks(
        networkModule: NetworkModule,
        question: String,
        callback: (StockQuestionResolution) -> Unit
    ) {
        val known = StockRepository.getAllKnownStocks()
        val directSymbols = explicitSymbols(question).toMutableList()
        val locallyNamedSymbols = known.filter { stock ->
            question.contains(stock.name) || question.contains(stock.code) ||
                question.contains(AIChatEngine.normalizedStockName(stock.name))
        }.mapNotNull { stock ->
            stock.marketSymbol.takeIf(::isSupportedSymbol) ?: marketSymbolForCode(stock.code)
        }
        val candidates = unresolvedStockNameCandidates(question, known)
        val searchedSymbols = mutableListOf<String>()

        fun loadResolved() {
            val symbols = (directSymbols + locallyNamedSymbols + searchedSymbols).distinct()
            if (symbols.isEmpty()) {
                callback(StockQuestionResolution())
                return
            }
            loadAllSymbols(networkModule, symbols, requireHistory = symbols.size >= 2) { stocks, message ->
                callback(StockQuestionResolution(stocks = stocks, errorMessage = message))
            }
        }

        fun searchNext(index: Int) {
            if (index >= candidates.size) {
                loadResolved()
                return
            }
            val candidate = candidates[index]
            search(networkModule, candidate) { results, _ ->
                val matching = results.orEmpty().filter { resultMatchesTerm(it, candidate) }
                val exact = matching.filter {
                    normalizeSearchToken(it.name) == normalizeSearchToken(candidate)
                }
                when {
                    exact.size == 1 -> {
                        searchedSymbols.add(exact.first().symbol)
                        searchNext(index + 1)
                    }
                    matching.size == 1 -> {
                        searchedSymbols.add(matching.first().symbol)
                        searchNext(index + 1)
                    }
                    matching.size > 1 -> callback(
                        StockQuestionResolution(
                            ambiguousTerm = candidate,
                            choices = matching.take(6)
                        )
                    )
                    else -> searchNext(index + 1)
                }
            }
        }
        searchNext(0)
    }

    internal fun explicitSymbols(question: String): List<String> =
        Regex("(?<!\\d)\\d{5,6}(?!\\d)").findAll(question).mapNotNull { match ->
            marketSymbolForCode(match.value)
        }.distinct().toList()

    internal fun marketSymbolForCode(code: String): String? = when {
        Regex("\\d{5}").matches(code) -> "hk$code"
        Regex("\\d{6}").matches(code) && code.first() in "569" -> "sh$code"
        Regex("\\d{6}").matches(code) -> "sz$code"
        else -> null
    }

    internal fun stockNameCandidates(question: String): List<String> {
        var cleaned = question.replace(Regex("\\d+日"), " ")
        listOf(
            "帮我", "请", "能不能", "可以", "查询", "搜索", "查一下", "分析", "比较", "对比", "看看", "一下", "最近", "股票",
            "怎么样", "走势", "趋势", "风险", "表现", "数据", "行情", "短期", "长期", "更强", "更好",
            "关键观察点", "观察点", "关键观察", "的"
        ).forEach {
            cleaned = cleaned.replace(it, " ")
        }
        val fragments = cleaned.split(Regex("[，。！？、,!?\\s]+|和|与|跟|及|vs|VS"))
        return fragments.map { it.trim() }.mapNotNull { fragment ->
            stockNameSuffixes.firstOrNull { fragment.endsWith(it) }
                ?.let { fragment.takeLast((it.length + 6).coerceAtMost(fragment.length)) }
                ?: fragment.takeIf {
                    it.length in 2..8 && Regex("[\\u4e00-\\u9fff]").containsMatchIn(it)
                }
        }.filter { it.length in 2..8 }.distinct()
    }

    /**
     * Keep only names that are not already represented by the locally available
     * stocks. Matching must be candidate-specific: checking the whole question
     * would incorrectly discard an unknown name as soon as any known stock is
     * mentioned in the same comparison request.
     */
    internal fun unresolvedStockNameCandidates(question: String, known: List<Stock>): List<String> {
        return stockNameCandidates(question).filter { candidate ->
            known.none { stock ->
                val knownName = normalizeSearchToken(stock.name)
                val normalizedCandidate = normalizeSearchToken(candidate)
                normalizedCandidate == knownName ||
                    (normalizedCandidate.length >= 2 && knownName.contains(normalizedCandidate))
            }
        }
    }

    internal fun normalizeSearchToken(value: String): String = value.trim().lowercase()
        .replace(Regex("[\\s·・._－-]+"), "")
        .replace(Regex("(sw|w|b|s)$", RegexOption.IGNORE_CASE), "")

    /**
     * Tencent's symbol search treats listed-share suffixes as part of pinyin.
     * Query the common suffix forms too so initials such as YSKJ can discover
     * both YSKJ and YSKJW listings without requiring the user to know the suffix.
     */
    internal fun searchQueryVariants(value: String): List<String> {
        val query = value.trim()
        if (query.isEmpty()) return emptyList()
        val compact = query.lowercase().replace(Regex("[\\s·・._－-]+"), "")
        if (!Regex("[a-z]{2,24}").matches(compact)) return listOf(query)
        val base = normalizeSearchToken(compact)
        return listOf(compact, base, "${base}w", "${base}sw", "${base}b", "${base}s").distinct()
    }

    internal fun resultMatchesTerm(result: StockSearchResult, term: String): Boolean {
        val query = normalizeSearchToken(term)
        return normalizeSearchToken(result.name).contains(query) ||
            normalizeSearchToken(result.pinyin).contains(query) ||
            result.code.contains(query) || result.symbol.lowercase().contains(query)
    }

    internal fun rankSearchResults(query: String, results: List<StockSearchResult>): List<StockSearchResult> {
        val normalized = normalizeSearchToken(query)
        return results.sortedBy { result ->
            when {
                normalizeSearchToken(result.name) == normalized -> 0
                normalizeSearchToken(result.pinyin) == normalized -> 1
                normalizeSearchToken(result.name).startsWith(normalized) -> 2
                normalizeSearchToken(result.pinyin).startsWith(normalized) -> 3
                else -> 4
            }
        }
    }

    internal fun isSupportedSymbol(symbol: String): Boolean {
        return Regex("^(sh|sz)\\d{6}$", RegexOption.IGNORE_CASE).matches(symbol) ||
            Regex("^hk\\d{5}$", RegexOption.IGNORE_CASE).matches(symbol)
    }

    internal fun parseSearchResponse(data: JSONObject): List<StockSearchResult> {
        val array = data.optJSONArray("results") ?: return emptyList()
        val results = mutableListOf<StockSearchResult>()
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            val symbol = item.optString("symbol")
            if (!isSupportedSymbol(symbol)) continue
            results.add(
                StockSearchResult(
                    symbol = symbol,
                    code = item.optString("code"),
                    name = item.optString("name"),
                    market = item.optString("market"),
                    pinyin = item.optString("pinyin")
                )
            )
        }
        return results
    }

    internal fun parseMarketResponse(data: JSONObject): List<Stock> {
        val array = data.optJSONArray("stocks") ?: return emptyList()
        val stocks = mutableListOf<Stock>()
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            val price = item.optDouble("price", 0.0)
            if (item.optString("code").isEmpty() || price <= 0.0) continue
            val history = mutableListOf<PricePoint>()
            val historyArray = item.optJSONArray("history")
            if (historyArray != null) {
                for (historyIndex in 0 until historyArray.length()) {
                    val point = historyArray.optJSONObject(historyIndex) ?: continue
                    val close = point.optDouble("close", 0.0)
                    if (close > 0.0) history.add(PricePoint(
                        point.optString("date"), close, point.optDouble("volume", 0.0)
                    ))
                }
            }
            stocks.add(
                Stock(
                    code = item.optString("code"),
                    name = item.optString("name"),
                    price = price,
                    changeAmount = item.optDouble("changeAmount", 0.0),
                    changePercent = item.optDouble("changePercent", 0.0),
                    high = item.optDouble("high", price),
                    low = item.optDouble("low", price),
                    volume = item.optString("volume", "--"),
                    dataSource = data.optString("source", "实时行情"),
                    updatedAt = item.optString("updatedAt"),
                    history = history.takeLast(20),
                    marketSymbol = item.optString("symbol"),
                    volumeValue = item.optDouble("volumeValue", 0.0)
                )
            )
        }
        return stocks
    }
}
