package com.example.stockaidemo.model

import kotlin.math.abs
import kotlin.math.round
import kotlin.math.sqrt

data class PricePoint(
    val date: String,
    val close: Double,
    val volume: Double = 0.0
)

data class StockSearchResult(
    val symbol: String,
    val code: String,
    val name: String,
    val market: String,
    val pinyin: String = ""
)

data class StockQuestionResolution(
    val stocks: List<Stock> = emptyList(),
    val ambiguousTerm: String = "",
    val choices: List<StockSearchResult> = emptyList(),
    val errorMessage: String = ""
)

/** 不依赖 JVM 的两位小数格式化，Android/iOS/OHOS 共用。 */
fun formatPriceValue(value: Double): String {
    val scaled = round(value * 100.0).toLong()
    val magnitude = abs(scaled)
    val decimals = (magnitude % 100).toString().padStart(2, '0')
    return (if (scaled < 0) "-" else "") + (magnitude / 100) + "." + decimals
}

/**
 * 股票数据模型
 * 覆盖 Task1 要求的字段：名称/代码/最新价/涨跌额/涨跌幅/最高价/最低价/成交量
 */
data class Stock(
    val code: String,          // 股票代码，如 "000001"
    val name: String,          // 股票名称，如 "平安银行"
    val price: Double,         // 最新价
    val changeAmount: Double,  // 涨跌额
    val changePercent: Double, // 涨跌幅（%）
    val high: Double,          // 最高价
    val low: Double,           // 最低价
    val volume: String,        // 成交量，用字符串方便直接展示单位（如 "12.3万手"）
    val dataSource: String = "演示行情",
    val updatedAt: String = "",
    val history: List<PricePoint> = emptyList(),
    val marketSymbol: String = "",
    val volumeValue: Double = 0.0
) {
    val isUp: Boolean get() = changeAmount >= 0

    fun formatPrice(): String = formatPriceValue(price)

    fun formatChange(): String {
        val sign = if (isUp) "+" else ""
        return "$sign${formatPriceValue(changeAmount)}  $sign${formatPriceValue(changePercent)}%"
    }

    /** 日内振幅，使用昨收近似值（最新价 - 涨跌额）作为分母。 */
    fun amplitudePercent(): Double {
        val previousClose = price - changeAmount
        return if (previousClose <= 0.0) 0.0 else (high - low) / previousClose * 100.0
    }

    /** 最新价位于当日最低到最高之间的位置，0% 为低位，100% 为高位。 */
    fun pricePositionPercent(): Double {
        val range = high - low
        return if (range <= 0.0) 50.0 else ((price - low) / range * 100.0).coerceIn(0.0, 100.0)
    }

    fun riskLabel(): String = when {
        amplitudePercent() >= 4.0 -> "高风险"
        amplitudePercent() >= 2.0 -> "中风险"
        else -> "低风险"
    }

    fun displayUpdatedAt(): String {
        return when {
            updatedAt.length >= 14 && updatedAt.take(14).all { it.isDigit() } -> updatedAt.substring(4, 6) + "-" + updatedAt.substring(6, 8) +
                " " + updatedAt.substring(8, 10) + ":" + updatedAt.substring(10, 12)
            updatedAt.length >= 16 && (updatedAt[4] == '-' || updatedAt[4] == '/') -> updatedAt.substring(5, 16)
            updatedAt.isNotEmpty() -> updatedAt
            else -> "快照时间"
        }
    }

    /** Returns the newest available closing-price window without inventing data. */
    fun historyWindow(days: Int): List<PricePoint> = history.takeLast(days.coerceAtLeast(1))

    /** Percentage movement across a historical window, used by charts and explanations. */
    fun historyChangePercent(days: Int): Double {
        val window = historyWindow(days)
        if (window.size < 2 || window.first().close == 0.0) return 0.0
        return (window.last().close - window.first().close) / window.first().close * 100.0
    }

    fun maxDrawdownPercent(days: Int = 20): Double {
        val closes = historyWindow(days).map { it.close }.filter { it > 0.0 }
        if (closes.size < 2) return 0.0
        var peak = closes.first()
        var largest = 0.0
        closes.forEach { close ->
            if (close > peak) peak = close
            if (peak > 0.0) largest = maxOf(largest, (peak - close) / peak * 100.0)
        }
        return largest
    }

    fun historicalVolatilityPercent(days: Int = 20): Double {
        val closes = historyWindow(days).map { it.close }.filter { it > 0.0 }
        if (closes.size < 3) return 0.0
        val returns = closes.zipWithNext { before, after -> (after - before) / before * 100.0 }
        val average = returns.average()
        return sqrt(returns.map { (it - average) * (it - average) }.average())
    }

    fun volumeRatio(days: Int = 20): Double? {
        if (volumeValue <= 0.0) return null
        val historicalVolumes = historyWindow(days).map { it.volume }.filter { it > 0.0 }
        if (historicalVolumes.size < 5) return null
        return volumeValue / historicalVolumes.average()
    }

    /**
     * Key levels are derived locally from recent closing-price extrema. This keeps
     * the chart and the AI prose grounded in the same deterministic market data.
     */
    fun recentSupportLevel(days: Int = 20): Double = recentKeyLevels(days).first

    fun recentResistanceLevel(days: Int = 20): Double = recentKeyLevels(days).second

    private fun recentKeyLevels(days: Int): Pair<Double, Double> {
        val closes = historyWindow(days).map { it.close }.filter { it > 0.0 }
        if (closes.size < 3) return low to high
        val localLows = (1 until closes.lastIndex)
            .filter { closes[it] <= closes[it - 1] && closes[it] <= closes[it + 1] }
            .map { closes[it] }
        val localHighs = (1 until closes.lastIndex)
            .filter { closes[it] >= closes[it - 1] && closes[it] >= closes[it + 1] }
            .map { closes[it] }
        val support = localLows.filter { it <= price }.maxOrNull() ?: closes.minOrNull() ?: low
        val resistance = localHighs.filter { it >= price }.minOrNull() ?: closes.maxOrNull() ?: high
        return if (support < resistance) support to resistance
        else (closes.minOrNull() ?: low) to (closes.maxOrNull() ?: high)
    }
}

/** Sparse, readable date ticks for the detail chart. */
fun Stock.historyDateTicks(days: Int): List<String> {
    val points = historyWindow(days)
    if (points.isEmpty()) return emptyList()
    val indices = if (points.size <= 5) {
        listOf(0, points.lastIndex / 2, points.lastIndex)
    } else {
        listOf(0, points.lastIndex / 3, points.lastIndex * 2 / 3, points.lastIndex)
    }
    return indices.distinct().map { points[it].date.takeLast(5) }
}

/** A compact, deterministic overview grounded in the currently loaded watchlist. */
fun buildMarketPulse(stocks: List<Stock>): String {
    if (stocks.isEmpty()) return "等待真实行情后生成今日洞察"
    val upCount = stocks.count { it.changePercent > 0.0 }
    val downCount = stocks.count { it.changePercent < 0.0 }
    val strongest = stocks.maxByOrNull { it.changePercent } ?: stocks.first()
    val weakest = stocks.minByOrNull { it.changePercent } ?: stocks.first()
    val direction = when {
        upCount > downCount -> "整体偏强"
        downCount > upCount -> "整体偏弱"
        else -> "涨跌分化"
    }
    val risk = when {
        stocks.map { it.amplitudePercent() }.average() >= 4.0 -> "偏高"
        stocks.map { it.amplitudePercent() }.average() >= 2.0 -> "中性"
        else -> "较低"
    }
    val lagging = if (weakest.changePercent < 0.0) "回调" else "相对落后"
    return "今日自选$direction，${strongest.name}领涨，${weakest.name}$lagging；$upCount 涨 $downCount 跌，波动风险$risk"
}

/** AI judgement uses semantic wording so quote red/green remains reserved for market movement. */
fun aiSignalLabel(action: String): String = when (action) {
    "买入", "看多", "偏多" -> "偏强"
    "卖出", "看空", "偏空" -> "偏弱"
    "分析中" -> "分析中"
    else -> "关注"
}
