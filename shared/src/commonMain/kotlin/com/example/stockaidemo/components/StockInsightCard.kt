package com.example.stockaidemo.components

import com.example.stockaidemo.model.ChatMessage
import com.example.stockaidemo.model.Stock
import com.example.stockaidemo.model.formatPriceValue
import com.example.stockaidemo.model.aiSignalLabel
import com.example.stockaidemo.model.historyDateTicks
import com.tencent.kuikly.core.base.Color
import com.tencent.kuikly.core.base.ViewContainer
import com.tencent.kuikly.core.layout.FlexPositionType
import com.tencent.kuikly.core.views.Canvas
import com.tencent.kuikly.core.views.Text
import com.tencent.kuikly.core.views.View

fun ViewContainer<*, *>.PriceHistoryChart(
    stock: Stock,
    width: Float,
    height: Float = 64f,
    dayCount: Int = 20,
    supportLevel: Double? = null,
    resistanceLevel: Double? = null
) {
    val points = stock.historyWindow(dayCount)
    val referenceLevels = listOfNotNull(supportLevel, resistanceLevel).filter { it > 0.0 }
    val values = points.map { it.close } + referenceLevels
    val minValue = values.minOrNull() ?: 0.0
    val maxValue = values.maxOrNull() ?: minValue
    val range = (maxValue - minValue).takeIf { it > 0.0 } ?: 1.0
    fun levelY(level: Double): Float = 5f + ((maxValue - level) / range * (height - 10f)).toFloat()
    val labelsAreClose = supportLevel != null && resistanceLevel != null &&
        kotlin.math.abs(levelY(supportLevel) - levelY(resistanceLevel)) < 15f
    View {
        attr { width(width); marginTop(10f); height(height + if (referenceLevels.isEmpty()) 27f else 43f) }
        if (points.size >= 2) {
            View {
                attr { width(width); height(height) }
                Canvas({ attr { height(height); width(width) } }) { canvas, canvasWidth, canvasHeight ->
                    val usableHeight = canvasHeight - 10f
                    val step = (canvasWidth - 8f) / (points.size - 1)

                    referenceLevels.forEachIndexed { index, level ->
                        val y = 5f + ((maxValue - level) / range * usableHeight).toFloat()
                        canvas.beginPath(); canvas.moveTo(4f, y); canvas.lineTo(canvasWidth - 4f, y)
                        canvas.strokeStyle(if (index == 0) Color(0xFF10B981) else Color(0xFFF59E0B))
                        canvas.lineWidth(1f); canvas.stroke()
                    }

                    canvas.beginPath()
                    points.forEachIndexed { index, point ->
                        val x = 4f + index * step
                        val y = 5f + ((maxValue - point.close) / range * usableHeight).toFloat()
                        if (index == 0) canvas.moveTo(x, y) else canvas.lineTo(x, y)
                    }
                    canvas.strokeStyle(if (stock.isUp) Color(0xFF2563EB) else Color(0xFF10B981))
                    canvas.lineWidth(2.5f); canvas.lineCapRound(); canvas.stroke()
                }
                if (supportLevel != null) Text {
                    attr {
                        text("支撑 ${formatPriceValue(supportLevel)}"); fontSize(8f); color(Color(0xFF059669))
                        positionType(FlexPositionType.ABSOLUTE); right(3f)
                        top((levelY(supportLevel) + if (labelsAreClose) 2f else -7f).coerceIn(0f, height - 12f))
                        backgroundColor(Color.WHITE)
                    }
                }
                if (resistanceLevel != null) Text {
                    attr {
                        text("压力 ${formatPriceValue(resistanceLevel)}"); fontSize(8f); color(Color(0xFFD97706))
                        positionType(FlexPositionType.ABSOLUTE); right(3f)
                        top((levelY(resistanceLevel) + if (labelsAreClose) -14f else -7f).coerceIn(0f, height - 12f))
                        backgroundColor(Color.WHITE)
                    }
                }
            }
            View {
                attr { width(width); flexDirectionRow(); justifyContentSpaceBetween(); marginTop(2f) }
                stock.historyDateTicks(dayCount).forEach { date ->
                    Text { attr { text(date); fontSize(8f); color(Color(0xFF94A3B8)) } }
                }
            }
            if (supportLevel != null && resistanceLevel != null) {
                Text {
                    attr {
                        text("AI 图表联动 · 关键价位来自20日局部高低点")
                        fontSize(9f); marginTop(3f); color(Color(0xFF475569))
                    }
                }
            }
        } else {
            View {
                attr { height(height); allCenter(); backgroundColor(Color(0xFFF8FAFC)); borderRadius(8f) }
                Text { attr { text("刷新真实行情后显示20日折线"); fontSize(10f); color(Color(0xFF94A3B8)) } }
            }
        }
    }
}

/** Compact, label-free chart for each quote row on the watchlist. */
fun ViewContainer<*, *>.MiniPriceSparkline(stock: Stock, width: Float, height: Float = 34f) {
    val points = stock.historyWindow(20)
    View {
        attr { width(width); height(height); allCenter() }
        if (points.size >= 2) {
            Canvas({ attr { width(width); height(height) } }) { canvas, canvasWidth, canvasHeight ->
                val minValue = points.minOf { it.close }
                val maxValue = points.maxOf { it.close }
                val range = (maxValue - minValue).takeIf { it > 0.0 } ?: 1.0
                val step = (canvasWidth - 4f) / (points.size - 1)
                canvas.beginPath()
                points.forEachIndexed { index, point ->
                    val x = 2f + index * step
                    val y = 3f + ((maxValue - point.close) / range * (canvasHeight - 6f)).toFloat()
                    if (index == 0) canvas.moveTo(x, y) else canvas.lineTo(x, y)
                }
                canvas.strokeStyle(if (stock.historyChangePercent(20) >= 0) Color(0xFFF5222D) else Color(0xFF00A870))
                canvas.lineWidth(1.7f); canvas.lineCapRound(); canvas.stroke()
            }
        } else {
            Text { attr { text("等待走势"); fontSize(8f); color(Color(0xFFCBD5E1)) } }
        }
    }
}

fun ViewContainer<*, *>.StockInsightCard(
    message: ChatMessage,
    stock: Stock,
    width: Float,
    actionText: String,
    clickable: Boolean,
    onOpen: () -> Unit
) {
    View {
        attr {
            width(width); marginTop(8f); padding(13f); borderRadius(14f)
            backgroundColor(Color(0xFFFFFBEB))
        }
        if (clickable) event { click { onOpen() } }
        View {
            attr { flexDirectionRow(); justifyContentSpaceBetween(); alignItemsCenter() }
            View {
                attr { flexDirectionColumn() }
                Text { attr { text("${stock.name} · ${stock.code}"); fontSize(14f); fontWeightBold(); color(Color(0xFF78350F)) } }
                Text {
                    attr {
                        text("${stock.formatPrice()}  ${stock.formatChange()}")
                        fontSize(12f); marginTop(4f)
                        color(if (stock.isUp) Color(0xFFDC2626) else Color(0xFF059669))
                    }
                }
            }
            Text { attr { text(actionText); fontSize(11f); color(if (clickable) Color(0xFFD97706) else Color(0xFF94A3B8)) } }
        }
        PriceHistoryChart(stock, width - 26f)
        View {
            attr { flexDirectionRow(); marginTop(7f) }
            InsightBadge(aiSignalLabel(message.action), Color(0xFFDBEAFE), Color(0xFF1D4ED8))
            val risk = message.riskLevel.ifEmpty { stock.riskLabel() }
            InsightBadge(risk, riskBackground(risk), riskForeground(risk))
        }
        Text { attr { text(message.insightTitle.ifEmpty { "AI 结构化洞察" }); fontSize(13f); fontWeightBold(); marginTop(9f); color(Color(0xFF1E293B)) } }
        Text { attr { text(message.insightSummary); fontSize(12f); marginTop(5f); color(Color(0xFF475569)) } }
        Text {
            attr {
                val historyBasis = if (stock.history.isEmpty()) "当前快照" else "${stock.history.size}日收盘价"
                text("来源：${stock.dataSource} · 行情时间：${stock.displayUpdatedAt()}\n依据：最新价、涨跌幅、最高/最低、成交量、$historyBasis")
                fontSize(9f); marginTop(8f); color(Color(0xFF64748B))
            }
        }
    }
}

fun ViewContainer<*, *>.RiskRankingCard(stocks: List<Stock>, width: Float, onOpen: (Stock) -> Unit) {
    val ranked = stocks.sortedByDescending { it.amplitudePercent() }.take(5)
    View {
        attr { width(width); marginTop(8f); padding(13f); borderRadius(14f); backgroundColor(Color(0xFFFFF7ED)) }
        Text { attr { text("AI 波动风险排行"); fontSize(14f); fontWeightBold(); color(Color(0xFF7C2D12)) } }
        Text { attr { text("依据日内振幅排序，点击股票可查看完整详情"); fontSize(10f); marginTop(4f); color(Color(0xFF9A3412)) } }
        ranked.forEachIndexed { index, stock ->
            View {
                attr {
                    flexDirectionRow(); alignItemsCenter(); justifyContentSpaceBetween()
                    paddingTop(10f); paddingBottom(10f)
                }
                event { click { onOpen(stock) } }
                View {
                    attr { flexDirectionRow(); alignItemsCenter() }
                    View {
                        attr { size(24f, 24f); borderRadius(12f); allCenter(); backgroundColor(Color(0xFFFFEDD5)) }
                        Text { attr { text("${index + 1}"); fontSize(10f); fontWeightBold(); color(Color(0xFFEA580C)) } }
                    }
                    View {
                        attr { marginLeft(9f); flexDirectionColumn() }
                        Text { attr { text(stock.name); fontSize(12f); fontWeightBold(); color(Color(0xFF1E293B)) } }
                        Text { attr { text("${stock.code} · ${stock.formatChange()}"); fontSize(9f); marginTop(2f); color(Color(0xFF64748B)) } }
                    }
                }
                View {
                    attr { flexDirectionColumn(); alignItemsFlexEnd() }
                    Text { attr { text(stock.riskLabel()); fontSize(10f); fontWeightBold(); color(riskForeground(stock.riskLabel())) } }
                    Text { attr { text("振幅 ${formatPriceValue(stock.amplitudePercent())}%  ›"); fontSize(9f); marginTop(3f); color(Color(0xFF64748B)) } }
                }
            }
            if (index < ranked.lastIndex) {
                View { attr { height(1f); backgroundColor(Color(0xFFFED7AA)) } }
            }
        }
        Text {
            attr {
                val latest = ranked.maxByOrNull { it.updatedAt }
                text("来源：${latest?.dataSource ?: "当前行情"} · ${latest?.displayUpdatedAt() ?: "快照时间"}\n依据字段：最高价、最低价、昨收估算值")
                fontSize(9f); lineHeight(13f); marginTop(7f); color(Color(0xFF64748B))
            }
        }
    }
}

fun ViewContainer<*, *>.StockComparisonCard(
    stocks: List<Stock>,
    width: Float,
    onOpen: (Stock) -> Unit
) {
    if (stocks.size < 2) return
    val compared = stocks.take(5)
    val chartStocks = compared.take(4)
    val portfolioMode = compared.size >= 3
    val rankingMode = compared.size >= 5
    val innerWidth = width - 26f
    val labelWidth = if (compared.size == 2) 66f else 54f
    val tableStocks = if (rankingMode) emptyList() else compared
    val stockColumnWidth = (innerWidth - labelWidth) / tableStocks.size.coerceAtLeast(1).toFloat()
    val summaryColumnWidth = innerWidth / compared.size.toFloat()
    View {
        attr {
            width(width); marginTop(2f); padding(13f); borderRadius(16f)
            backgroundColor(Color(0xFFF8FAFC))
        }
        View {
            attr { flexDirectionRow(); alignItemsCenter(); justifyContentSpaceBetween() }
            View {
                attr { flexDirectionColumn() }
                Text { attr { text("股票对比"); fontSize(15f); fontWeightBold(); color(Color(0xFF172554)) } }
                Text { attr { text("20日表现与风险画像"); fontSize(9f); marginTop(3f); color(Color(0xFF64748B)) } }
            }
            Text {
                attr {
                    text(when { rankingMode -> "智能排行"; portfolioMode -> "组合对比"; else -> "详细对比" })
                    fontSize(9f); fontWeightBold(); color(Color(0xFF2563EB))
                }
            }
        }

        if (!portfolioMode) {
            View {
                attr {
                    width(innerWidth); marginTop(11f); paddingTop(10f); paddingBottom(10f)
                    flexDirectionRow(); borderRadius(12f); backgroundColor(Color.WHITE)
                }
                compared.forEach { stock ->
                    View {
                        attr {
                            width(summaryColumnWidth); alignItemsCenter()
                            paddingLeft(10f); paddingRight(10f)
                        }
                        event { click { onOpen(stock) } }
                        Text { attr { text(stock.name); fontSize(11f); fontWeightBold(); color(Color(0xFF1E293B)) } }
                        Text { attr { text(stock.code + "  ›"); fontSize(8f); marginTop(2f); color(Color(0xFF94A3B8)) } }
                        Text {
                            attr {
                                text(stock.formatPrice()); fontSize(13f); fontWeightBold(); marginTop(6f)
                                color(if (stock.isUp) Color(0xFFDC2626) else Color(0xFF059669))
                            }
                        }
                        Text {
                            attr {
                                val sign = if (stock.changePercent >= 0) "+" else ""
                                text("今日 $sign${formatPriceValue(stock.changePercent)}%")
                                fontSize(8f); marginTop(2f)
                                color(if (stock.changePercent >= 0) Color(0xFFDC2626) else Color(0xFF059669))
                            }
                        }
                        MiniPriceSparkline(stock, summaryColumnWidth - 28f, 30f)
                        Text {
                            attr {
                                val change = stock.historyChangePercent(20)
                                text("20日 " + signedPercent(change))
                                fontSize(8f); fontWeightBold(); marginTop(3f)
                                color(if (change >= 0) Color(0xFFDC2626) else Color(0xFF059669))
                            }
                        }
                    }
                }
            }
        }

        if (!rankingMode) {
            NormalizedComparisonChart(chartStocks, innerWidth)
            ComparisonMetricRow("股票", tableStocks, labelWidth, stockColumnWidth) { shortStockName(it.name) }
            ComparisonMetricRow("20日涨跌", tableStocks, labelWidth, stockColumnWidth) { signedPercent(it.historyChangePercent(20)) }
            ComparisonMetricRow("区间位置", tableStocks, labelWidth, stockColumnWidth) { historyPositionLabel(it) }
            ComparisonMetricRow("量比", tableStocks, labelWidth, stockColumnWidth) {
                it.volumeRatio(20)?.let { ratio -> formatPriceValue(ratio) + "×" } ?: "不足"
            }
            ComparisonMetricRow("波动率", tableStocks, labelWidth, stockColumnWidth) {
                formatPriceValue(it.historicalVolatilityPercent(20)) + "%"
            }
            ComparisonMetricRow("最大回撤", tableStocks, labelWidth, stockColumnWidth) {
                formatPriceValue(it.maxDrawdownPercent(20)) + "%"
            }
            if (compared.size == 2) ComparisonMetricRow("支撑/压力", tableStocks, labelWidth, stockColumnWidth) {
                formatPriceValue(it.recentSupportLevel()) + "/" + formatPriceValue(it.recentResistanceLevel())
            }
        } else {
            ComparisonRanking(compared, innerWidth, onOpen)
        }

        View {
            attr {
                marginTop(11f); padding(11f); borderRadius(12f)
                backgroundColor(Color(0xFFEFF6FF))
            }
            View { attr { flexDirectionRow(); alignItemsCenter() }
                View { attr { size(6f, 6f); borderRadius(3f); backgroundColor(Color(0xFF2563EB)); marginRight(6f) } }
                Text { attr { text("AI 结论"); fontSize(11f); fontWeightBold(); color(Color(0xFF1E3A8A)) } }
            }
            Text { attr { text(comparisonConclusion(compared)); fontSize(11f); lineHeight(17f); marginTop(6f); color(Color(0xFF334155)) } }
            Text { attr { text("关键观察"); fontSize(10f); fontWeightBold(); marginTop(8f); color(Color(0xFF1E3A8A)) } }
            Text {
                attr {
                    text(comparisonCondition(compared)); fontSize(9f); lineHeight(14f); marginTop(6f)
                    color(Color(0xFF64748B))
                }
            }
        }
        Text {
            attr {
                val latest = compared.maxByOrNull { it.updatedAt }
                val historyBasis = if (compared.all { it.history.isNotEmpty() }) "20日历史收盘" else "当前快照"
                text("来源：${latest?.dataSource ?: "当前行情"} · 行情时间：${latest?.displayUpdatedAt() ?: "快照时间"}\n依据字段：涨跌幅、最高/最低、最新价与$historyBasis")
                fontSize(9f); marginTop(9f); color(Color(0xFF64748B))
            }
        }
    }
}

private val comparisonLineColors = listOf(
    Color(0xFF2563EB), Color(0xFF10B981), Color(0xFFF59E0B), Color(0xFF8B5CF6)
)

private fun ViewContainer<*, *>.NormalizedComparisonChart(stocks: List<Stock>, width: Float) {
    val series = stocks.map { stock ->
        val points = stock.historyWindow(20)
        val base = points.firstOrNull()?.close?.takeIf { it > 0.0 } ?: 1.0
        points.map { (it.close / base - 1.0) * 100.0 }
    }
    val values = series.flatten()
    val minValue = minOf(values.minOrNull() ?: 0.0, 0.0)
    val maxValue = maxOf(values.maxOrNull() ?: 0.0, 0.0)
    val range = (maxValue - minValue).takeIf { it > 0.0 } ?: 1.0
    View {
        attr { width(width); marginTop(11f); padding(10f); borderRadius(12f); backgroundColor(Color.WHITE) }
        Text { attr { text("20日归一化走势"); fontSize(10f); fontWeightBold(); color(Color(0xFF334155)) } }
        Text { attr { text("首日 = 0%，比较相对涨跌"); fontSize(8f); marginTop(2f); color(Color(0xFF94A3B8)) } }
        Canvas({ attr { width(width - 20f); height(78f); marginTop(7f) } }) { canvas, canvasWidth, canvasHeight ->
            val zeroY = 4f + ((maxValue - 0.0) / range * (canvasHeight - 8f)).toFloat()
            canvas.beginPath(); canvas.moveTo(2f, zeroY); canvas.lineTo(canvasWidth - 2f, zeroY)
            canvas.strokeStyle(Color(0xFFCBD5E1)); canvas.lineWidth(1f); canvas.stroke()
            series.forEachIndexed { seriesIndex, points ->
                if (points.size >= 2) {
                    val step = (canvasWidth - 4f) / (points.size - 1)
                    canvas.beginPath()
                    points.forEachIndexed { index, value ->
                        val x = 2f + index * step
                        val y = 4f + ((maxValue - value) / range * (canvasHeight - 8f)).toFloat()
                        if (index == 0) canvas.moveTo(x, y) else canvas.lineTo(x, y)
                    }
                    canvas.strokeStyle(comparisonLineColors[seriesIndex]); canvas.lineWidth(2f); canvas.lineCapRound(); canvas.stroke()
                }
            }
        }
        View {
            attr { flexDirectionRow(); justifyContentSpaceBetween() }
            stocks.forEachIndexed { index, stock ->
                View {
                    attr { flexDirectionRow(); alignItemsCenter(); marginTop(5f) }
                    View { attr { size(7f, 3f); backgroundColor(comparisonLineColors[index]); marginRight(4f) } }
                    Text { attr { text(shortStockName(stock.name)); fontSize(8f); color(Color(0xFF475569)) } }
                }
            }
        }
    }
}

private fun ViewContainer<*, *>.ComparisonRanking(stocks: List<Stock>, width: Float, onOpen: (Stock) -> Unit) {
    val ranked = stocks.sortedWith(compareByDescending<Stock> { it.historyChangePercent(20) }
        .thenBy { it.historicalVolatilityPercent(20) })
    View {
        attr { width(width); marginTop(11f); padding(10f); borderRadius(12f); backgroundColor(Color.WHITE) }
        Text { attr { text("综合排行 · 最多展示5只"); fontSize(10f); fontWeightBold(); color(Color(0xFF334155)) } }
        Text { attr { text("优先20日动能，波动率用于同档筛选"); fontSize(8f); marginTop(3f); color(Color(0xFF94A3B8)) } }
        ranked.forEachIndexed { index, stock ->
            View {
                attr { flexDirectionRow(); alignItemsCenter(); minHeight(38f) }
                event { click { onOpen(stock) } }
                Text { attr { width(24f); text("${index + 1}"); fontSize(10f); fontWeightBold(); color(Color(0xFF2563EB)) } }
                Text { attr { flex(1f); text(stock.name); fontSize(10f); fontWeightBold(); color(Color(0xFF1E293B)) } }
                val period = stock.historyChangePercent(20)
                Text { attr { width(56f); text(signedPercent(period)); textAlignRight(); fontSize(9f); fontWeightBold(); color(if (period >= 0) Color(0xFFDC2626) else Color(0xFF059669)) } }
                Text { attr { width(66f); text("波动 " + formatPriceValue(stock.historicalVolatilityPercent(20)) + "%"); textAlignRight(); fontSize(8f); color(Color(0xFF64748B)) } }
            }
            if (index < ranked.lastIndex) View { attr { height(1f); backgroundColor(Color(0xFFE2E8F0)) } }
        }
    }
}

private fun ViewContainer<*, *>.ComparisonMetricRow(
    label: String,
    stocks: List<Stock>,
    labelWidth: Float,
    stockColumnWidth: Float,
    value: (Stock) -> String
) {
    View {
        attr { flexDirectionColumn() }
        View {
            attr { flexDirectionRow(); alignItemsCenter(); minHeight(34f) }
            Text { attr { width(labelWidth); text(label); fontSize(9f); color(Color(0xFF64748B)) } }
            stocks.forEach { stock ->
                Text {
                    attr {
                        width(stockColumnWidth); text(value(stock)); textAlignCenter()
                        fontSize(9f); fontWeightBold(); color(Color(0xFF1E293B))
                    }
                }
            }
        }
        View { attr { height(1f); backgroundColor(Color(0xFFE2E8F0)) } }
    }
}

private fun historyPositionLabel(stock: Stock): String {
    val closes = stock.historyWindow(20).map { it.close }
    val high = closes.maxOrNull() ?: stock.high
    if (high <= 0.0) return "数据不足"
    return "距高点 " + signedPercent((stock.price - high) / high * 100.0)
}

private fun signedPercent(value: Double): String = (if (value >= 0) "+" else "") + formatPriceValue(value) + "%"

private fun shortStockName(name: String): String = if (name.length <= 5) name else name.take(4) + "…"

private fun comparisonConclusion(stocks: List<Stock>): String {
    val momentum = stocks.maxByOrNull { it.historyChangePercent(20) } ?: stocks.first()
    val steadier = stocks.minByOrNull { it.historicalVolatilityPercent(20) } ?: stocks.first()
    val deepest = stocks.maxByOrNull { it.maxDrawdownPercent(20) } ?: stocks.first()
    return "趋势最强：${momentum.name}；走势最稳：${steadier.name}；回撤最大：${deepest.name}。" +
        if (stocks.size == 2) "偏趋势可关注${momentum.name}，偏稳健可关注${steadier.name}。" else "排名用于缩小观察范围，不代表投资建议。"
}

private fun comparisonCondition(stocks: List<Stock>): String = stocks.joinToString("；") {
    "${it.name}：突破${formatPriceValue(it.recentResistanceLevel())}偏强，跌破${formatPriceValue(it.recentSupportLevel())}需谨慎"
}

private fun ViewContainer<*, *>.InsightBadge(label: String, background: Color, foreground: Color) {
    View {
        attr { marginRight(8f); paddingLeft(8f); paddingRight(8f); paddingTop(3f); paddingBottom(3f); borderRadius(8f); backgroundColor(background) }
        Text { attr { text(label); fontSize(10f); fontWeightBold(); color(foreground) } }
    }
}

private fun riskBackground(risk: String): Color = when (risk) {
    "高风险" -> Color(0xFFFEE2E2)
    "低风险" -> Color(0xFFD1FAE5)
    else -> Color(0xFFFEF3C7)
}

private fun riskForeground(risk: String): Color = when (risk) {
    "高风险" -> Color(0xFFDC2626)
    "低风险" -> Color(0xFF047857)
    else -> Color(0xFFB45309)
}
