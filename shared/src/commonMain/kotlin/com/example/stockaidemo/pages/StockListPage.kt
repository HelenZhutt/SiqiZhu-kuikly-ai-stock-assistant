package com.example.stockaidemo.pages

import com.tencent.kuikly.core.annotations.Page
import com.tencent.kuikly.core.base.*
import com.tencent.kuikly.core.base.event.PanGestureParams
import com.tencent.kuikly.core.directives.vfor
import com.tencent.kuikly.core.directives.vif
import com.tencent.kuikly.core.module.RouterModule
import com.tencent.kuikly.core.module.NetworkModule
import com.tencent.kuikly.core.module.SharedPreferencesModule
import com.tencent.kuikly.core.nvi.serialization.json.JSONObject
import com.tencent.kuikly.core.nvi.serialization.json.JSONArray
import com.tencent.kuikly.core.reactive.handler.*
import com.tencent.kuikly.core.views.*
import com.example.stockaidemo.base.BasePager
import com.example.stockaidemo.data.StockRepository
import com.example.stockaidemo.data.MarketDataEngine
import com.example.stockaidemo.model.Stock
import com.example.stockaidemo.model.StockSearchResult
import com.example.stockaidemo.model.buildMarketPulse
import com.example.stockaidemo.model.formatPriceValue
import com.example.stockaidemo.components.MiniPriceSparkline
import kotlin.math.abs

/**
 * Task1-1 首页行情列表页
 * - 展示股票名称/代码/最新价/涨跌额/涨跌幅
 * - 支持列表滚动浏览
 * - 点击某只股票进入个股详情页
 */
@Page("StockList")
internal class StockListPage : BasePager() {
    companion object {
        private const val WATCHLIST_FOLDERS_KEY = "stock_watchlist_folders_v1"
        private const val WATCHLIST_FOLDER_ASSIGNMENTS_KEY = "stock_watchlist_folder_assignments_v1"
    }

    // 响应式列表容器，配合 vfor 自动渲染每一行
    private var stockList by observableList<Stock>()
    private var displayedStocks by observableList<Stock>()
    private var marketStatus by observable("演示行情 · 点右侧刷新")
    private var isRefreshing by observable(false)
    private var searchResults by observableList<StockSearchResult>()
    private var searchStatus by observable("")
    private var isSearching by observable(false)
    private var searchText = ""
    private var searchRequestId = 0
    private var revealedDeleteCode by observable("")
    private var activeSwipeCode by observable("")
    private var activeSwipeOffset by observable(0f)
    private var swipeStartPageX = 0f
    private var swipeStartPageY = 0f
    private var swipeStartOffset = 0f
    private var swipeIsHorizontal = false
    private var folderNames by observableList<String>()
    private var selectedFolder by observable("")
    private var assigningStockCode by observable("")
    private var isCreatingFolder by observable(false)
    private var isManagingFolders by observable(false)
    private var newFolderText = ""
    private val stockFolders = mutableMapOf<String, String>()
    private val savedSymbols = mutableListOf<String>()
    private lateinit var searchInputRef: ViewRef<InputView>
    private lateinit var folderInputRef: ViewRef<InputView>

    override fun created() {
        super.created()
        restoreWatchlistSymbols()
        restoreFolders()
        stockList.addAll(StockRepository.getStockList())
        refreshDisplayedStocks()
        refreshQuotes()
    }

    override fun pageDidAppear() {
        super.pageDidAppear()
        // StockChat can add an on-demand quote while this page stays alive.
        // Re-read the shared watchlist whenever the user comes back.
        restoreWatchlistSymbols()
        syncVisibleStocks()
    }

    override fun body(): ViewBuilder {
        val ctx = this
        return {
            attr {
                flex(1f)
                backgroundColor(Color(0xFFF5F6FA))
            }

            // 顶部标题栏
            View {
                attr {
                    height(58f + ctx.getPager().pageData.statusBarHeight)
                    paddingTop(ctx.getPager().pageData.statusBarHeight)
                    flexDirectionRow()
                    alignItemsCenter()
                    justifyContentSpaceBetween()
                    paddingLeft(16f)
                    paddingRight(16f)
                    backgroundColor(Color.WHITE)
                }
                Text {
                    attr {
                        text("行情 · 自选")
                        fontSize(18f)
                        fontWeightBold()
                        color(Color(0xFF1A1A1A))
                    }
                }
                View {
                    attr {
                        paddingLeft(12f)
                        paddingRight(12f)
                        paddingTop(7f)
                        paddingBottom(7f)
                        borderRadius(16f)
                        backgroundColor(Color(0xFFEFF6FF))
                    }
                    Text {
                        attr {
                            text("AI 问答 ›")
                            fontSize(12f)
                            fontWeightBold()
                            color(Color(0xFF2563EB))
                        }
                    }
                    event {
                        click {
                            ctx.acquireModule<RouterModule>(RouterModule.MODULE_NAME)
                                .openPage("StockChat", JSONObject())
                        }
                    }
                }
            }

            // Compact segmented folder bar. Every control has an explicit
            // height and allCenter so labels stay centered regardless of text.
            View {
                attr {
                    height(52f); marginLeft(12f); marginRight(12f); marginTop(8f); marginBottom(8f)
                    paddingLeft(6f); paddingRight(6f)
                    borderRadius(18f); flexDirectionRow(); alignItemsCenter(); backgroundColor(Color.WHITE)
                }
                List {
                    attr { flex(1f); height(40f); flexDirectionRow(); alignItemsCenter() }
                    View {
                        attr {
                            height(36f); marginRight(6f); paddingLeft(14f); paddingRight(14f)
                            borderRadius(12f); allCenter()
                            backgroundColor(if (ctx.selectedFolder.isEmpty()) Color(0xFF2563EB) else Color(0xFFF1F5F9))
                        }
                        Text {
                            attr {
                                text("全部"); fontSize(12f); fontWeightMedium()
                                color(if (ctx.selectedFolder.isEmpty()) Color.WHITE else Color(0xFF475569))
                            }
                        }
                        event { click { ctx.selectFolder("") } }
                    }
                    vfor({ ctx.folderNames }) { folder ->
                        View {
                            attr {
                                height(36f); marginRight(6f); paddingLeft(13f); paddingRight(if (ctx.isManagingFolders) 8f else 13f)
                                borderRadius(12f); flexDirectionRow(); alignItemsCenter(); justifyContentCenter()
                                backgroundColor(if (ctx.selectedFolder == folder) Color(0xFF2563EB) else Color(0xFFF1F5F9))
                            }
                            Text {
                                attr {
                                    text(folder); fontSize(12f); fontWeightMedium()
                                    color(if (ctx.selectedFolder == folder) Color.WHITE else Color(0xFF475569))
                                }
                            }
                            vif({ ctx.isManagingFolders }) {
                                View {
                                    attr {
                                        size(20f, 20f); marginLeft(6f); borderRadius(10f); allCenter()
                                        backgroundColor(if (ctx.selectedFolder == folder) Color(0x55FFFFFF) else Color(0xFFFFE4E6))
                                    }
                                    Text {
                                        attr {
                                            text("×"); fontSize(14f); fontWeightBold()
                                            color(if (ctx.selectedFolder == folder) Color.WHITE else Color(0xFFE11D48))
                                        }
                                    }
                                    event { click { ctx.deleteFolder(folder) } }
                                }
                            }
                            event { click { if (!ctx.isManagingFolders) ctx.selectFolder(folder) } }
                        }
                    }
                }
                View {
                    attr { width(48f); height(36f); marginLeft(4f); borderRadius(12f); allCenter(); backgroundColor(Color(0xFFEFF6FF)) }
                    Text { attr { text("＋ 新建"); fontSize(10f); fontWeightBold(); color(Color(0xFF2563EB)) } }
                    event {
                        click {
                            ctx.isManagingFolders = false
                            ctx.isCreatingFolder = true
                        }
                    }
                }
                vif({ ctx.folderNames.isNotEmpty() }) {
                    View {
                        attr { width(42f); height(36f); marginLeft(4f); borderRadius(12f); allCenter(); backgroundColor(Color(0xFFF1F5F9)) }
                        Text {
                            attr {
                                text(if (ctx.isManagingFolders) "完成" else "管理")
                                fontSize(10f); fontWeightMedium()
                                color(if (ctx.isManagingFolders) Color(0xFF2563EB) else Color(0xFF64748B))
                            }
                        }
                        event { click { ctx.isManagingFolders = !ctx.isManagingFolders } }
                    }
                }
            }

            vif({ ctx.isCreatingFolder }) {
                View {
                    attr {
                        marginLeft(12f); marginRight(12f); marginBottom(8f); padding(8f)
                        borderRadius(12f); flexDirectionRow(); alignItemsCenter(); backgroundColor(Color.WHITE)
                    }
                    View {
                        attr { flex(1f); height(36f); paddingLeft(10f); paddingRight(10f); borderRadius(10f); backgroundColor(Color(0xFFF1F5F9)) }
                        Input {
                            ref { ctx.folderInputRef = it }
                            attr { flex(1f); fontSize(13f); placeholder("文件夹名称，如：科技股"); returnKeyTypeDone() }
                            event {
                                textDidChange { ctx.newFolderText = it.text }
                                inputReturn { ctx.createFolder(it.text) }
                            }
                        }
                    }
                    View {
                        attr { marginLeft(8f); paddingLeft(12f); paddingRight(12f); height(36f); borderRadius(10f); allCenter(); backgroundColor(Color(0xFF2563EB)) }
                        Text { attr { text("创建"); fontSize(11f); fontWeightBold(); color(Color.WHITE) } }
                        event { click { ctx.createFolder(ctx.newFolderText) } }
                    }
                    View {
                        attr { marginLeft(6f); padding(8f) }
                        Text { attr { text("取消"); fontSize(11f); color(Color(0xFF64748B)) } }
                        event { click { ctx.cancelFolderCreation() } }
                    }
                }
            }

            View {
                attr {
                    marginLeft(12f); marginRight(12f); marginBottom(8f)
                    padding(10f); borderRadius(14f); backgroundColor(Color.WHITE)
                }
                View {
                    attr { flexDirectionRow(); alignItemsCenter() }
                    View {
                        attr {
                            flex(1f); height(40f); paddingLeft(12f); paddingRight(12f)
                            borderRadius(20f); backgroundColor(Color(0xFFF1F5F9))
                        }
                        Input {
                            ref { ctx.searchInputRef = it }
                            attr {
                                flex(1f); fontSize(13f); color(Color(0xFF1E293B))
                                placeholder("搜索A股/港股名称、代码或拼音…")
                                placeholderColor(Color(0xFF94A3B8)); imeNoFullscreen(true); returnKeyTypeSearch()
                            }
                            event {
                                // Keep the button and the keyboard Search action on the
                                // same value, including while a Chinese IME is committing.
                                textDidChange {
                                    ctx.searchText = it.text
                                    if (it.text.trim().isEmpty()) ctx.clearSearch()
                                }
                                inputBlur { ctx.searchText = it.text }
                                inputReturn { ctx.searchStocks(it.text) }
                            }
                        }
                    }
                    View {
                        attr {
                            width(58f); height(40f); marginLeft(8f); borderRadius(20f)
                            allCenter(); backgroundColor(Color(0xFF2563EB))
                        }
                        Text { attr { text(if (ctx.isSearching) "…" else "搜索"); fontSize(12f); fontWeightBold(); color(Color.WHITE) } }
                        event { click { ctx.searchStocks(ctx.searchText) } }
                    }
                }
                if (ctx.searchStatus.isNotEmpty()) {
                    Text { attr { text(ctx.searchStatus); fontSize(10f); marginTop(7f); marginLeft(4f); color(Color(0xFF64748B)) } }
                }
                vfor({ ctx.searchResults }) { result ->
                    View {
                        attr {
                            marginTop(8f); padding(10f); borderRadius(10f); flexDirectionRow()
                            alignItemsCenter(); justifyContentSpaceBetween(); backgroundColor(Color(0xFFF8FAFC))
                        }
                        event { click { ctx.selectSearchResult(result) } }
                        View {
                            attr { flexDirectionRow(); alignItemsCenter() }
                            View {
                                attr { paddingLeft(7f); paddingRight(7f); paddingTop(3f); paddingBottom(3f); borderRadius(7f); backgroundColor(Color(0xFFDBEAFE)) }
                                Text { attr { text(result.market); fontSize(9f); fontWeightBold(); color(Color(0xFF1D4ED8)) } }
                            }
                            Text { attr { text(result.name); fontSize(13f); fontWeightBold(); marginLeft(9f); color(Color(0xFF1E293B)) } }
                            Text { attr { text(result.code); fontSize(11f); marginLeft(7f); color(Color(0xFF94A3B8)) } }
                        }
                        Text { attr { text("加入并查看 ›"); fontSize(10f); color(Color(0xFF2563EB)) } }
                    }
                }
            }

            View {
                attr {
                    margin(12f)
                    marginBottom(8f)
                    padding(14f)
                    borderRadius(14f)
                    backgroundLinearGradient(
                        Direction.TO_RIGHT,
                        ColorStop(Color(0xFF1D4ED8), 0f),
                        ColorStop(Color(0xFF0891B2), 1f)
                    )
                }
                View {
                    attr { flexDirectionRow(); justifyContentSpaceBetween(); alignItemsCenter() }
                    View {
                        attr { flexDirectionColumn() }
                        Text {
                            attr {
                                text("AI 市场雷达")
                                fontSize(16f)
                                fontWeightBold()
                                color(Color.WHITE)
                            }
                        }
                        Text {
                            attr {
                                val visibleStocks = ctx.displayedStocks
                                val upCount = visibleStocks.count { it.isUp }
                                val downCount = visibleStocks.size - upCount
                                val scope = if (ctx.selectedFolder.isEmpty()) "自选" else ctx.selectedFolder
                                text("${visibleStocks.size} 只$scope · $upCount 涨 $downCount 跌 · ${ctx.marketStatus}")
                                fontSize(11f)
                                marginTop(5f)
                                color(Color(0xFFDDEAFE))
                            }
                        }
                    }
                    Text {
                        attr {
                            text(
                                if (ctx.isRefreshing) "更新中…"
                                else if (ctx.marketStatus.contains("演示行情")) "重试行情"
                                else "查看今日解读 ›"
                            )
                            fontSize(10f)
                            color(Color(0xFFE0F2FE))
                        }
                        event {
                            click {
                                if (ctx.marketStatus.contains("演示行情")) ctx.refreshQuotes()
                                else ctx.openTodayInsight()
                            }
                        }
                    }
                }
                Text {
                    attr {
                        text(buildMarketPulse(ctx.displayedStocks))
                        fontSize(12f); lineHeight(17f); marginTop(10f)
                        color(Color.WHITE)
                    }
                }
            }

            // 行情列表
            List {
                attr {
                    flex(1f)
                }

                vfor({ ctx.displayedStocks }) { stock ->
                    View {
                        attr {
                            positionRelative()
                            overflow(true)
                            backgroundColor(Color(0xFFFF3B30))
                        }

                        // iOS-style destructive action, revealed only by swiping the row left.
                        View {
                            attr {
                                positionAbsolute()
                                top(0f)
                                right(0f)
                                bottom(0f)
                                width(76f)
                                allCenter()
                                backgroundColor(Color(0xFFFF3B30))
                            }
                            Text {
                                attr {
                                    text("删除")
                                    fontSize(14f)
                                    fontWeightMedium()
                                    color(Color.WHITE)
                                }
                            }
                            event {
                                click {
                                    ctx.closeSwipeAction()
                                    ctx.removeSavedStock(stock)
                                }
                            }
                        }

                        // Foreground row slides over the destructive action.
                        View {
                            attr {
                                flexDirectionRow()
                                justifyContentSpaceBetween()
                                alignItemsCenter()
                                padding(16f)
                                backgroundColor(Color.WHITE)
                                transform(Translate(0f, offsetX = ctx.swipeOffsetFor(stock.code)))
                            }

                            event {
                                pan { ctx.handleStockRowPan(stock, it) }
                                click {
                                    if (ctx.revealedDeleteCode.isNotEmpty()) {
                                        ctx.closeSwipeAction()
                                    } else {
                                        // 点击行 -> 跳转个股详情页，携带股票代码
                                        val params = JSONObject()
                                        params.put("code", stock.code)
                                        ctx.acquireModule<RouterModule>(RouterModule.MODULE_NAME)
                                            .openPage("StockDetail", params)
                                    }
                                }
                            }

                            // 左侧：名称 + 代码
                            View {
                                attr {
                                    flexDirectionColumn()
                                    width(105f)
                                }
                                Text {
                                    attr {
                                        text(stock.name)
                                        fontSize(16f)
                                        fontWeightBold()
                                        color(Color(0xFF1A1A1A))
                                    }
                                }
                                Text {
                                    attr {
                                        text(stock.code)
                                        fontSize(12f)
                                        color(Color(0xFF999999))
                                        marginTop(4f)
                                    }
                                }
                                Text {
                                    attr {
                                        text(ctx.folderLabel(stock) + " ›")
                                        fontSize(9f); marginTop(5f); color(Color(0xFF2563EB))
                                    }
                                    event {
                                        click {
                                            ctx.closeSwipeAction()
                                            ctx.assigningStockCode = if (ctx.assigningStockCode == stock.code) "" else stock.code
                                        }
                                    }
                                }
                            }

                            View {
                                attr {
                                    flex(1f)
                                    marginLeft(10f)
                                    marginRight(10f)
                                    alignItemsCenter()
                                }
                                MiniPriceSparkline(stock, 86f)
                                Text {
                                    attr {
                                        if (stock.history.size >= 2) {
                                            val periodChange = stock.historyChangePercent(20)
                                            val sign = if (periodChange >= 0) "+" else ""
                                            text("20日 $sign${formatPriceValue(periodChange)}%")
                                            color(if (periodChange >= 0) Color(0xFFF5222D) else Color(0xFF00A870))
                                        } else {
                                            text("走势")
                                            color(Color(0xFF94A3B8))
                                        }
                                        fontSize(8f); marginTop(2f)
                                    }
                                }
                            }

                            // 右侧：最新价 + 涨跌额/涨跌幅
                            View {
                                attr {
                                    flexDirectionColumn()
                                    alignItemsFlexEnd()
                                    width(104f)
                                }
                                Text {
                                    attr {
                                        text(stock.formatPrice())
                                        fontSize(16f)
                                        fontWeightBold()
                                        color(if (stock.isUp) Color(0xFFF5222D) else Color(0xFF00A870))
                                    }
                                }
                                Text {
                                    attr {
                                        text(stock.formatChange())
                                        fontSize(12f)
                                        marginTop(4f)
                                        color(if (stock.isUp) Color(0xFFF5222D) else Color(0xFF00A870))
                                    }
                                }
                            }
                        }

                        // 分割线（用 1px 高的 View 模拟，避免依赖未验证过的 border API）
                        View {
                            attr {
                                height(1f)
                                backgroundColor(Color(0xFFEEEEEE))
                            }
                        }

                        vif({ ctx.assigningStockCode == stock.code }) {
                            View {
                                attr {
                                    paddingLeft(16f); paddingRight(16f); paddingTop(9f); paddingBottom(9f)
                                    flexDirectionRow(); alignItemsCenter(); backgroundColor(Color(0xFFF8FAFC))
                                }
                                Text { attr { text("收入："); fontSize(10f); color(Color(0xFF64748B)) } }
                                View {
                                    attr { marginLeft(6f); paddingLeft(9f); paddingRight(9f); paddingTop(5f); paddingBottom(5f); borderRadius(10f); backgroundColor(Color.WHITE) }
                                    Text { attr { text("未分类"); fontSize(10f); color(Color(0xFF475569)) } }
                                    event { click { ctx.assignStockToFolder(stock, "") } }
                                }
                                vfor({ ctx.folderNames }) { folder ->
                                    View {
                                        attr {
                                            marginLeft(6f); paddingLeft(9f); paddingRight(9f); paddingTop(5f); paddingBottom(5f)
                                            borderRadius(10f); backgroundColor(if (ctx.folderLabel(stock) == folder) Color(0xFFDBEAFE) else Color.WHITE)
                                        }
                                        Text { attr { text(folder); fontSize(10f); color(Color(0xFF1D4ED8)) } }
                                        event { click { ctx.assignStockToFolder(stock, folder) } }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private fun refreshQuotes() {
        if (isRefreshing) return
        if (savedSymbols.isEmpty()) {
            marketStatus = "暂无自选 · 搜索添加"
            syncVisibleStocks()
            return
        }
        isRefreshing = true
        marketStatus = "正在更新真实行情…"
        MarketDataEngine.loadSymbols(acquireModule(NetworkModule.MODULE_NAME), savedSymbols) { stocks, _ ->
            isRefreshing = false
            if (stocks == null) {
                marketStatus = "演示行情 · 点右侧重试"
                return@loadSymbols
            }
            StockRepository.replaceStocks(stocks)
            syncVisibleStocks()
            val displayTime = stocks.firstOrNull()?.displayUpdatedAt() ?: "刚刚"
            marketStatus = "真实行情 · $displayTime 更新"
        }
    }

    private fun openTodayInsight() {
        val question = if (selectedFolder.isEmpty()) {
            "请根据当前真实行情解读今天的自选股整体表现、领涨回调和风险"
        } else {
            val names = displayedStocks.joinToString("、") { it.name }
            "请根据当前真实行情解读“$selectedFolder”文件夹里的股票整体表现、领涨回调和风险：$names"
        }
        acquireModule<RouterModule>(RouterModule.MODULE_NAME).openPage("StockChat", JSONObject().apply {
            put("question", question)
            put("forceQuestion", true)
        })
    }

    private fun searchStocks(rawQuery: String) {
        val query = rawQuery.trim()
        if (query.isEmpty()) {
            searchResults.clear()
            searchStatus = "请输入股票名称、代码或拼音"
            return
        }
        searchText = query
        val requestId = ++searchRequestId
        isSearching = true
        searchStatus = "正在搜索“$query”…"
        searchResults.clear()
        val cachedResults = MarketDataEngine.searchCachedStocks(query, StockRepository.getAllKnownStocks())
        searchResults.addAll(cachedResults)
        if (cachedResults.isNotEmpty()) searchStatus = "已找到本地匹配，正在补全市场结果…"
        if (::searchInputRef.isInitialized) searchInputRef.view?.blur()
        MarketDataEngine.search(acquireModule(NetworkModule.MODULE_NAME), query) { results, message ->
            // A slower response for an older query must never replace the user's
            // latest search results.
            if (requestId != searchRequestId) return@search
            isSearching = false
            if (results == null) {
                searchStatus = if (cachedResults.isNotEmpty()) {
                    "已显示本地匹配 · 在线补全暂时不可用"
                } else {
                    "$message · 点击搜索重试"
                }
            } else if (results.isEmpty()) {
                searchStatus = if (cachedResults.isNotEmpty()) {
                    "找到 ${cachedResults.size} 个本地结果"
                } else {
                    "没有找到匹配的A股或港股，请换名称或代码"
                }
            } else {
                val merged = (cachedResults + results).distinctBy { it.symbol }
                searchResults.clear()
                searchResults.addAll(merged)
                searchStatus = "找到 ${merged.size} 个结果，选择后获取真实行情"
            }
        }
    }

    private fun clearSearch() {
        searchRequestId += 1
        isSearching = false
        searchResults.clear()
        searchStatus = ""
    }

    private fun swipeOffsetFor(code: String): Float = when {
        activeSwipeCode == code -> activeSwipeOffset
        revealedDeleteCode == code -> -76f
        else -> 0f
    }

    private fun handleStockRowPan(stock: Stock, params: PanGestureParams) {
        when (params.state) {
            "start" -> {
                activeSwipeCode = stock.code
                swipeStartPageX = params.pageX
                swipeStartPageY = params.pageY
                swipeStartOffset = if (revealedDeleteCode == stock.code) -76f else 0f
                if (revealedDeleteCode != stock.code) revealedDeleteCode = ""
                activeSwipeOffset = swipeStartOffset
                swipeIsHorizontal = false
            }
            "move" -> {
                if (activeSwipeCode != stock.code) return
                val deltaX = params.pageX - swipeStartPageX
                val deltaY = params.pageY - swipeStartPageY
                if (!swipeIsHorizontal && abs(deltaX) > 5f && abs(deltaX) > abs(deltaY)) {
                    swipeIsHorizontal = true
                }
                if (swipeIsHorizontal) {
                    activeSwipeOffset = (swipeStartOffset + deltaX).coerceIn(-76f, 0f)
                }
            }
            "end" -> {
                if (activeSwipeCode != stock.code) return
                revealedDeleteCode = if (swipeIsHorizontal && activeSwipeOffset <= -38f) stock.code else ""
                activeSwipeCode = ""
                activeSwipeOffset = 0f
                swipeIsHorizontal = false
            }
        }
    }

    private fun closeSwipeAction() {
        revealedDeleteCode = ""
        activeSwipeCode = ""
        activeSwipeOffset = 0f
        swipeIsHorizontal = false
    }

    private fun selectFolder(folder: String) {
        selectedFolder = folder
        assigningStockCode = ""
        refreshDisplayedStocks()
    }

    private fun refreshDisplayedStocks() {
        displayedStocks.clear()
        displayedStocks.addAll(if (selectedFolder.isEmpty()) stockList else {
            stockList.filter { stockFolders[watchlistSymbol(it)] == selectedFolder }
        })
    }

    private fun folderLabel(stock: Stock): String = stockFolders[watchlistSymbol(stock)].orEmpty().ifEmpty { "未分类" }

    private fun createFolder(rawName: String) {
        val name = rawName.trim().take(12)
        if (name.isEmpty()) return
        if (folderNames.none { it.equals(name, ignoreCase = true) }) folderNames.add(name)
        selectedFolder = folderNames.firstOrNull { it.equals(name, ignoreCase = true) } ?: name
        cancelFolderCreation()
        persistFolders()
        refreshDisplayedStocks()
    }

    private fun deleteFolder(folder: String) {
        folderNames.removeAll { it == folder }
        stockFolders.entries.removeAll { it.value == folder }
        if (selectedFolder == folder) selectedFolder = ""
        assigningStockCode = ""
        if (folderNames.isEmpty()) isManagingFolders = false
        persistFolders()
        refreshDisplayedStocks()
    }

    private fun cancelFolderCreation() {
        newFolderText = ""
        isCreatingFolder = false
        if (::folderInputRef.isInitialized) {
            folderInputRef.view?.setText("")
            folderInputRef.view?.blur()
        }
    }

    private fun assignStockToFolder(stock: Stock, folder: String) {
        val symbol = watchlistSymbol(stock)
        if (folder.isEmpty()) stockFolders.remove(symbol) else stockFolders[symbol] = folder
        assigningStockCode = ""
        persistFolders()
        refreshDisplayedStocks()
    }

    private fun restoreFolders() {
        val preferences = acquireModule<SharedPreferencesModule>(SharedPreferencesModule.MODULE_NAME)
        try {
            val folders = JSONArray(preferences.getString(WATCHLIST_FOLDERS_KEY).ifEmpty { "[]" })
            for (index in 0 until folders.length()) {
                val name = folders.optString(index).orEmpty().trim()
                if (name.isNotEmpty() && folderNames.none { it == name }) folderNames.add(name)
            }
            val assignments = JSONArray(preferences.getString(WATCHLIST_FOLDER_ASSIGNMENTS_KEY).ifEmpty { "[]" })
            for (index in 0 until assignments.length()) {
                val item = assignments.optJSONObject(index) ?: continue
                val symbol = item.optString("symbol")
                val folder = item.optString("folder")
                if (symbol.isNotEmpty() && folderNames.contains(folder)) stockFolders[symbol] = folder
            }
        } catch (_: Throwable) {
            folderNames.clear()
            stockFolders.clear()
        }
    }

    private fun persistFolders() {
        val preferences = acquireModule<SharedPreferencesModule>(SharedPreferencesModule.MODULE_NAME)
        val folders = JSONArray()
        folderNames.forEach { folders.put(it) }
        val assignments = JSONArray()
        stockFolders.forEach { (symbol, folder) ->
            if (folderNames.contains(folder)) assignments.put(JSONObject().apply {
                put("symbol", symbol); put("folder", folder)
            })
        }
        preferences.setString(WATCHLIST_FOLDERS_KEY, folders.toString())
        preferences.setString(WATCHLIST_FOLDER_ASSIGNMENTS_KEY, assignments.toString())
    }

    private fun removeSavedStock(stock: Stock) {
        val symbol = watchlistSymbol(stock)
        savedSymbols.removeAll { it.equals(symbol, ignoreCase = true) }
        stockFolders.remove(symbol)
        StockRepository.removeFromWatchlist(stock.code)
        persistWatchlistSymbols()
        persistFolders()
        syncVisibleStocks()
    }

    private fun selectSearchResult(result: StockSearchResult) {
        if (isSearching) return
        isSearching = true
        searchStatus = "正在加载 ${result.name} 的实时行情与20日走势…"
        MarketDataEngine.loadSymbols(acquireModule(NetworkModule.MODULE_NAME), listOf(result.symbol)) { stocks, message ->
            isSearching = false
            val stock = stocks?.firstOrNull()
            if (stock == null) {
                searchStatus = "$message · 点击该结果重试"
                return@loadSymbols
            }
            StockRepository.replaceStocks(listOf(stock))
            if (!savedSymbols.contains(result.symbol)) savedSymbols.add(result.symbol)
            persistWatchlistSymbols()
            syncVisibleStocks()
            searchResults.clear()
            searchStatus = "已加入自选：${stock.name} · 真实20日行情"
            acquireModule<RouterModule>(RouterModule.MODULE_NAME)
                .openPage("StockDetail", JSONObject().apply { put("code", stock.code) })
        }
    }

    private fun restoreWatchlistSymbols() {
        val preferences = acquireModule<SharedPreferencesModule>(SharedPreferencesModule.MODULE_NAME)
        val persisted = preferences.getString(StockRepository.WATCHLIST_SYMBOLS_KEY)
            .split(",").map { it.trim() }.filter { MarketDataEngine.isSupportedSymbol(it) }
        val initialized = preferences.getString(StockRepository.WATCHLIST_INITIALIZED_KEY) == "1"
        savedSymbols.clear()
        if (initialized) {
            savedSymbols.addAll(persisted.distinct().take(20))
        } else {
            // Migrate existing installs: the original four rows were implicit,
            // while searched stocks were the only symbols saved in preferences.
            savedSymbols.addAll((StockRepository.getStockList().map(::watchlistSymbol) + persisted)
                .filter { MarketDataEngine.isSupportedSymbol(it) }.distinct().take(20))
            preferences.setString(StockRepository.WATCHLIST_INITIALIZED_KEY, "1")
            persistWatchlistSymbols()
        }
        StockRepository.getStockList().filter { stock ->
            savedSymbols.none { it.equals(watchlistSymbol(stock), ignoreCase = true) }
        }.forEach { StockRepository.removeFromWatchlist(it.code) }
    }

    private fun persistWatchlistSymbols() {
        val preferences = acquireModule<SharedPreferencesModule>(SharedPreferencesModule.MODULE_NAME)
        preferences.setString(StockRepository.WATCHLIST_INITIALIZED_KEY, "1")
        preferences.setString(StockRepository.WATCHLIST_SYMBOLS_KEY, savedSymbols.distinct().takeLast(20).joinToString(","))
    }

    private fun watchlistSymbol(stock: Stock): String = stock.marketSymbol.ifEmpty {
        MarketDataEngine.marketSymbolForCode(stock.code).orEmpty()
    }

    private fun syncVisibleStocks() {
        stockList.clear()
        stockList.addAll(StockRepository.getStockList())
        refreshDisplayedStocks()
    }
}
