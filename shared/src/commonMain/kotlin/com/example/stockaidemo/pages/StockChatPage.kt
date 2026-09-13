package com.example.stockaidemo.pages

import com.example.stockaidemo.base.BasePager
import com.example.stockaidemo.components.ChatBubble
import com.example.stockaidemo.components.StockComparisonCard
import com.example.stockaidemo.components.StockInsightCard
import com.example.stockaidemo.components.RiskRankingCard
import com.example.stockaidemo.data.AIChatEngine
import com.example.stockaidemo.data.MarketDataEngine
import com.example.stockaidemo.data.StockRepository
import com.example.stockaidemo.model.ChatMessage
import com.example.stockaidemo.model.Stock
import com.example.stockaidemo.model.StockSearchResult
import com.example.stockaidemo.model.formatPriceValue
import com.tencent.kuikly.core.annotations.Page
import com.tencent.kuikly.core.base.*
import com.tencent.kuikly.core.directives.vfor
import com.tencent.kuikly.core.directives.vif
import com.tencent.kuikly.core.module.NetworkModule
import com.tencent.kuikly.core.module.RouterModule
import com.tencent.kuikly.core.module.SharedPreferencesModule
import com.tencent.kuikly.core.nvi.serialization.json.JSONArray
import com.tencent.kuikly.core.nvi.serialization.json.JSONObject
import com.tencent.kuikly.core.reactive.handler.*
import com.tencent.kuikly.core.views.*

/** Task 2：多轮 AI 股票问答、流式回答、结构化行情卡片与本地持久化。 */
@Page("StockChat")
internal class StockChatPage : BasePager() {
    companion object {
        private const val CHAT_HISTORY_KEY = "stock_ai_chat_history_v2"
        private const val CONVERSATIONS_KEY = "stock_ai_conversations_v3"
        private const val CURRENT_CONVERSATION_KEY = "stock_ai_current_conversation_v3"
        private const val MAX_PERSISTED_MESSAGES = 40
        private const val MAX_CONVERSATIONS = 30
    }

    private data class ConversationRecord(
        val id: String,
        val title: String,
        val messages: List<ChatMessage>
    )

    private var messages by observableList<ChatMessage>()
    private var engineOnline by observable(false)
    private var engineIssue by observable("")
    private var marketStatus by observable("行情载入中")
    private var marketRevision by observable(0)
    private var historyDrawerVisible by observable(false)
    private var isManagingConversations by observable(false)
    private var conversationRecords by observableList<ConversationRecord>()
    private var displayedConversationRecords by observableList<ConversationRecord>()
    private var currentConversationId = ""
    private var nextConversationNumber = 1
    private var inputText = ""
    private var nextMessageId = 1
    private var enteredFromDetail = false
    private var originStockCode = ""
    private lateinit var inputRef: ViewRef<InputView>
    private lateinit var chatListRef: ViewRef<ListView<*, *>>
    private var chatContentHeight = 0f
    private var pendingScrollToLatest = false
    private var pendingScrollAnimated = false

    override fun created() {
        super.created()
        enteredFromDetail = pagerData.params.optString("origin") == "detail"
        originStockCode = pagerData.params.optString("originStockCode")
        restoreConversationStore()
        val network = acquireModule<NetworkModule>(NetworkModule.MODULE_NAME)
        AIChatEngine.checkAvailability(network) {
            engineOnline = it
            if (!it) engineIssue = "offline"
        }
        rehydrateConversationStocks(network)
        repairPersistedComparisons(network)
        val watchlistSymbols = StockRepository.getStockList().mapNotNull { stock ->
            stock.marketSymbol.takeIf { MarketDataEngine.isSupportedSymbol(it) }
                ?: MarketDataEngine.marketSymbolForCode(stock.code)
        }
        MarketDataEngine.loadSymbols(network, watchlistSymbols) { stocks, _ ->
            if (stocks != null) {
                StockRepository.replaceStocks(stocks)
                marketRevision += 1
                marketStatus = if (stocks.any { it.history.size >= 5 }) "真实20日行情" else "真实即时报价"
            } else marketStatus = "快照行情"
        }
        val initialQuestion = pagerData.params.optString("question")
        val forceQuestion = pagerData.params.optBoolean("forceQuestion")
        val lastUserQuestion = messages.lastOrNull { it.role == "user" }?.content.orEmpty()
        if (initialQuestion.isNotEmpty() && (forceQuestion || initialQuestion != lastUserQuestion)) submitQuestion(initialQuestion)
    }

    override fun pageDidAppear() {
        super.pageDidAppear()
        scrollToLatest(false)
    }

    override fun body(): ViewBuilder {
        val ctx = this
        return {
            attr { flex(1f); backgroundColor(Color(0xFFF3F6FB)) }
            View {
                attr {
                    height(62f + ctx.getPager().pageData.statusBarHeight)
                    paddingTop(ctx.getPager().pageData.statusBarHeight)
                    paddingLeft(12f); paddingRight(12f)
                    flexDirectionRow(); alignItemsCenter(); justifyContentSpaceBetween()
                    backgroundColor(Color.WHITE)
                }
                View {
                    attr { width(52f); height(62f); justifyContentCenter(); alignItemsFlexStart() }
                    View {
                        attr { size(38f, 38f); borderRadius(19f); allCenter(); backgroundColor(Color(0xFFF1F5F9)) }
                        event { click { ctx.historyDrawerVisible = true } }
                        Text { attr { text("☰"); fontSize(18f); color(Color(0xFF334155)) } }
                    }
                }
                View {
                    attr { flex(1f); flexDirectionColumn(); alignItemsCenter() }
                    Text { attr { text("知势"); fontSize(17f); fontWeightBold(); color(Color(0xFF0F172A)) } }
                    Text {
                        attr {
                            text(when (ctx.engineIssue) {
                                "quota" -> "AI 股票投研助手 · 额度受限"
                                "request" -> "AI 股票投研助手 · 请求失败"
                                else -> if (ctx.engineOnline) "AI 股票投研助手 · 在线" else "AI 股票投研助手 · 离线"
                            })
                            fontSize(10f); marginTop(2f)
                            color(when (ctx.engineIssue) {
                                "quota", "request" -> Color(0xFFF59E0B)
                                else -> if (ctx.engineOnline) Color(0xFF10B981) else Color(0xFF64748B)
                            })
                        }
                    }
                }
                View {
                    attr { width(52f); height(62f); justifyContentCenter(); alignItemsFlexEnd() }
                    event { click { ctx.startNewConversation() } }
                    View {
                        attr { size(38f, 38f); borderRadius(19f); allCenter(); backgroundColor(Color(0xFFEFF6FF)) }
                        Text { attr { text("＋"); fontSize(22f); color(Color(0xFF2563EB)) } }
                    }
                }
            }

            List {
                ref { ctx.chatListRef = it }
                attr { flex(1f); padding(12f) }
                event {
                    contentSizeChanged { _, height ->
                        ctx.chatContentHeight = height
                        if (ctx.pendingScrollToLatest) ctx.applyLatestScroll()
                    }
                }
                View {
                    attr {
                        padding(14f); marginBottom(10f); borderRadius(14f)
                        backgroundLinearGradient(Direction.TO_RIGHT, ColorStop(Color(0xFF1D4ED8), 0f), ColorStop(Color(0xFF06B6D4), 1f))
                    }
                    Text { attr { text("今日市场 AI 快报"); fontSize(16f); fontWeightBold(); color(Color.WHITE) } }
                    Text { attr { text("真实行情 · 事件影响 · 20日趋势对比"); fontSize(12f); marginTop(5f); color(Color(0xFFE0F2FE)) } }
                    View quickRow@{
                        attr { flexDirectionRow(); marginTop(12f) }
                        with(this@StockChatPage) {
                            this@quickRow.QuickQuestion("分析腾讯控股", "分析腾讯控股的短期趋势和风险")
                            this@quickRow.QuickQuestion("对比两只股票", "对比平安银行和贵州茅台，哪只短期动能更强？")
                        }
                    }
                    View riskRow@{
                        attr { flexDirectionRow(); marginTop(8f) }
                        with(this@StockChatPage) {
                            this@riskRow.QuickQuestion("查看风险排行", "当前自选股里风险最高的是谁？请给出风险排行")
                        }
                    }
                }

                vfor({ ctx.messages }) { message ->
                    ctx.marketRevision
                    val comparisonStocks = if (message.showComparisonCard && message.comparisonCodes.isNotEmpty()) {
                        ctx.resolveStocks(message.comparisonCodes)
                    } else emptyList()
                    View {
                        attr {
                            marginBottom(12f); flexDirectionColumn()
                            if (message.role == "user") alignItemsFlexEnd() else alignItemsFlexStart()
                        }
                        Text {
                            attr { text(if (message.role == "user") "我" else "AI 助手"); fontSize(10f); marginBottom(4f); color(Color(0xFF94A3B8)) }
                        }
                        // A successful comparison is rendered as a purpose-built UI instead of
                        // repeating the model's long markdown report above the same data.
                        if (comparisonStocks.size < 2 || message.role == "user" || message.isLoading || message.retryQuestion.isNotEmpty()) {
                            ChatBubble(message, ctx.pagerData.pageViewWidth - 64f, ctx.evidenceFor(message)) { ctx.retryMessage(message) }
                        }

                        if (message.stockChoices.isNotEmpty()) {
                            ctx.decodeStockChoices(message.stockChoices).forEach { choice ->
                                View {
                                    attr {
                                        width(ctx.pagerData.pageViewWidth - 64f)
                                        marginTop(7f); padding(11f); borderRadius(12f)
                                        flexDirectionRow(); justifyContentSpaceBetween(); alignItemsCenter()
                                        backgroundColor(Color.WHITE)
                                    }
                                    Text {
                                        attr {
                                            text("${choice.name}  ${choice.code}")
                                            fontSize(12f); fontWeightBold(); color(Color(0xFF1E293B))
                                        }
                                    }
                                    Text { attr { text("选择 ›"); fontSize(11f); color(Color(0xFF2563EB)) } }
                                    event { click { ctx.chooseAmbiguousStock(message, choice) } }
                                }
                            }
                        }

                        if (message.showRiskRankingCard) {
                            RiskRankingCard(StockRepository.getStockList(), ctx.pagerData.pageViewWidth - 50f) { stock -> ctx.openStock(stock) }
                        } else if (message.showComparisonCard && message.comparisonCodes.isNotEmpty()) {
                            if (comparisonStocks.size >= 2) {
                                StockComparisonCard(
                                    comparisonStocks,
                                    ctx.pagerData.pageViewWidth - 50f
                                ) { stock -> ctx.openStock(stock) }
                            }
                        } else if (message.showStockCard && message.stockCode.isNotEmpty()) {
                            val stock = StockRepository.getStockByCode(message.stockCode)
                            if (stock != null) {
                                val actionText = when {
                                    !ctx.enteredFromDetail -> "查看详情 ›"
                                    stock.code == ctx.originStockCode -> "返回原详情 ›"
                                    else -> "查看详情 ›"
                                }
                                StockInsightCard(message, stock, ctx.pagerData.pageViewWidth - 50f, actionText, true) { ctx.openStock(stock) }
                                if (!StockRepository.isInWatchlist(stock.code)) {
                                    View {
                                        attr {
                                            marginTop(7f); paddingLeft(12f); paddingRight(12f)
                                            paddingTop(7f); paddingBottom(7f); borderRadius(12f)
                                            backgroundColor(Color(0xFFDBEAFE))
                                        }
                                        Text { attr { text("＋ 加入自选"); fontSize(11f); fontWeightBold(); color(Color(0xFF1D4ED8)) } }
                                        event { click { ctx.addToWatchlist(stock) } }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            View {
                attr {
                    width(ctx.pagerData.pageViewWidth - 32f)
                    height(58f); padding(6f)
                    marginLeft(16f); marginRight(16f); marginTop(10f)
                    marginBottom(10f + ctx.getPager().pageData.safeAreaInsets.bottom)
                    flexDirectionRow(); alignItemsCenter()
                    borderRadius(29f); backgroundColor(Color.WHITE)
                    boxShadow(BoxShadow(0f, 5f, 18f, Color(0x260F172A)), true)
                }
                View {
                    attr { flex(1f); height(46f); paddingLeft(12f); paddingRight(12f); borderRadius(23f); backgroundColor(Color.WHITE) }
                    Input {
                        ref { ctx.inputRef = it }
                        attr {
                            flex(1f); fontSize(14f); color(Color(0xFF1E293B))
                            placeholder("问个股、趋势、风险或股票对比…"); placeholderColor(Color(0xFF94A3B8))
                            imeNoFullscreen(true); returnKeyTypeSend()
                        }
                        event { textDidChange { ctx.inputText = it.text }; inputReturn { ctx.submitQuestion(it.text) } }
                    }
                }
                View {
                    attr { size(56f, 42f); marginLeft(8f); borderRadius(21f); allCenter(); backgroundColor(Color(0xFF2563EB)) }
                    Text { attr { text("发送"); fontSize(14f); fontWeightBold(); color(Color.WHITE) } }
                    event { click { ctx.submitQuestion(ctx.inputText) } }
                }
            }

            vif({ ctx.historyDrawerVisible }) {
                View {
                    attr {
                        positionAbsolute(); top(0f); right(0f); bottom(0f); left(0f)
                        backgroundColor(Color(0x66000000))
                    }
                    event { click { ctx.historyDrawerVisible = false } }
                }
                View {
                    attr {
                        positionAbsolute(); top(0f); bottom(0f); left(0f)
                        width(ctx.pagerData.pageViewWidth * 0.82f)
                        paddingTop(ctx.getPager().pageData.statusBarHeight + 12f)
                        paddingLeft(16f); paddingRight(16f)
                        backgroundColor(Color(0xFFF8FAFC))
                    }
                    View {
                        attr { height(44f); flexDirectionRow(); alignItemsCenter(); justifyContentSpaceBetween() }
                        Text { attr { text("对话记录"); fontSize(18f); fontWeightBold(); color(Color(0xFF111827)) } }
                        View {
                            attr { flexDirectionRow(); alignItemsCenter() }
                            vif({ ctx.conversationRecords.isNotEmpty() }) {
                                View {
                                    attr { height(32f); paddingLeft(10f); paddingRight(10f); borderRadius(16f); allCenter(); backgroundColor(Color(0xFFF1F5F9)) }
                                    Text {
                                        attr {
                                            text(if (ctx.isManagingConversations) "完成" else "编辑")
                                            fontSize(11f); fontWeightMedium()
                                            color(if (ctx.isManagingConversations) Color(0xFF2563EB) else Color(0xFF64748B))
                                        }
                                    }
                                    event { click { ctx.isManagingConversations = !ctx.isManagingConversations } }
                                }
                            }
                            View {
                                attr { size(32f, 32f); marginLeft(7f); borderRadius(16f); allCenter(); backgroundColor(Color(0xFFE2E8F0)) }
                                Text { attr { text("×"); fontSize(19f); color(Color(0xFF475569)) } }
                                event {
                                    click {
                                        ctx.isManagingConversations = false
                                        ctx.historyDrawerVisible = false
                                    }
                                }
                            }
                        }
                    }
                    View {
                        attr {
                            marginTop(8f); height(42f); borderRadius(12f); allCenter()
                            backgroundColor(Color(0xFF2563EB))
                        }
                        Text { attr { text("＋ 新对话"); fontSize(13f); fontWeightBold(); color(Color.WHITE) } }
                        event { click { ctx.startNewConversation() } }
                    }
                    List {
                        attr { flex(1f); marginTop(12f) }
                        vfor({ ctx.displayedConversationRecords }) { conversation ->
                            View {
                                attr {
                                    marginBottom(7f); padding(12f); borderRadius(12f)
                                    flexDirectionRow(); alignItemsCenter()
                                    backgroundColor(
                                        if (conversation.id == ctx.currentConversationId) Color(0xFFDBEAFE)
                                        else Color.WHITE
                                    )
                                }
                                View {
                                    attr { flex(1f); flexDirectionColumn(); justifyContentCenter() }
                                    Text {
                                        attr {
                                            text(conversation.title)
                                            fontSize(12f); fontWeightMedium(); color(Color(0xFF1E293B))
                                        }
                                    }
                                    Text {
                                        attr {
                                            text("${conversation.messages.count { it.role == "user" }} 条提问")
                                            fontSize(9f); marginTop(4f); color(Color(0xFF94A3B8))
                                        }
                                    }
                                }
                                vif({ ctx.isManagingConversations }) {
                                    View {
                                        attr {
                                            width(48f); height(30f); marginLeft(8f); borderRadius(15f); allCenter()
                                            backgroundColor(Color(0xFFFFE4E6))
                                        }
                                        Text { attr { text("删除"); fontSize(10f); fontWeightMedium(); color(Color(0xFFE11D48)) } }
                                        event { click { ctx.deleteConversation(conversation.id) } }
                                    }
                                }
                                event { click { if (!ctx.isManagingConversations) ctx.openConversation(conversation.id) } }
                            }
                        }
                    }
                    val originName = StockRepository.getStockByCode(ctx.originStockCode)?.name.orEmpty()
                    View {
                        attr {
                            height(72f + ctx.getPager().pageData.safeAreaInsets.bottom)
                            paddingTop(8f); paddingBottom(8f + ctx.getPager().pageData.safeAreaInsets.bottom)
                        }
                        View {
                            attr {
                                height(56f); paddingLeft(10f); paddingRight(12f)
                                flexDirectionRow(); alignItemsCenter()
                                borderRadius(16f); backgroundColor(Color.WHITE)
                                boxShadow(BoxShadow(0f, 4f, 14f, Color(0x1F0F172A)), true)
                            }
                            View {
                                attr {
                                    size(36f, 36f); borderRadius(18f); allCenter()
                                    backgroundColor(Color(0xFFEFF6FF))
                                }
                                Text { attr { text("‹"); fontSize(25f); color(Color(0xFF2563EB)) } }
                            }
                            View {
                                attr { flex(1f); marginLeft(10f); flexDirectionColumn(); justifyContentCenter() }
                                Text {
                                    attr {
                                        text(
                                            if (ctx.enteredFromDetail) {
                                                if (originName.isNotEmpty()) "返回${originName}详情" else "返回股票详情"
                                            } else "返回行情首页"
                                        )
                                        fontSize(13f); fontWeightBold(); color(Color(0xFF1E293B))
                                    }
                                }
                                Text {
                                    attr {
                                        text(if (ctx.enteredFromDetail) "继续查看完整行情与20日走势" else "查看自选股票与市场雷达")
                                        fontSize(9f); marginTop(3f); color(Color(0xFF94A3B8))
                                    }
                                }
                            }
                            Text { attr { text("›"); fontSize(20f); color(Color(0xFF94A3B8)) } }
                            event { click { ctx.acquireModule<RouterModule>(RouterModule.MODULE_NAME).closePage() } }
                        }
                    }
                }
            }
        }
    }

    private fun ViewContainer<*, *>.QuickQuestion(label: String, question: String) {
        View {
            attr { marginRight(8f); paddingLeft(10f); paddingRight(10f); paddingTop(6f); paddingBottom(6f); borderRadius(12f); backgroundColor(Color(0x33FFFFFF)) }
            Text { attr { text(label); fontSize(11f); color(Color.WHITE) } }
            event { click { this@StockChatPage.submitQuestion(question) } }
        }
    }

    private fun resolveStocks(codes: String): List<Stock> = codes.split(",").mapNotNull { StockRepository.getStockByCode(it.trim()) }

    /** Restores temporary quote context for persisted AI cards after an app restart. */
    private fun rehydrateConversationStocks(network: NetworkModule) {
        val codes = messages.flatMap { message ->
            listOf(message.stockCode) + message.comparisonCodes.split(",")
        }.map { it.trim() }.filter { it.isNotEmpty() && StockRepository.getStockByCode(it) == null }
            .distinct()
        val symbols = codes.mapNotNull { MarketDataEngine.marketSymbolForCode(it) }
        if (symbols.isEmpty()) return
        MarketDataEngine.loadSymbols(network, symbols) { stocks, _ ->
            if (!stocks.isNullOrEmpty()) {
                StockRepository.cacheTransientStocks(stocks)
                marketRevision += 1
            }
        }
    }

    /**
     * Older app versions persisted only the first two comparison codes. Repair
     * those cards from their original user question instead of showing stale,
     * contradictory two-stock results forever after an upgrade.
     */
    private fun repairPersistedComparisons(network: NetworkModule) {
        val repairs = messages.indices.mapNotNull { index ->
            val message = messages[index]
            if (message.role != "assistant" || !message.showComparisonCard) return@mapNotNull null
            val question = messages.take(index).lastOrNull { it.role == "user" }?.content.orEmpty()
            if (!AIChatEngine.isComparisonIntent(question)) null else Pair(message.id, question)
        }.distinctBy { it.second }

        fun repairNext(index: Int) {
            if (index >= repairs.size) return
            val (messageId, question) = repairs[index]
            MarketDataEngine.loadQuestionStocks(network, question) { loadedStocks ->
                if (loadedStocks.isNotEmpty()) StockRepository.cacheTransientStocks(loadedStocks)
                val compared = AIChatEngine.namedStocks(question)
                val messageIndex = messages.indexOfFirst { it.id == messageId }
                if (messageIndex >= 0 && compared.size >= 2) {
                    val message = messages[messageIndex]
                    val repairedCodes = compared.joinToString(",") { it.code }
                    val previousCount = message.comparisonCodes.split(",").count { it.isNotBlank() }
                    if (message.comparisonCodes != repairedCodes) {
                        val contradictory = message.content.contains("缺少") ||
                            message.content.contains("无法完整比较") || message.content.contains("仅包含")
                        messages[messageIndex] = message.copy(
                            comparisonCodes = repairedCodes,
                            content = if (contradictory && compared.size > previousCount) {
                                recoveredComparisonMarkdown(compared)
                            } else message.content
                        )
                        marketRevision += 1
                        persistMessages()
                        scrollToLatest(false)
                    }
                }
                repairNext(index + 1)
            }
        }
        repairNext(0)
    }

    private fun recoveredComparisonMarkdown(stocks: List<Stock>): String {
        val rows = stocks.joinToString("\n") { stock ->
            val sign = if (stock.changePercent >= 0) "+" else ""
            "- **${stock.name}(${stock.code})**：${stock.formatPrice()}，$sign${formatPriceValue(stock.changePercent)}%，${stock.riskLabel()}"
        }
        return "### ${stocks.size}只股票行情对比\n$rows\n\n已根据原问题补全全部股票的真实行情；跨行业对比应优先参考涨跌幅、振幅与价格位置。"
    }

    private fun openStock(stock: Stock) {
        if (enteredFromDetail && stock.code == originStockCode) {
            acquireModule<RouterModule>(RouterModule.MODULE_NAME).closePage()
            return
        }
        acquireModule<RouterModule>(RouterModule.MODULE_NAME).openPage("StockDetail", JSONObject().apply {
            put("code", stock.code)
            put("origin", "chat")
        })
    }

    private fun addToWatchlist(stock: Stock) {
        StockRepository.addToWatchlist(stock)
        val preferences = acquireModule<SharedPreferencesModule>(SharedPreferencesModule.MODULE_NAME)
        val persisted = preferences.getString(StockRepository.WATCHLIST_SYMBOLS_KEY)
            .split(",").map { it.trim() }.filter { MarketDataEngine.isSupportedSymbol(it) }.toMutableList()
        val symbols = if (preferences.getString(StockRepository.WATCHLIST_INITIALIZED_KEY) == "1") {
            persisted
        } else {
            (StockRepository.getStockList().mapNotNull { item ->
                item.marketSymbol.takeIf { MarketDataEngine.isSupportedSymbol(it) }
                    ?: MarketDataEngine.marketSymbolForCode(item.code)
            } + persisted).distinct().toMutableList()
        }
        if (symbols.none { it.equals(stock.marketSymbol, ignoreCase = true) }) symbols.add(stock.marketSymbol)
        preferences.setString(StockRepository.WATCHLIST_INITIALIZED_KEY, "1")
        preferences.setString(StockRepository.WATCHLIST_SYMBOLS_KEY, symbols.distinct().takeLast(20).joinToString(","))
        // vfor keeps stable message subtrees; replace matching items so their
        // watchlist-dependent action is rendered immediately after the tap.
        messages.indices.filter { messages[it].stockCode == stock.code }.forEach { index ->
            messages[index] = messages[index].copy()
        }
        marketRevision += 1
        marketStatus = "已加入自选：${stock.name}"
        scrollToLatest(false)
    }

    private fun evidenceFor(message: ChatMessage): String {
        val stocks = when {
            message.comparisonCodes.isNotEmpty() -> resolveStocks(message.comparisonCodes)
            message.stockCode.isNotEmpty() -> listOfNotNull(StockRepository.getStockByCode(message.stockCode))
            else -> emptyList()
        }
        if (stocks.isEmpty()) return "内容仅供教学参考，不构成投资建议"
        val latest = stocks.maxByOrNull { it.updatedAt }
        val historyBasis = if (stocks.any { it.history.isNotEmpty() }) "历史收盘" else "当前快照"
        return "行情：${latest?.dataSource} · ${latest?.displayUpdatedAt()}\n依据：最新价、涨跌幅、最高/最低、成交量、$historyBasis"
    }

    private fun submitQuestion(rawQuestion: String, addUserMessage: Boolean = true) {
        val question = rawQuestion.trim()
        if (question.isEmpty() || messages.any { it.isLoading }) return
        inputText = ""
        if (::inputRef.isInitialized) { inputRef.view?.setText(""); inputRef.view?.blur() }
        if (addUserMessage) { messages.add(ChatMessage(id = nextMessageId++, role = "user", content = question)); persistMessages() }
        val loadingId = nextMessageId++
        messages.add(ChatMessage(id = loadingId, role = "assistant", content = "", isLoading = true))
        scrollToLatest(true)
        val network = acquireModule<NetworkModule>(NetworkModule.MODULE_NAME)
        MarketDataEngine.resolveQuestionStocks(network, question) { resolution ->
            if (resolution.ambiguousTerm.isNotEmpty() && resolution.choices.isNotEmpty()) {
                val loadingIndex = messages.indexOfFirst { it.id == loadingId }
                if (loadingIndex >= 0) messages.removeAt(loadingIndex)
                messages.add(ChatMessage(
                    id = nextMessageId++, role = "assistant",
                    content = "### 请确认你说的是哪只“${resolution.ambiguousTerm}”\n找到多个可能的股票。选择后我会自动继续刚才的问题。",
                    source = "股票实体识别",
                    pendingQuestion = question,
                    ambiguousTerm = resolution.ambiguousTerm,
                    stockChoices = encodeStockChoices(resolution.choices)
                ))
                persistMessages()
                scrollToLatest(true)
                return@resolveQuestionStocks
            }
            val loadedStocks = resolution.stocks
            if (loadedStocks.isNotEmpty()) {
                StockRepository.cacheTransientStocks(loadedStocks)
                marketRevision += 1
                marketStatus = if (loadedStocks.any { it.history.size >= 5 }) "真实20日行情" else "真实即时报价"
            }
            if (resolution.errorMessage.isNotEmpty()) {
                val loadingIndex = messages.indexOfFirst { it.id == loadingId }
                if (loadingIndex >= 0) messages.removeAt(loadingIndex)
                messages.add(ChatMessage(
                    id = nextMessageId++, role = "assistant",
                    content = "### 行情获取未完成\n${resolution.errorMessage}\n\n为避免用残缺数据做比较，本次没有调用 AI。请稍后重试。",
                    source = "行情获取失败", retryQuestion = question
                ))
                persistMessages()
                scrollToLatest(true)
                return@resolveQuestionStocks
            }
            startAIRequest(network, question, loadingId)
        }
    }

    private fun chooseAmbiguousStock(message: ChatMessage, choice: StockSearchResult) {
        if (messages.any { it.isLoading }) return
        val messageIndex = messages.indexOfFirst { it.id == message.id }
        if (messageIndex >= 0) messages[messageIndex] = message.copy(stockChoices = "")
        messages.add(ChatMessage(
            id = nextMessageId++, role = "user", content = "选择：${choice.name}（${choice.code}）"
        ))
        persistMessages()
        scrollToLatest(true)
        val network = acquireModule<NetworkModule>(NetworkModule.MODULE_NAME)
        MarketDataEngine.loadSymbols(network, listOf(choice.symbol)) { stocks, _ ->
            val selected = stocks?.firstOrNull()
            if (selected != null) {
                StockRepository.cacheTransientStocks(listOf(selected))
                marketRevision += 1
                marketStatus = if (selected.history.size >= 5) "真实20日行情" else "真实即时报价"
            }
            val resolvedQuestion = message.pendingQuestion.replace(message.ambiguousTerm, choice.name) +
                "（已确认股票代码为${choice.code}）"
            submitQuestion(resolvedQuestion, addUserMessage = false)
        }
    }

    private fun encodeStockChoices(choices: List<StockSearchResult>): String = choices.joinToString(";") {
        listOf(it.symbol, it.code, it.name, it.market, it.pinyin).joinToString("|")
    }

    private fun decodeStockChoices(encoded: String): List<StockSearchResult> = encoded.split(";").mapNotNull { row ->
        val values = row.split("|")
        if (values.size < 4) null else StockSearchResult(
            symbol = values[0], code = values[1], name = values[2], market = values[3],
            pinyin = values.getOrElse(4) { "" }
        )
    }

    private fun startAIRequest(network: NetworkModule, question: String, loadingId: Int) {
        val history = messages.filter { !it.isLoading }.takeLast(8).joinToString("\n") {
            val relation = if (it.stockCode.isNotEmpty()) " [关联股票:${it.stockName}(${it.stockCode})]" else ""
            (if (it.role == "user") "用户：" else "助手：") + it.content + relation
        }
        AIChatEngine.askStreaming(
            network, question, history,
            onPartial = { partial ->
                val index = messages.indexOfFirst { it.id == loadingId }
                if (index >= 0) messages[index] = messages[index].copy(content = partial)
                scrollToLatest(false)
            }
        ) { response ->
            engineIssue = when {
                response.source.contains("额度受限") -> "quota"
                response.source.contains("请求失败") -> "request"
                response.source.contains("离线") -> "offline"
                else -> ""
            }
            engineOnline = engineIssue.isEmpty()
            val loadingIndex = messages.indexOfFirst { it.id == loadingId }
            if (loadingIndex >= 0) messages.removeAt(loadingIndex)
            val stock = StockRepository.getStockByCode(response.stockCode)
            messages.add(ChatMessage(
                id = nextMessageId++, role = "assistant", content = response.markdown,
                source = response.source, stockCode = response.stockCode, comparisonCodes = response.comparisonCodes,
                stockName = stock?.name ?: "", insightTitle = response.insightTitle, insightSummary = response.insightSummary,
                action = response.action, riskLevel = response.riskLevel, showStockCard = response.showStockCard,
                showComparisonCard = response.showComparisonCard, showRiskRankingCard = response.showRiskRankingCard,
                retryQuestion = if (engineIssue.isNotEmpty()) question else ""
            ))
            persistMessages()
            scrollToLatest(true)
        }
    }

    private fun retryMessage(message: ChatMessage) {
        if (messages.any { it.isLoading } || message.retryQuestion.isEmpty()) return
        messages.indexOfFirst { it.id == message.id }.takeIf { it >= 0 }?.let { messages.removeAt(it) }
        persistMessages(); submitQuestion(message.retryQuestion, addUserMessage = false)
    }

    private fun addWelcomeMessage() {
        messages.add(ChatMessage(id = nextMessageId++, role = "assistant", content = "### 你好，我是知势\n你的 AI 股票投研助手。我会结合真实行情、20日走势和近期事件的市场反应回答，也可以并排比较股票或生成风险排行。", source = "在线智能分析"))
    }

    private fun startNewConversation() {
        persistMessages()
        if (messages.none { it.role == "user" }) {
            isManagingConversations = false
            historyDrawerVisible = false
            return
        }
        currentConversationId = "conversation_${nextConversationNumber++}"
        messages.clear()
        nextMessageId = 1
        addWelcomeMessage()
        isManagingConversations = false
        historyDrawerVisible = false
        persistMessages()
        scrollToLatest(false)
    }

    private fun openConversation(id: String) {
        if (id == currentConversationId) {
            historyDrawerVisible = false
            return
        }
        persistMessages()
        val recordIndex = conversationRecords.indexOfFirst { it.id == id }
        if (recordIndex < 0) return
        val record = conversationRecords.removeAt(recordIndex)
        conversationRecords.add(record)
        currentConversationId = id
        messages.clear()
        messages.addAll(record.messages)
        nextMessageId = (messages.maxOfOrNull { it.id } ?: 0) + 1
        historyDrawerVisible = false
        val network = acquireModule<NetworkModule>(NetworkModule.MODULE_NAME)
        rehydrateConversationStocks(network)
        repairPersistedComparisons(network)
        persistMessages()
        scrollToLatest(false)
    }

    private fun deleteConversation(id: String) {
        if (id == currentConversationId && messages.any { it.isLoading }) return
        val deletingCurrent = id == currentConversationId
        val deleteIndex = conversationRecords.indexOfFirst { it.id == id }
        if (deleteIndex < 0) return
        conversationRecords.removeAt(deleteIndex)
        if (deletingCurrent) {
            val replacement = conversationRecords.lastOrNull()
            if (replacement == null) {
                currentConversationId = "conversation_${nextConversationNumber++}"
                messages.clear()
                nextMessageId = 1
                addWelcomeMessage()
            } else {
                currentConversationId = replacement.id
                messages.clear()
                messages.addAll(replacement.messages)
                if (messages.isEmpty()) addWelcomeMessage()
                nextMessageId = (messages.maxOfOrNull { it.id } ?: 0) + 1
                val network = acquireModule<NetworkModule>(NetworkModule.MODULE_NAME)
                rehydrateConversationStocks(network)
                repairPersistedComparisons(network)
            }
        }
        if (conversationRecords.isEmpty()) isManagingConversations = false
        persistMessages()
        scrollToLatest(false)
    }

    private fun scrollToLatest(animated: Boolean) {
        pendingScrollToLatest = true
        pendingScrollAnimated = animated
        getPager().addNextTickTask {
            applyLatestScroll()
        }
    }

    private fun applyLatestScroll() {
        if (!pendingScrollToLatest || !::chatListRef.isInitialized) return
        val viewportHeight = chatListRef.view?.frame?.height ?: 0f
        if (chatContentHeight <= 0f || viewportHeight <= 0f) return
        chatListRef.view?.setContentOffset(
            0f,
            (chatContentHeight - viewportHeight).coerceAtLeast(0f),
            pendingScrollAnimated
        )
        pendingScrollToLatest = false
    }

    private fun restoreConversationStore() {
        val preferences = acquireModule<SharedPreferencesModule>(SharedPreferencesModule.MODULE_NAME)
        val saved = preferences.getString(CONVERSATIONS_KEY)
        if (saved.isNotEmpty()) {
            try {
                val array = JSONArray(saved)
                for (index in 0 until array.length()) {
                    val item = array.optJSONObject(index) ?: continue
                    val id = item.optString("id")
                    val messageArray = item.optJSONArray("messages") ?: JSONArray()
                    if (id.isEmpty()) continue
                    val restored = decodeMessages(messageArray)
                    // Older builds persisted the welcome-only draft as a real
                    // conversation named "新对话". A conversation only belongs
                    // in history after the user has actually asked something.
                    if (restored.none { it.role == "user" }) {
                        id.substringAfterLast("_").toIntOrNull()?.let {
                            nextConversationNumber = maxOf(nextConversationNumber, it + 1)
                        }
                        continue
                    }
                    conversationRecords.add(ConversationRecord(
                        id = id,
                        title = item.optString("title").ifEmpty { conversationTitle(restored) },
                        messages = restored
                    ))
                    id.substringAfterLast("_").toIntOrNull()?.let {
                        nextConversationNumber = maxOf(nextConversationNumber, it + 1)
                    }
                }
            } catch (_: Throwable) {
                conversationRecords.clear()
            }
        }

        if (conversationRecords.isEmpty()) {
            if (!restoreMessages()) addWelcomeMessage()
            currentConversationId = "conversation_${nextConversationNumber++}"
            persistMessages()
            return
        }

        val savedCurrentId = preferences.getString(CURRENT_CONVERSATION_KEY)
        val current = conversationRecords.firstOrNull { it.id == savedCurrentId }
        if (current != null) {
            currentConversationId = current.id
            messages.addAll(current.messages)
        } else if (savedCurrentId.isNotEmpty()) {
            // Preserve an intentionally blank current draft without exposing it
            // as a zero-question row in the history drawer.
            currentConversationId = savedCurrentId
            addWelcomeMessage()
        } else {
            currentConversationId = conversationRecords.last().id
            messages.addAll(conversationRecords.last().messages)
        }
        if (messages.isEmpty()) addWelcomeMessage()
        nextMessageId = (messages.maxOfOrNull { it.id } ?: 0) + 1
        // Rewrite once so legacy welcome-only records disappear from storage too.
        persistMessages()
    }

    private fun restoreMessages(): Boolean {
        val saved = acquireModule<SharedPreferencesModule>(SharedPreferencesModule.MODULE_NAME).getString(CHAT_HISTORY_KEY)
        if (saved.isEmpty()) return false
        return try {
            val array = JSONArray(saved)
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val content = item.optString("content")
                val role = item.optString("role")
                if (content.isEmpty() || (role != "user" && role != "assistant")) continue
                messages.add(decodeMessage(item, nextMessageId++))
            }
            messages.isNotEmpty()
        } catch (_: Throwable) { messages.clear(); nextMessageId = 1; false }
    }

    private fun persistMessages() {
        if (currentConversationId.isEmpty()) return
        val snapshot = messages.filter { !it.isLoading }.takeLast(MAX_PERSISTED_MESSAGES)
        val index = conversationRecords.indexOfFirst { it.id == currentConversationId }
        if (snapshot.any { it.role == "user" }) {
            val record = ConversationRecord(currentConversationId, conversationTitle(snapshot), snapshot)
            if (index >= 0) conversationRecords[index] = record else conversationRecords.add(record)
        } else if (index >= 0) {
            conversationRecords.removeAt(index)
        }
        while (conversationRecords.size > MAX_CONVERSATIONS) conversationRecords.removeAt(0)
        refreshDisplayedConversations()

        val conversationsJson = JSONArray()
        conversationRecords.forEach { conversation ->
            conversationsJson.put(JSONObject().apply {
                put("id", conversation.id)
                put("title", conversation.title)
                put("messages", encodeMessages(conversation.messages))
            })
        }
        val preferences = acquireModule<SharedPreferencesModule>(SharedPreferencesModule.MODULE_NAME)
        preferences.setString(CONVERSATIONS_KEY, conversationsJson.toString())
        preferences.setString(CURRENT_CONVERSATION_KEY, currentConversationId)
    }

    private fun refreshDisplayedConversations() {
        displayedConversationRecords.clear()
        displayedConversationRecords.addAll(conversationRecords.reversed())
    }

    private fun conversationTitle(items: List<ChatMessage>): String {
        val firstQuestion = items.firstOrNull { it.role == "user" }?.content?.trim().orEmpty()
        return if (firstQuestion.isEmpty()) "新对话" else firstQuestion.take(22)
    }

    private fun encodeMessages(items: List<ChatMessage>): JSONArray = JSONArray().apply {
        items.filter { !it.isLoading }.takeLast(MAX_PERSISTED_MESSAGES).forEach { message ->
            put(JSONObject().apply {
                put("id", message.id); put("role", message.role); put("content", message.content); put("source", message.source)
                put("stockCode", message.stockCode); put("comparisonCodes", message.comparisonCodes); put("stockName", message.stockName)
                put("insightTitle", message.insightTitle); put("insightSummary", message.insightSummary)
                put("action", message.action); put("riskLevel", message.riskLevel)
                put("showStockCard", message.showStockCard); put("showComparisonCard", message.showComparisonCard)
                put("showRiskRankingCard", message.showRiskRankingCard)
                put("pendingQuestion", message.pendingQuestion); put("ambiguousTerm", message.ambiguousTerm)
                put("stockChoices", message.stockChoices); put("retryQuestion", message.retryQuestion)
            })
        }
    }

    private fun decodeMessages(array: JSONArray): List<ChatMessage> {
        val restored = mutableListOf<ChatMessage>()
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            val role = item.optString("role")
            if (item.optString("content").isEmpty() || (role != "user" && role != "assistant")) continue
            restored.add(decodeMessage(item, item.optInt("id", index + 1)))
        }
        return restored
    }

    private fun decodeMessage(item: JSONObject, fallbackId: Int): ChatMessage = ChatMessage(
        id = item.optInt("id", fallbackId), role = item.optString("role"), content = item.optString("content"),
        source = item.optString("source"), stockCode = item.optString("stockCode"),
        comparisonCodes = item.optString("comparisonCodes"), stockName = item.optString("stockName"),
        insightTitle = item.optString("insightTitle"), insightSummary = item.optString("insightSummary"),
        action = item.optString("action"), riskLevel = item.optString("riskLevel"),
        showStockCard = item.optBoolean("showStockCard"), showComparisonCard = item.optBoolean("showComparisonCard"),
        showRiskRankingCard = item.optBoolean("showRiskRankingCard"), pendingQuestion = item.optString("pendingQuestion"),
        ambiguousTerm = item.optString("ambiguousTerm"), stockChoices = item.optString("stockChoices"),
        retryQuestion = item.optString("retryQuestion")
    )
}
