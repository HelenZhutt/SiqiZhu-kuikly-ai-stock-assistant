package com.example.stockaidemo.data

import com.example.stockaidemo.config.AIConfig
import com.example.stockaidemo.model.ChatAIResponse
import com.example.stockaidemo.model.Stock
import com.example.stockaidemo.model.formatPriceValue
import com.tencent.kuikly.core.module.NetworkModule
import com.tencent.kuikly.core.nvi.serialization.json.JSONArray
import com.tencent.kuikly.core.nvi.serialization.json.JSONObject

/**
 * Task 2 的 AI 服务层。页面只负责展示和交互，网络协议与降级策略集中在这里。
 */
object AIChatEngine {

    fun checkAvailability(networkModule: NetworkModule, callback: (Boolean) -> Unit) {
        networkModule.httpRequest(
            url = AIConfig.proxyUrl.replace("/analyze", "/health"),
            isPost = false,
            param = JSONObject(),
            timeout = 3,
            responseCallback = { data: JSONObject, success: Boolean, _: String, _ ->
                callback(success && data.optBoolean("ok") && data.optBoolean("configured"))
            }
        )
    }

    fun ask(
        networkModule: NetworkModule,
        question: String,
        history: String,
        callback: (ChatAIResponse) -> Unit
    ) {
        val body = buildRequestBody(question, history)

        networkModule.httpRequest(
            url = AIConfig.proxyUrl.replace("/analyze", "/chat"),
            isPost = true,
            param = body,
            headers = JSONObject().apply {
                put("Content-Type", "application/json")
            },
            timeout = 12,
            responseCallback = { data: JSONObject, success: Boolean, networkError: String, _ ->
                val onlineResult = if (success && data.optBoolean("ok")) {
                    parseOnlineResponse(data, question)
                } else {
                    null
                }
                val serviceError = data.optString("error").ifEmpty { networkError }
                callback(onlineResult ?: localFallback(question, history, serviceError))
            }
        )
    }

    /** Gemini 真流式生成；Kuikly 端通过长轮询接收增量片段，保持跨端通用。 */
    fun askStreaming(
        networkModule: NetworkModule,
        question: String,
        history: String,
        onPartial: (String) -> Unit,
        callback: (ChatAIResponse) -> Unit
    ) {
        networkModule.httpRequest(
            url = AIConfig.proxyUrl.replace("/analyze", "/chat-stream/start"),
            isPost = true,
            param = buildRequestBody(question, history),
            headers = JSONObject().apply { put("Content-Type", "application/json") },
            timeout = 8,
            responseCallback = { data: JSONObject, success: Boolean, _: String, _ ->
                val streamId = data.optString("id")
                if (!success || !data.optBoolean("ok") || streamId.isEmpty()) {
                    ask(networkModule, question, history, callback)
                } else {
                    pollStream(networkModule, streamId, 0, question, history, onPartial, callback)
                }
            }
        )
    }

    private fun pollStream(
        networkModule: NetworkModule,
        streamId: String,
        revision: Int,
        question: String,
        history: String,
        onPartial: (String) -> Unit,
        callback: (ChatAIResponse) -> Unit
    ) {
        val url = AIConfig.proxyUrl.replace(
            "/analyze",
            "/chat-stream/poll?id=$streamId&after=$revision"
        )
        networkModule.httpRequest(
            url = url,
            isPost = false,
            param = JSONObject(),
            timeout = 7,
            responseCallback = { data: JSONObject, success: Boolean, networkError: String, _ ->
                if (!success || !data.optBoolean("ok")) {
                    callback(localFallback(question, history, data.optString("error").ifEmpty { networkError }))
                    return@httpRequest
                }
                val partial = data.optString("partial")
                if (partial.isNotEmpty()) onPartial(partial)
                if (isStreamComplete(data)) {
                    val result = data.optJSONObject("result")
                    if (result != null && result.optBoolean("ok") && data.optString("error").isEmpty()) {
                        callback(parseOnlineResponse(result, question, streamed = true))
                    } else {
                        callback(localFallback(question, history, data.optString("error")))
                    }
                } else {
                    pollStream(
                        networkModule,
                        streamId,
                        data.optInt("revision", revision),
                        question,
                        history,
                        onPartial,
                        callback
                    )
                }
            }
        )
    }

    private fun buildRequestBody(question: String, history: String): JSONObject {
        val contextStocks = marketStocksFor(question, history)
        val useNewsGrounding = contextStocks.size == 1 && shouldUseNewsGrounding(question)
        val priceHistory = JSONArray()
        contextStocks.forEach { stock ->
            val points = JSONArray()
            stock.history.takeLast(30).forEach { point ->
                points.put(JSONObject().apply {
                    put("date", point.date)
                    put("close", point.close)
                })
            }
            priceHistory.put(JSONObject().apply {
                put("code", stock.code)
                put("name", stock.name)
                put("points", points)
            })
        }
        return JSONObject().apply {
            put("question", question)
            put("history", history)
            put("marketContext", buildMarketContext(question, history))
            put("useNewsGrounding", useNewsGrounding)
            put("newsCompanies", if (useNewsGrounding) contextStocks.joinToString("、") { it.name } else "")
            put("priceHistory", priceHistory)
        }
    }

    internal fun shouldUseNewsGrounding(question: String): Boolean {
        return Regex("分析|走势|趋势|预测|前景|上涨|下跌|涨|跌|新闻|消息|热点|产品|发布|原因|为什么|利好|利空|对比|比较")
            .containsMatchIn(question)
    }

    internal fun parseOnlineResponse(
        data: JSONObject,
        question: String,
        streamed: Boolean = false
    ): ChatAIResponse {
        val namedStocks = namedStocks(question)
        val comparisonIntent = isComparisonIntent(question)
        val multiStockComparison = namedStocks.size >= 2
        val comparisonCodes = if (multiStockComparison) {
            // The model may omit later symbols. The deterministic entity resolver
            // is authoritative for which explicitly named stocks the UI renders.
            namedStocks.joinToString(",") { it.code }
        } else data.optString("comparisonCodes").ifEmpty {
            if (namedStocks.size >= 2) namedStocks.joinToString(",") { it.code } else ""
        }
        val resolvedStockCode = data.optString("stockCode").ifEmpty {
            if (namedStocks.size == 1) namedStocks.first().code else ""
        }
        // Two or more explicitly named stocks always use the unified comparison
        // pipeline. Risk wording changes the conclusion, not the card type.
        val riskRanking = isRiskRankingIntent(question) && !multiStockComparison
        val markdown = sanitizeModelMarkdown(data.optString("markdown")).let {
            if (namedStocks.isNotEmpty()) correctFalseMissingData(it, question) else it
        }
        return ChatAIResponse(
            markdown = markdown,
            source = data.optString("provider", "AI") + " · " + data.optString("model", "model") +
                (if (streamed) " · 流式" else "") +
                (if (data.optBoolean("newsGrounded")) " · 事件信号" else ""),
            stockCode = resolvedStockCode,
            comparisonCodes = comparisonCodes,
            insightTitle = data.optString("insightTitle"),
            insightSummary = data.optString("insightSummary"),
            action = data.optString("action"),
            riskLevel = data.optString("riskLevel"),
            showStockCard = data.optBoolean("showStockCard") || explicitlyNamedStockCount(question) == 1,
            showComparisonCard = multiStockComparison || (!riskRanking &&
                data.optBoolean("showComparisonCard") && comparisonCodes.isNotEmpty()),
            showRiskRankingCard = riskRanking
        )
    }

    internal fun buildMarketContext(question: String = "", history: String = ""): String {
        return marketStocksFor(question, history).joinToString("；") { stock ->
            val periodChange = stock.historyChangePercent(20)
            val window = stock.historyWindow(20)
            val rangeLow = window.minOfOrNull { it.close } ?: stock.low
            val rangeHigh = window.maxOfOrNull { it.close } ?: stock.high
            val volumeSignal = stock.volumeRatio(20)?.let {
                " 当前量/20日均量" + formatPriceValue(it) + "倍"
            } ?: " 当前成交量${stock.volume}（历史均量不足，不作量比判断）"
            stock.name + "(" + stock.code + ") 现价" + stock.formatPrice() +
                " 涨跌" + stock.formatChange() + " 最高" + stock.high +
                " 最低" + stock.low + " 20日涨跌" + formatPriceValue(periodChange) + "%" +
                " 20日区间" + formatPriceValue(rangeLow) + "-" + formatPriceValue(rangeHigh) +
                " 最大回撤" + formatPriceValue(stock.maxDrawdownPercent(20)) + "%" +
                " 日收益波动率" + formatPriceValue(stock.historicalVolatilityPercent(20)) + "%" +
                " 支撑" + formatPriceValue(stock.recentSupportLevel()) +
                " 压力" + formatPriceValue(stock.recentResistanceLevel()) + volumeSignal +
                " 数据源" + stock.dataSource + " 行情时间" + stock.displayUpdatedAt() +
                "。注意：以上是已计算指标，不要在回答中逐日罗列历史价格"
        }
    }

    /**
     * Portfolio questions are strictly scoped to the current watchlist. Stocks
     * fetched temporarily for another conversation (including removed stocks)
     * must never leak into the home-page market radar or today's interpretation.
     */
    internal fun marketStocksFor(question: String, history: String = ""): List<Stock> {
        val explicitlyNamed = namedStocks(question)
        if (explicitlyNamed.isNotEmpty()) return explicitlyNamed
        if (question.contains("自选") || isRiskRankingIntent(question)) {
            return StockRepository.getStockList()
        }
        val referencedByHistory = StockRepository.getAllKnownStocks().filter { stock ->
            history.contains(stock.name) || history.contains(stock.code) || history.contains(normalizedStockName(stock.name))
        }
        return referencedByHistory.ifEmpty { StockRepository.getStockList() }
    }

    internal fun explicitlyNamedStockCount(question: String): Int = namedStocks(question).size

    internal fun namedStocks(question: String): List<Stock> {
        val nameCandidates = MarketDataEngine.stockNameCandidates(question)
            .map(MarketDataEngine::normalizeSearchToken)
            .filter { it.length >= 2 }
        return StockRepository.getAllKnownStocks().filter { stock ->
            val normalizedName = MarketDataEngine.normalizeSearchToken(stock.name)
            question.contains(stock.name) || question.contains(stock.code) ||
                question.contains(normalizedStockName(stock.name)) ||
                nameCandidates.any { candidate -> normalizedName.contains(candidate) }
        }
    }

    internal fun normalizedStockName(name: String): String = name
        .replace(Regex("[-－]?(W|B|S|SW)$", RegexOption.IGNORE_CASE), "")
        .removeSuffix("r")
        .trim()

    internal fun stripUnnecessaryApology(markdown: String): String = markdown
        .replace("\n非常抱歉，", "\n")
        .replace("\n非常抱歉， ", "\n")
        .replace("\n抱歉，", "\n")
        .removePrefix("非常抱歉，")
        .removePrefix("抱歉，")

    internal fun sanitizeModelMarkdown(markdown: String): String {
        val internalField = Regex(
            "(?im)^\\s*[-*#]*\\s*(insightTitle|insightSummary|showStockCard|showComparisonCard|" +
                "comparisonCodes|stockCode|riskLevel|action)\\s*[:：].*$"
        )
        val rawDailyPrice = Regex("(?m)^\\s*[-*]?\\s*20\\d{2}[-/]\\d{2}[-/]\\d{2}\\s*[:：].*$")
        return stripUnnecessaryApology(markdown)
            .replace(internalField, "")
            .replace(rawDailyPrice, "")
            .replace(Regex("\\n{3,}"), "\n\n")
            .trim()
    }

    /**
     * The locally resolved market data is authoritative. If the language model
     * still claims that a resolved stock or its history is unavailable, replace
     * that contradiction with a concise deterministic summary from the data that
     * was just fetched on demand.
     */
    internal fun correctFalseMissingData(markdown: String, question: String): String {
        val resolved = namedStocks(question)
        if (resolved.isEmpty() || resolved.any { it.history.size < 5 }) return markdown
        val compact = markdown.replace(" ", "")
        val contradictsData = compact.contains("仅有最新快照") ||
            compact.contains("无法完整比较") || compact.contains("无法进行全面") ||
            resolved.any { stock ->
                listOf("不包含${stock.name}", "${stock.name}暂无", "缺少${stock.name}", "没有${stock.name}").any(compact::contains)
            }
        if (!contradictsData) return markdown

        val rows = resolved.joinToString("\n") { stock ->
            val dailySign = if (stock.changePercent >= 0) "+" else ""
            val periodChange = stock.historyChangePercent(20)
            val periodSign = if (periodChange >= 0) "+" else ""
            "- **${stock.name}(${stock.code})**：现价${stock.formatPrice()}，当日$dailySign${formatPriceValue(stock.changePercent)}%，20日$periodSign${formatPriceValue(periodChange)}%，${stock.riskLabel()}"
        }
        return "### ${if (resolved.size == 1) resolved.first().name + "最新行情" else resolved.size.toString() + "只股票行情对比"}\n" +
            "$rows\n\n以上数据已按需查询，无需先加入自选。"
    }

    internal fun isComparisonIntent(question: String): Boolean {
        return listOf("对比", "比较", "哪只", "哪个好", "留哪个", "vs").any {
            question.lowercase().contains(it)
        }
    }

    internal fun isRiskRankingIntent(question: String): Boolean {
        val normalized = question.lowercase()
        return listOf("风险排行", "风险排名", "风险最高", "风险最大", "最危险", "risk ranking").any {
            normalized.contains(it)
        }
    }

    internal fun isStreamComplete(data: JSONObject): Boolean = data.optBoolean("done")

    internal fun localFallback(question: String, history: String, serviceError: String = ""): ChatAIResponse {
        val quotaLimited = isQuotaError(serviceError)
        val fallbackSource = when {
            quotaLimited -> "Gemini 额度受限 · 本地行情兜底"
            serviceError.isNotEmpty() -> "在线请求失败 · 本地行情兜底"
            else -> "本地知识库 · 离线兜底"
        }
        val compared = namedStocks(question)
        val multiStockComparison = compared.size >= 2
        val riskRanking = isRiskRankingIntent(question) && !multiStockComparison
        val explicitStock = StockRepository.getAllKnownStocks().firstOrNull {
            question.contains(it.name) || question.contains(it.code)
        }
        val stock = explicitStock ?: StockRepository.getAllKnownStocks().firstOrNull {
            history.contains(it.name) || history.contains(it.code)
        }
        if (stock == null) {
            return ChatAIResponse(
                markdown = "### 我能帮你什么\n- 分析 Demo 中的个股走势和风险\n- 对比多只股票的当日表现\n- 解释上一轮判断的依据\n请告诉我股票名称或代码。",
                source = fallbackSource,
                stockCode = "",
                comparisonCodes = compared.joinToString(",") { it.code },
                insightTitle = "",
                insightSummary = "",
                action = "",
                riskLevel = "",
                showStockCard = false,
                showComparisonCard = multiStockComparison,
                showRiskRankingCard = riskRanking
            )
        }
        val direction = if (stock.changePercent > 1.0) "动能偏强" else if (stock.changePercent < -1.0) "短线承压" else "震荡整理"
        val periodChange = stock.historyChangePercent(20)
        val periodDirection = if (periodChange > 1.0) "偏强" else if (periodChange < -1.0) "偏弱" else "横盘"
        val closes = stock.history.takeLast(20).map { it.close }
        val support = closes.minOrNull() ?: stock.low
        val resistance = closes.maxOrNull() ?: stock.high
        val drawdown = stock.maxDrawdownPercent(20)
        val volatility = stock.historicalVolatilityPercent(20)
        val volumeRatio = stock.volumeRatio(20)
        val volumeText = volumeRatio?.takeIf { it > 0.0 }?.let {
            "，当前成交量约为20日均量的${formatPriceValue(it)}倍"
        } ?: ""
        val periodSign = if (periodChange >= 0) "+" else ""
        val conditionalInsight = "若放量突破${formatPriceValue(resistance)}，趋势改善的可信度上升；" +
            "若跌破${formatPriceValue(support)}，需警惕弱势延续。"
        val isExplanation = question.lowercase().contains("explain") ||
            question.contains("解释") || question.contains("为什么") || question.contains("依据")
        val markdown = if (isExplanation) {
            "### 判断依据\n" +
                "- **20日趋势**：$periodDirection，区间累计$periodSign${formatPriceValue(periodChange)}%，最大回撤${formatPriceValue(drawdown)}%\n" +
                "- **价格位置**：现价${stock.formatPrice()}，20日支撑约${formatPriceValue(support)}、压力约${formatPriceValue(resistance)}\n" +
                "- **波动与量价**：历史波动率${formatPriceValue(volatility)}%$volumeText\n" +
                "- **条件观察**：$conditionalInsight"
        } else {
            "### ${stock.name}本地行情研判\n" +
                "- **20日表现**：$periodDirection，累计$periodSign${formatPriceValue(periodChange)}%，当前$direction\n" +
                "- **关键位置**：支撑约${formatPriceValue(support)}，压力约${formatPriceValue(resistance)}\n" +
                "- **风险刻画**：最大回撤${formatPriceValue(drawdown)}%，历史波动率${formatPriceValue(volatility)}%$volumeText\n" +
                "- **条件观察**：$conditionalInsight"
        }
        return ChatAIResponse(
            markdown = markdown,
            source = fallbackSource,
            stockCode = stock.code,
            comparisonCodes = compared.joinToString(",") { it.code },
            insightTitle = "${stock.name} · ${direction}",
            insightSummary = "20日$periodDirection，支撑${formatPriceValue(support)}、压力${formatPriceValue(resistance)}。$conditionalInsight",
            action = "关注",
            riskLevel = "中风险",
            showStockCard = explicitStock != null && compared.size < 2,
            showComparisonCard = multiStockComparison,
            showRiskRankingCard = riskRanking
        )
    }

    internal fun isQuotaError(error: String): Boolean {
        val normalized = error.lowercase()
        return normalized.contains("quota") || normalized.contains("rate limit") ||
            normalized.contains("resource_exhausted") || normalized.contains("429")
    }
}
