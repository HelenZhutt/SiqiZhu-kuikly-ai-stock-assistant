package com.example.stockaidemo.data

import com.example.stockaidemo.model.Stock

/**
 * 行情仓库。首次打开先用可演示的快照，真实接口返回后原位替换。
 * 网络异常不会破坏页面闭环。
 */
object StockRepository {

    const val WATCHLIST_SYMBOLS_KEY = "stock_watchlist_symbols_v1"
    const val WATCHLIST_INITIALIZED_KEY = "stock_watchlist_initialized_v1"

    private val stocks = mutableListOf(
        Stock(
            code = "000001",
            name = "平安银行",
            price = 12.86,
            changeAmount = 0.34,
            changePercent = 2.72,
            high = 12.90,
            low = 12.50,
            volume = "58.2万手"
        ),
        Stock(
            code = "600519",
            name = "贵州茅台",
            price = 1680.50,
            changeAmount = -12.30,
            changePercent = -0.73,
            high = 1699.00,
            low = 1675.20,
            volume = "1.2万手"
        ),
        Stock(
            code = "300750",
            name = "宁德时代",
            price = 218.40,
            changeAmount = 4.82,
            changePercent = 2.26,
            high = 219.80,
            low = 213.10,
            volume = "23.5万手"
        ),
        Stock(
            code = "00700",
            name = "腾讯控股",
            price = 386.20,
            changeAmount = 5.60,
            changePercent = 1.47,
            high = 388.00,
            low = 380.40,
            volume = "9.8万手"
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
}
