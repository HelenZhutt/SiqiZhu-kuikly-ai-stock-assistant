package com.example.stockaidemo

import com.example.stockaidemo.data.AIChatEngine
import com.example.stockaidemo.data.MarketDataEngine
import com.example.stockaidemo.data.StockRepository
import com.example.stockaidemo.model.Stock
import com.example.stockaidemo.model.PricePoint
import com.example.stockaidemo.model.formatPriceValue
import com.example.stockaidemo.model.buildMarketPulse
import com.example.stockaidemo.model.aiSignalLabel
import com.example.stockaidemo.model.historyDateTicks
import com.tencent.kuikly.core.nvi.serialization.json.JSONArray
import com.tencent.kuikly.core.nvi.serialization.json.JSONObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StockDomainTest {
    private fun stock(
        code: String = "TEST",
        price: Double = 10.0,
        change: Double = 0.5,
        high: Double = 11.0,
        low: Double = 9.0
    ) = Stock(code, "测试股", price, change, 5.0, high, low, "1.0万手")

    @Test
    fun formatsPositivePriceToTwoDecimals() {
        assertEquals("12.30", formatPriceValue(12.3))
    }

    @Test
    fun formatsNegativePriceWithoutLosingSign() {
        assertEquals("-0.73", formatPriceValue(-0.729))
    }

    @Test
    fun calculatesIntradayAmplitudeFromPreviousClose() {
        assertEquals(21.0526, stock().amplitudePercent(), 0.001)
    }

    @Test
    fun clampsPricePositionInsideDayRange() {
        assertEquals(50.0, stock().pricePositionPercent(), 0.001)
        assertEquals(100.0, stock(price = 12.0).pricePositionPercent(), 0.001)
    }

    @Test
    fun classifiesVolatilityRisk() {
        assertEquals("低风险", stock(high = 10.05, low = 9.95).riskLabel())
        assertEquals("高风险", stock(high = 11.0, low = 9.0).riskLabel())
    }

    @Test
    fun mergesFreshQuotesWithoutDroppingCachedStocks() {
        val oldA = stock("A", price = 10.0)
        val oldB = stock("B", price = 20.0)
        val merged = StockRepository.mergeStocks(listOf(oldA, oldB), listOf(stock("A", price = 12.0)))
        assertEquals(12.0, merged[0].price)
        assertEquals(oldB, merged[1])
        val withNewSearchResult = StockRepository.mergeStocks(listOf(oldA), listOf(stock("C", price = 30.0)))
        assertEquals(listOf("A", "C"), withNewSearchResult.map { it.code })
    }

    @Test
    fun recognizesComparisonIntentAndNamedStocks() {
        assertTrue(AIChatEngine.isComparisonIntent("对比平安银行和贵州茅台"))
        assertEquals(2, AIChatEngine.explicitlyNamedStockCount("平安银行和贵州茅台哪个好"))
        assertEquals(3, AIChatEngine.explicitlyNamedStockCount("比较平安银行、贵州茅台和宁德时代"))
        assertFalse(AIChatEngine.isComparisonIntent("解释平安银行"))
    }

    @Test
    fun comparisonUiKeepsEveryExplicitlyNamedStock() {
        val response = AIChatEngine.parseOnlineResponse(JSONObject().apply {
            put("markdown", "三股对比")
            put("showComparisonCard", true)
            put("comparisonCodes", "000001,600519")
        }, "比较平安银行、贵州茅台和宁德时代")

        assertEquals("000001,600519,300750", response.comparisonCodes)
        assertTrue(response.showComparisonCard)
    }

    @Test
    fun parsesQuoteJsonAndHistoricalCloseSeries() {
        val history = JSONArray().apply {
            put(JSONObject().apply { put("date", "2026-09-08"); put("close", 11.78) })
            put(JSONObject().apply { put("date", "2026-09-09"); put("close", 11.70) })
        }
        val payload = JSONObject().apply {
            put("source", "测试行情")
            put("stocks", JSONArray().apply {
                put(JSONObject().apply {
                    put("code", "000001"); put("name", "平安银行"); put("price", 11.70)
                    put("changeAmount", -0.08); put("changePercent", -0.68)
                    put("high", 11.79); put("low", 11.69); put("volume", "58.2万手")
                    put("updatedAt", "20260909150000"); put("history", history)
                })
            })
        }
        val parsed = MarketDataEngine.parseMarketResponse(payload)
        assertEquals(1, parsed.size)
        assertEquals(2, parsed.first().history.size)
        assertEquals(11.70, parsed.first().history.last().close)
    }

    @Test
    fun summarizesResearchMetricsWithoutDumpingDailyPrices() {
        val history = (1..20).map { day ->
            PricePoint("2026-09-${day.toString().padStart(2, '0')}", 10.0 + day / 10.0, 1000.0 + day)
        }
        StockRepository.cacheTransientStocks(listOf(stock("METRIC").copy(
            name = "指标测试股", history = history, volumeValue = 1500.0
        )))
        val context = AIChatEngine.buildMarketContext("分析指标测试股")
        assertTrue(context.contains("20日涨跌"))
        assertTrue(context.contains("最大回撤"))
        assertTrue(context.contains("当前量/20日均量"))
        assertFalse(context.contains("2026-09-01:"))
    }

    @Test
    fun stripsSchemaFieldsAndRawDailyRowsFromModelCopy() {
        val sanitized = AIChatEngine.sanitizeModelMarkdown(
            "### 研判\ninsightTitle: 内部标题\n- 2026-09-01: 75.10\n**趋势**：偏弱"
        )
        assertFalse(sanitized.contains("insightTitle"))
        assertFalse(sanitized.contains("2026-09-01"))
        assertTrue(sanitized.contains("趋势"))
    }

    @Test
    fun computesDrawdownVolatilityAndVolumeRatio() {
        val sample = stock().copy(
            volumeValue = 200.0,
            history = listOf(
                PricePoint("1", 10.0, 100.0), PricePoint("2", 12.0, 100.0),
                PricePoint("3", 9.0, 100.0), PricePoint("4", 10.0, 100.0),
                PricePoint("5", 11.0, 100.0)
            )
        )
        assertEquals(25.0, sample.maxDrawdownPercent(), 0.001)
        assertTrue(sample.historicalVolatilityPercent() > 0.0)
        assertEquals(2.0, sample.volumeRatio() ?: 0.0, 0.001)
    }

    @Test
    fun parsesSupportedStockSearchResultsAndRejectsUnsupportedMarkets() {
        val payload = JSONObject().apply {
            put("results", JSONArray().apply {
                put(JSONObject().apply { put("symbol", "sh600036"); put("code", "600036"); put("name", "招商银行"); put("market", "SH") })
                put(JSONObject().apply { put("symbol", "usaapl"); put("code", "AAPL"); put("name", "苹果"); put("market", "US") })
            })
        }
        val results = MarketDataEngine.parseSearchResponse(payload)
        assertEquals(1, results.size)
        assertEquals("招商银行", results.first().name)
        assertTrue(MarketDataEngine.isSupportedSymbol("hk03968"))
        assertFalse(MarketDataEngine.isSupportedSymbol("usaapl"))
    }

    @Test
    fun searchesCachedStocksByNameCodeAndMarketSymbol() {
        val stocks = listOf(
            stock("600031").copy(name = "三一重工", marketSymbol = "sh600031"),
            stock("00700").copy(name = "腾讯控股", marketSymbol = "hk00700")
        )

        assertEquals(listOf("600031"), MarketDataEngine.searchCachedStocks("三一", stocks).map { it.code })
        assertEquals(listOf("00700"), MarketDataEngine.searchCachedStocks("007", stocks).map { it.code })
        assertEquals(listOf("600031"), MarketDataEngine.searchCachedStocks("SH600", stocks).map { it.code })
    }

    @Test
    fun searchNormalizationDoesNotRequireListedShareSuffixes() {
        assertEquals("宇数科技", MarketDataEngine.normalizeSearchToken("宇数科技-W"))
        assertEquals("yskj", MarketDataEngine.normalizeSearchToken("YSKJ-W"))
        assertEquals(
            listOf("yskj", "yskjw", "yskjsw", "yskjb", "yskjs"),
            MarketDataEngine.searchQueryVariants("YSKJ")
        )
        assertEquals(
            listOf("yskjw", "yskj", "yskjsw", "yskjb", "yskjs"),
            MarketDataEngine.searchQueryVariants("YSKJW")
        )
        assertEquals(listOf("宇数科技"), MarketDataEngine.searchQueryVariants("宇数科技"))
        assertEquals("小鹏集团", AIChatEngine.normalizedStockName("小鹏集团w"))
        val result = com.example.stockaidemo.model.StockSearchResult(
            symbol = "hk09868", code = "09868", name = "小鹏集团w", market = "HK", pinyin = "xpjtw"
        )
        assertTrue(MarketDataEngine.resultMatchesTerm(result, "小鹏"))
        StockRepository.cacheTransientStocks(
            listOf(stock("09868").copy(name = "小鹏集团w", marketSymbol = "hk09868"))
        )
        assertEquals("09868", AIChatEngine.namedStocks("对比美团和小鹏").first { it.name.contains("小鹏") }.code)
    }

    @Test
    fun enablesNewsGroundingForResearchQuestionsOnly() {
        assertTrue(AIChatEngine.shouldUseNewsGrounding("分析小鹏集团的走势和近期新闻"))
        assertTrue(AIChatEngine.shouldUseNewsGrounding("为什么这只股票上涨"))
        assertFalse(AIChatEngine.shouldUseNewsGrounding("你好"))
    }

    @Test
    fun formatsCompactAndSeparatedQuoteTimes() {
        assertEquals("09-10 16:14", stock().copy(updatedAt = "20260910161441").displayUpdatedAt())
        assertEquals("09/10 16:08", stock().copy(updatedAt = "2026/09/10 16:08:52").displayUpdatedAt())
    }

    @Test
    fun recognizesStreamingCompletionOnlyWhenDone() {
        assertFalse(AIChatEngine.isStreamComplete(JSONObject().apply { put("done", false) }))
        assertTrue(AIChatEngine.isStreamComplete(JSONObject().apply { put("done", true) }))
    }

    @Test
    fun offlineFallbackStillReturnsUsefulStructuredAnswer() {
        val answer = AIChatEngine.localFallback("解释平安银行的风险", "")
        assertTrue(answer.markdown.contains("判断依据"))
        assertEquals("000001", answer.stockCode)
        assertTrue(answer.showStockCard)
        assertTrue(answer.source.contains("离线兜底"))
    }

    @Test
    fun quotaFailureIsNotMisreportedAsOffline() {
        val answer = AIChatEngine.localFallback(
            "分析平安银行",
            "",
            "You exceeded your current quota. Please check your plan and billing details."
        )
        assertTrue(AIChatEngine.isQuotaError("429 RESOURCE_EXHAUSTED"))
        assertTrue(answer.source.contains("额度受限"))
        assertFalse(answer.markdown.contains("离线分析"))
    }

    @Test
    fun demoSnapshotIncludesChartableHistoryWithoutLiveQuotes() {
        val stocks = StockRepository.getStockList()
        assertEquals(4, stocks.size)
        assertTrue(stocks.all { it.history.size >= 20 })
        assertTrue(stocks.all { kotlin.math.abs(it.history.last().close - it.price) < 0.02 })
        val cached = MarketDataEngine.cachedStocksForSymbols(
            listOf("sz000001", "sh600519", "sz300750", "hk00700"),
            requireHistory = true
        )
        assertEquals(listOf("000001", "600519", "300750", "00700"), cached.map { it.code })
    }

    @Test
    fun historicalWindowUsesNewestAvailablePoints() {
        val history = (1..20).map { PricePoint("09-${it.toString().padStart(2, '0')}", it.toDouble()) }
        val sample = stock().copy(history = history)
        assertEquals((16..20).map { it.toDouble() }, sample.historyWindow(5).map { it.close })
        assertEquals(20, sample.historyWindow(20).size)
    }

    @Test
    fun calculatesHistoricalWindowMovement() {
        val history = listOf(PricePoint("09-01", 10.0), PricePoint("09-02", 11.0), PricePoint("09-03", 12.0))
        assertEquals(20.0, stock().copy(history = history).historyChangePercent(3), 0.001)
        assertEquals(0.0, stock().copy(history = history).historyChangePercent(1), 0.001)
    }

    @Test
    fun marketPulseIsGroundedInLoadedQuotes() {
        val pulse = buildMarketPulse(listOf(
            stock(code = "A", change = 1.0).copy(name = "领涨股", changePercent = 5.0),
            stock(code = "B", change = -0.5).copy(name = "回调股", changePercent = -2.0)
        ))
        assertTrue(pulse.contains("领涨股领涨"))
        assertTrue(pulse.contains("回调股回调"))
        assertTrue(pulse.contains("1 涨 1 跌"))
    }

    @Test
    fun recognizesRiskRankingIntent() {
        assertTrue(AIChatEngine.isRiskRankingIntent("当前风险最高的是谁？请做风险排行"))
        assertFalse(AIChatEngine.isRiskRankingIntent("解释平安银行的风险"))
        assertTrue(AIChatEngine.localFallback("自选股风险排名", "").showRiskRankingCard)
    }

    @Test
    fun explicitlyNamedStocksTakeComparisonPrecedenceOverRiskRanking() {
        val question = "比较平安银行和贵州茅台，哪只风险最高"
        val response = AIChatEngine.parseOnlineResponse(
            JSONObject().apply { put("markdown", "### 对比结论\n已完成") },
            question
        )

        assertTrue(response.showComparisonCard)
        assertFalse(response.showRiskRankingCard)
        assertEquals(listOf("000001", "600519"), AIChatEngine.marketStocksFor(question).map { it.code })
    }

    @Test
    fun aiSignalDoesNotReuseQuoteDirectionLabels() {
        assertEquals("偏强", aiSignalLabel("买入"))
        assertEquals("偏弱", aiSignalLabel("卖出"))
        assertEquals("关注", aiSignalLabel("观望"))
    }

    @Test
    fun derivesKeyLevelsFromRecentLocalExtrema() {
        val sample = stock(price = 10.4, high = 10.8, low = 10.1).copy(history = listOf(
            PricePoint("09-01", 10.0), PricePoint("09-02", 10.6), PricePoint("09-03", 10.2),
            PricePoint("09-04", 10.9), PricePoint("09-05", 10.3), PricePoint("09-06", 10.8)
        ))
        assertEquals(10.3, sample.recentSupportLevel(), 0.001)
        assertEquals(10.6, sample.recentResistanceLevel(), 0.001)
    }

    @Test
    fun createsSparseDateTicksForFiveAndTwentyDayCharts() {
        val history = (1..20).map { PricePoint("09-${it.toString().padStart(2, '0')}", it.toDouble()) }
        val sample = stock().copy(history = history)
        assertEquals(listOf("09-16", "09-18", "09-20"), sample.historyDateTicks(5))
        assertEquals(4, sample.historyDateTicks(20).size)
    }

    @Test
    fun resolvesExplicitMarketSymbolsWithoutAddingWatchlistState() {
        assertEquals(listOf("sh600031", "hk00700"), MarketDataEngine.explicitSymbols("对比三一重工600031和腾讯00700"))
        assertEquals("sz300750", MarketDataEngine.marketSymbolForCode("300750"))
        assertTrue(MarketDataEngine.stockNameCandidates("对比三一重工和紫金矿业").containsAll(listOf("三一重工", "紫金矿业")))
        assertEquals(listOf("美团"), MarketDataEngine.stockNameCandidates("分析一下美团"))
        assertEquals(listOf("美图公司"), MarketDataEngine.stockNameCandidates("查询美图公司"))
        assertEquals(
            listOf("美图公司"),
            MarketDataEngine.stockNameCandidates("分析美图公司最近20日的趋势、风险和关键观察点。")
        )
        assertEquals("美团", AIChatEngine.normalizedStockName("美团-W"))
        assertEquals("### 美团行情\n现价数据已载入", AIChatEngine.stripUnnecessaryApology("### 美团行情\n非常抱歉，现价数据已载入"))
    }

    @Test
    fun keepsUnknownStockCandidateWhenQuestionAlsoContainsKnownStocks() {
        val known = listOf(
            stock(code = "000001").copy(name = "平安银行"),
            stock(code = "600519").copy(name = "贵州茅台")
        )

        assertEquals(
            listOf("招商银行"),
            MarketDataEngine.unresolvedStockNameCandidates(
                "比较平安银行、招商银行和贵州茅台最近20日走势",
                known
            )
        )
    }

    @Test
    fun portfolioContextExcludesTemporarilyQueriedStocks() {
        StockRepository.cacheTransientStocks(
            listOf(stock(code = "01357").copy(name = "美图公司", marketSymbol = "hk01357"))
        )

        val portfolio = AIChatEngine.marketStocksFor("解读今天的自选股整体表现")
        assertFalse(portfolio.any { it.code == "01357" })
        assertEquals(
            listOf("01357"),
            AIChatEngine.marketStocksFor("查询美图公司").map { it.code }
        )
    }

    @Test
    fun genericQuestionDoesNotInheritUnrelatedStockContext() {
        val history = "用户：分析平安银行\n助手：平安银行短期震荡"

        assertTrue(AIChatEngine.marketStocksFor("随便返回一点东西", history).isEmpty())
        assertEquals("", AIChatEngine.buildMarketContext("随便返回一点东西", history))

        val fallback = AIChatEngine.localFallback("随便返回一点东西", history, "network error")
        assertEquals("", fallback.stockCode)
        assertFalse(fallback.showStockCard)
        assertFalse(fallback.markdown.contains("条件观察"))
        assertTrue(fallback.markdown.contains("随便返回一点东西"))
    }

    @Test
    fun explicitFollowUpCanReuseTheMostRecentStockContext() {
        val history = "用户：分析贵州茅台\n助手：贵州茅台震荡\n用户：分析平安银行\n助手：平安银行承压"

        assertEquals(
            listOf("000001"),
            AIChatEngine.marketStocksFor("它为什么下跌？", history).map { it.code }
        )
    }

    @Test
    fun genericOnlineResponseCannotForceAnUnrelatedStockCard() {
        val response = AIChatEngine.parseOnlineResponse(
            JSONObject().apply {
                put("markdown", "直接回答普通问题")
                put("stockCode", "000001")
                put("showStockCard", true)
            },
            "随便返回一点东西",
            history = "用户：分析平安银行"
        )

        assertEquals("", response.stockCode)
        assertFalse(response.showStockCard)
    }
}
