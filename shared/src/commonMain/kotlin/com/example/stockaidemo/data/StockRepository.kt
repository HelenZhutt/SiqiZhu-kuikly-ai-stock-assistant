package com.example.stockaidemo.data

import com.example.stockaidemo.model.PricePoint
import com.example.stockaidemo.model.Stock
import kotlin.math.round

/**
 * 行情仓库。首次打开先用可演示的快照，真实接口返回后原位替换。
 * 网络异常不会破坏页面闭环。
 */
object StockRepository {

    const val WATCHLIST_SYMBOLS_KEY = "stock_watchlist_symbols_v1"
    const val WATCHLIST_INITIALIZED_KEY = "stock_watchlist_initialized_v1"

    private val demoTradingDays = listOf(
        "2026-08-17", "2026-08-18", "2026-08-19", "2026-08-20", "2026-08-21",
        "2026-08-24", "2026-08-25", "2026-08-26", "2026-08-27", "2026-08-28",
        "2026-08-31", "2026-09-01", "2026-09-02", "2026-09-03", "2026-09-04",
        "2026-09-07", "2026-09-08", "2026-09-09", "2026-09-10", "2026-09-11"
    )

    private val stocks = mutableListOf(
        Stock(
            code = "000001",
            name = "平安银行",
            price = 12.86,
            changeAmount = 0.34,
            changePercent = 2.72,
            high = 12.90,
            low = 12.50,
            volume = "58.2万手",
            updatedAt = "20260911150000",
            history = demoHistory(
                12.86,
                doubleArrayOf(
                    94.2, 93.6, 94.8, 93.1, 93.9, 95.2, 96.0, 94.4, 95.7, 96.5,
                    95.9, 97.4, 96.8, 98.1, 98.6, 97.9, 99.0, 98.4, 99.5, 100.0
                ),
                520_000.0
            ),
            marketSymbol = "sz000001",
            volumeValue = 582_000.0
        ),
        Stock(
            code = "600519",
            name = "贵州茅台",
            price = 1680.50,
            changeAmount = -12.30,
            changePercent = -0.73,
            high = 1699.00,
            low = 1675.20,
            volume = "1.2万手",
            updatedAt = "20260911150000",
            history = demoHistory(
                1680.50,
                doubleArrayOf(
                    103.8, 104.2, 103.5, 104.6, 103.1, 102.4, 102.9, 101.8, 102.2, 101.5,
                    102.0, 101.1, 100.6, 101.4, 100.8, 101.2, 100.4, 100.9, 100.3, 100.0
                ),
                11_000.0
            ),
            marketSymbol = "sh600519",
            volumeValue = 12_000.0
        ),
        Stock(
            code = "300750",
            name = "宁德时代",
            price = 218.40,
            changeAmount = 4.82,
            changePercent = 2.26,
            high = 219.80,
            low = 213.10,
            volume = "23.5万手",
            updatedAt = "20260911150000",
            history = demoHistory(
                218.40,
                doubleArrayOf(
                    91.5, 93.0, 90.8, 92.4, 94.1, 93.2, 95.6, 94.8, 96.2, 95.0,
                    97.4, 96.6, 98.0, 97.1, 98.8, 98.2, 99.4, 98.7, 99.8, 100.0
                ),
                210_000.0
            ),
            marketSymbol = "sz300750",
            volumeValue = 235_000.0
        ),
        Stock(
            code = "00700",
            name = "腾讯控股",
            price = 386.20,
            changeAmount = 5.60,
            changePercent = 1.47,
            high = 388.00,
            low = 380.40,
            volume = "9.8万手",
            updatedAt = "20260911150000",
            history = demoHistory(
                386.20,
                doubleArrayOf(
                    95.0, 95.6, 95.2, 96.1, 95.8, 96.7, 96.3, 97.2, 96.9, 97.6,
                    97.3, 98.1, 97.8, 98.6, 98.2, 99.0, 98.7, 99.4, 99.1, 100.0
                ),
                88_000.0
            ),
            marketSymbol = "hk00700",
            volumeValue = 98_000.0
        )
    )

    // Stocks queried inside a conversation are intentionally not part of the
    // user's watchlist. They remain available for cards/detail navigation only.
    private val transientStocks = mutableListOf<Stock>()

    fun getStockList(): List<Stock> = stocks.toList()

    fun getStockByCode(code: String): Stock? = stocks.find { it.code == code }
        ?: transientStocks.find { it.code == code }

    fun getAllKnownStocks(): List<Stock> = mergeStocks(stocks, transientStocks)

    fun isInWatchlist(code: String): Boolean = stocks.any { it.code == code }

    fun addToWatchlist(stock: Stock) {
        if (stocks.none { it.code == stock.code }) stocks.add(stock)
        transientStocks.removeAll { it.code == stock.code }
    }

    fun removeFromWatchlist(code: String) {
        val removed = stocks.firstOrNull { it.code == code } ?: return
        stocks.removeAll { it.code == code }
        if (transientStocks.none { it.code == code }) transientStocks.add(removed)
    }

    fun cacheTransientStocks(fresh: List<Stock>) {
        val onlyTransient = fresh.filter { candidate -> stocks.none { it.code == candidate.code } }
        if (onlyTransient.isEmpty()) return
        val merged = mergeStocks(transientStocks, onlyTransient)
        transientStocks.clear()
        transientStocks.addAll(merged)
    }

    fun replaceStocks(realStocks: List<Stock>) {
        if (realStocks.isEmpty()) return
        val merged = mergeStocks(stocks, realStocks)
        stocks.clear()
        stocks.addAll(merged)
        transientStocks.removeAll { transient -> stocks.any { it.code == transient.code } }
    }

    internal fun mergeStocks(cached: List<Stock>, fresh: List<Stock>): List<Stock> {
        val merged = cached.map { old -> fresh.firstOrNull { it.code == old.code } ?: old }.toMutableList()
        fresh.filter { candidate -> merged.none { it.code == candidate.code } }.forEach { merged.add(it) }
        return merged
    }

    /**
     * 离线 Demo 也要能画出 20 日折线和结构化卡片。相对路径最后一点对齐最新价，
     * 真实接口返回后会被 replaceStocks 整段替换。
     */
    private fun demoHistory(endPrice: Double, relativeCloses: DoubleArray, baseVolume: Double): List<PricePoint> {
        val last = relativeCloses.last()
        return relativeCloses.mapIndexed { index, relative ->
            PricePoint(
                date = demoTradingDays[index],
                close = round(endPrice * relative / last * 100.0) / 100.0,
                volume = baseVolume * (0.85 + (index % 4) * 0.08)
            )
        }
    }
}
