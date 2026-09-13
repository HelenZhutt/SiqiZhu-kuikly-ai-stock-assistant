package com.example.stockaidemo.pages

import com.tencent.kuikly.core.annotations.Page
import com.tencent.kuikly.core.base.*
import com.tencent.kuikly.core.module.RouterModule
import com.tencent.kuikly.core.module.NetworkModule
import com.tencent.kuikly.core.reactive.handler.*
import com.tencent.kuikly.core.views.*
import com.example.stockaidemo.base.BasePager
import com.example.stockaidemo.data.AIAnalysisEngine
import com.example.stockaidemo.data.StockRepository
import com.example.stockaidemo.model.AIAnalysisResult
import com.example.stockaidemo.model.Stock
import com.example.stockaidemo.model.formatPriceValue
import com.example.stockaidemo.model.aiSignalLabel
import com.example.stockaidemo.components.PriceHistoryChart

@Page("StockDetail")
internal class StockDetailPage : BasePager() {

    private var stock: Stock? = null

    private var aiResult by observable<AIAnalysisResult?>(null)
    private var selectedHistoryDays by observable(20)
    private var enteredFromChat = false

    override fun created() {
        super.created()
        enteredFromChat = pagerData.params.optString("origin") == "chat"
        val currentStock = StockRepository.getStockByCode(pagerData.params.optString("code"))
        stock = currentStock

        if (currentStock == null) {
            return
        }

        // 先放入占位结果，让 Kuikly 在首次构建时创建完整卡片；
        // 网络返回后只更新字段，不依赖异步切换 if/else 视图结构。
        aiResult = loadingResult(currentStock)
        requestAnalysis(currentStock)
    }

    override fun body(): ViewBuilder {
        val ctx = this
        val stock = ctx.stock

        return {
            attr {
                flex(1f)
                backgroundColor(Color(0xFFF5F6FA))
            }

            View {
                attr {
                    height(56f + ctx.getPager().pageData.statusBarHeight)
                    paddingTop(ctx.getPager().pageData.statusBarHeight)
                    flexDirectionRow()
                    alignItemsCenter()
                    paddingLeft(16f)
                    backgroundColor(Color.WHITE)
                }
                Text {
                    attr {
                        text("返回")
                        fontSize(16f)
                        color(Color(0xFF1A66FF))
                    }
                    event {
                        click {
                            ctx.acquireModule<RouterModule>(RouterModule.MODULE_NAME).closePage()
                        }
                    }
                }
                Text {
                    attr {
                        text(stock?.name ?: "个股详情")
                        fontSize(18f)
                        fontWeightBold()
                        marginLeft(12f)
                        color(Color(0xFF1A1A1A))
                    }
                }
            }

            if (stock == null) {
                Text {
                    attr {
                        text("未找到该股票信息")
                        fontSize(14f)
                        color(Color(0xFF999999))
                        margin(20f)
                    }
                }
            } else {
                List {
                    attr {
                        flex(1f)
                        flexDirectionColumn()
                    }
                View {
                    attr {
                        margin(16f)
                        padding(16f)
                        borderRadius(12f)
                        backgroundColor(Color.WHITE)
                        flexDirectionColumn()
                    }

                    Text {
                        attr {
                            text(stock.code)
                            fontSize(12f)
                            color(Color(0xFF999999))
                        }
                    }

                    Text {
                        attr {
                            text(stock.formatPrice())
                            fontSize(32f)
                            fontWeightBold()
                            marginTop(8f)
                            color(if (stock.isUp) Color(0xFFF5222D) else Color(0xFF00A870))
                        }
                    }

                    Text {
                        attr {
                            text(stock.formatChange())
                            fontSize(14f)
                            marginTop(4f)
                            color(if (stock.isUp) Color(0xFFF5222D) else Color(0xFF00A870))
                        }
                    }
                }

                View {
                    attr {
                        marginLeft(16f); marginRight(16f); marginTop(0f)
                        padding(16f); borderRadius(12f); backgroundColor(Color.WHITE)
                    }
                    View {
                        attr { flexDirectionRow(); alignItemsCenter(); justifyContentSpaceBetween() }
                        Text {
                            attr {
                                text("${ctx.selectedHistoryDays}日历史收盘")
                                fontSize(14f); fontWeightBold(); color(Color(0xFF1E293B))
                            }
                        }
                        View {
                            attr { flexDirectionRow() }
                            listOf(5, 20).forEach { days ->
                                View {
                                    attr {
                                        width(44f); height(26f); marginLeft(6f); borderRadius(13f); allCenter()
                                        backgroundColor(if (ctx.selectedHistoryDays == days) Color(0xFF2563EB) else Color(0xFFEFF6FF))
                                    }
                                    Text {
                                        attr {
                                            text("${days}日"); fontSize(10f); fontWeightBold()
                                            color(if (ctx.selectedHistoryDays == days) Color.WHITE else Color(0xFF2563EB))
                                        }
                                    }
                                    event { click { ctx.selectedHistoryDays = days } }
                                }
                            }
                        }
                    }
                    // Canvas content is created once by Kuikly. Keep two fixed chart
                    // instances in the tree so switching the reactive selector changes
                    // both the curve and its date-range label, not only the title.
                    View {
                        attr {
                            visibility(ctx.selectedHistoryDays == 5)
                            height(if (ctx.selectedHistoryDays == 5) 133f else 0f)
                        }
                        PriceHistoryChart(
                            stock = stock,
                            width = ctx.pagerData.pageViewWidth - 64f,
                            height = 86f,
                            dayCount = 5,
                            supportLevel = ctx.aiResult?.supportLevel ?: stock.recentSupportLevel(),
                            resistanceLevel = ctx.aiResult?.resistanceLevel ?: stock.recentResistanceLevel()
                        )
                    }
                    View {
                        attr {
                            visibility(ctx.selectedHistoryDays == 20)
                            height(if (ctx.selectedHistoryDays == 20) 133f else 0f)
                        }
                        PriceHistoryChart(
                            stock = stock,
                            width = ctx.pagerData.pageViewWidth - 64f,
                            height = 86f,
                            dayCount = 20,
                            supportLevel = ctx.aiResult?.supportLevel ?: stock.recentSupportLevel(),
                            resistanceLevel = ctx.aiResult?.resistanceLevel ?: stock.recentResistanceLevel()
                        )
                    }
                    Text {
                        attr {
                            text("来源：${stock.dataSource} · 行情时间：${stock.displayUpdatedAt()}")
                            fontSize(9f); marginTop(5f); color(Color(0xFF64748B))
                        }
                    }
                }

                View {
                    attr {
                        marginLeft(16f)
                        marginRight(16f)
                        padding(16f)
                        borderRadius(12f)
                        backgroundColor(Color.WHITE)
                        flexDirectionColumn()
                    }
                    View {
                        attr { flexDirectionRow(); width(ctx.pagerData.pageViewWidth - 64f) }
                        MarketMetric("最高", formatPriceValue(stock.high))
                        MarketMetric("最低", formatPriceValue(stock.low))
                        MarketMetric("成交量", stock.volume, compact = true)
                    }
                    Text {
                        attr {
                            text("数据来源：${stock.dataSource} · 行情时间：${stock.displayUpdatedAt()}\n依据字段：最新价、涨跌幅、最高/最低、成交量、${stock.history.size}日历史收盘")
                            fontSize(9f); lineHeight(13f); marginTop(12f); color(Color(0xFF64748B))
                        }
                    }
                }

                View {
                    attr {
                        marginLeft(16f); marginRight(16f); marginTop(16f)
                        padding(16f)
                        borderRadius(12f)
                        backgroundColor(Color.WHITE)
                        flexDirectionColumn()
                    }

                    View {
                        attr {
                            flexDirectionRow()
                            alignItemsCenter()
                            justifyContentSpaceBetween()
                        }
                        Text {
                            attr {
                                text("AI 解读")
                                fontSize(16f)
                                fontWeightBold()
                                color(Color(0xFF1A1A1A))
                            }
                        }
                        Text {
                            attr {
                                text(if (ctx.aiResult?.source?.contains("离线") == true) "AI 离线分析" else "AI 智能解读")
                                fontSize(11f)
                                color(Color(0xFF999999))
                            }
                        }
                    }

                    Text {
                        attr {
                            visibility(ctx.aiResult == null)
                            text("AI 正在分析行情，请稍候")
                            fontSize(13f)
                            marginTop(12f)
                            color(Color(0xFF999999))
                        }
                    }

                    View {
                        attr {
                            visibility(ctx.aiResult != null)
                            flexDirectionColumn()
                        }

                        View {
                            attr {
                                flexDirectionRow()
                                marginTop(12f)
                            }

                            View {
                                attr {
                                    backgroundColor(Color(0xFFDBEAFE))
                                    borderRadius(6f)
                                    paddingLeft(10f)
                                    paddingRight(10f)
                                    paddingTop(4f)
                                    paddingBottom(4f)
                                }
                                Text {
                                    attr {
                                        text(aiSignalLabel(ctx.aiResult?.action ?: ""))
                                        fontSize(12f)
                                        fontWeightBold()
                                        color(Color(0xFF1D4ED8))
                                    }
                                }
                            }

                            View {
                                attr {
                                    val riskLevel = ctx.aiResult?.riskLevel ?: "中风险"
                                    marginLeft(8f)
                                    backgroundColor(ctx.riskBgColor(riskLevel))
                                    borderRadius(6f)
                                    paddingLeft(10f)
                                    paddingRight(10f)
                                    paddingTop(4f)
                                    paddingBottom(4f)
                                }
                                Text {
                                    attr {
                                        text(ctx.aiResult?.riskLevel ?: "")
                                        fontSize(12f)
                                        fontWeightBold()
                                        color(ctx.riskTextColor(ctx.aiResult?.riskLevel ?: "中风险"))
                                    }
                                }
                            }

                            View {
                                attr {
                                    marginLeft(8f)
                                    backgroundColor(Color(0xFFF0F0F0))
                                    borderRadius(6f)
                                    paddingLeft(10f)
                                    paddingRight(10f)
                                    paddingTop(4f)
                                    paddingBottom(4f)
                                }
                                Text {
                                    attr {
                                        text("置信度" + (ctx.aiResult?.confidence?.toString() ?: ""))
                                        fontSize(12f)
                                        color(Color(0xFF666666))
                                    }
                                }
                            }
                        }

                        View {
                            attr { marginTop(12f); flexDirectionColumn() }
                            View {
                                attr { flexDirectionRow(); justifyContentSpaceBetween(); alignItemsCenter() }
                                Text { attr { text("AI 置信度"); fontSize(10f); color(Color(0xFF64748B)) } }
                                Text { attr { text("${ctx.aiResult?.confidence ?: 0}%"); fontSize(10f); fontWeightBold(); color(Color(0xFF2563EB)) } }
                            }
                            View {
                                attr {
                                    height(6f); marginTop(5f); borderRadius(3f)
                                    backgroundColor(Color(0xFFE2E8F0))
                                }
                                View {
                                    attr {
                                        width((ctx.pagerData.pageViewWidth - 64f) * ((ctx.aiResult?.confidence ?: 0) / 100f))
                                        height(6f); borderRadius(3f); backgroundColor(Color(0xFF2563EB))
                                    }
                                }
                            }
                            Text {
                                attr {
                                    text("关键支撑与压力位已联动标注在上方走势图")
                                    fontSize(10f); marginTop(6f); color(Color(0xFF64748B))
                                }
                            }
                        }

                        View {
                            attr {
                                val showOfflineRetry = ctx.aiResult?.source?.contains("离线") == true
                                visibility(showOfflineRetry)
                                height(if (showOfflineRetry) 38f else 0f)
                                marginTop(if (showOfflineRetry) 10f else 0f)
                                borderRadius(10f)
                                allCenter()
                                backgroundColor(Color(0xFFFFF7ED))
                            }
                            Text {
                                attr {
                                    text("在线分析失败，点此重试")
                                    fontSize(12f)
                                    fontWeightBold()
                                    color(Color(0xFFEA580C))
                                }
                            }
                            event { click { ctx.requestAnalysis(stock) } }
                        }

                        Text {
                            attr {
                                text("趋势判断：" + (ctx.aiResult?.trendJudge ?: ""))
                                fontSize(13f)
                                marginTop(12f)
                                color(Color(0xFF333333))
                            }
                        }

                        Text {
                            attr {
                                text(
                                    "支撑 " + formatPriceValue(ctx.aiResult?.supportLevel ?: 0.0) +
                                        "    压力 " + formatPriceValue(ctx.aiResult?.resistanceLevel ?: 0.0) +
                                        "    止损 " + formatPriceValue(ctx.aiResult?.stopLoss ?: 0.0)
                                )
                                fontSize(13f)
                                marginTop(6f)
                                color(Color(0xFF333333))
                            }
                        }

                        View {
                            attr {
                                marginTop(12f)
                                padding(10f)
                                borderRadius(8f)
                                backgroundColor(Color(0xFFF5F8FF))
                            }
                            Text {
                                attr {
                                    text(ctx.aiResult?.summary ?: "")
                                    fontSize(13f)
                                    color(Color(0xFF3D5A99))
                                }
                            }
                        }

                        Text {
                            attr {
                                text("以上内容由 AI 生成，仅供参考，不构成投资建议")
                                fontSize(10f)
                                marginTop(10f)
                                color(Color(0xFFBBBBBB))
                            }
                        }

                        View {
                            attr {
                                height(38f)
                                marginTop(12f)
                                borderRadius(10f)
                                allCenter()
                                backgroundColor(Color(0xFFEFF6FF))
                            }
                            Text {
                                attr {
                                    text(if (ctx.enteredFromChat) "返回 AI 对话 ›" else "继续追问 AI ›")
                                    fontSize(13f)
                                    fontWeightBold()
                                    color(Color(0xFF2563EB))
                                }
                            }
                            event {
                                click {
                                    if (ctx.enteredFromChat) {
                                        ctx.acquireModule<RouterModule>(RouterModule.MODULE_NAME).closePage()
                                        return@click
                                    }
                                    val params = com.tencent.kuikly.core.nvi.serialization.json.JSONObject().apply {
                                        put("question", "请进一步解释${stock.name}（${stock.code}）的机会、风险和关键观察点")
                                        put("origin", "detail")
                                        put("originStockCode", stock.code)
                                    }
                                    ctx.acquireModule<RouterModule>(RouterModule.MODULE_NAME)
                                        .openPage("StockChat", params)
                                }
                            }
                        }
                    }
                }

                // Keep the final action completely above the iPhone Home indicator.
                // An explicit footer is used because some Kuikly iOS hosts report a
                // zero bottom inset while the simulator still reserves the gesture area.
                View {
                    attr {
                        height(28f + ctx.getPager().pageData.safeAreaInsets.bottom)
                    }
                }
                }
            }
        }
    }

    private fun riskBgColor(riskLevel: String): Color {
        return when (riskLevel) {
            "低风险" -> Color(0xFFE6F7EF)
            "高风险" -> Color(0xFFFDECEE)
            else -> Color(0xFFFFF6E5)
        }
    }

    private fun riskTextColor(riskLevel: String): Color = when (riskLevel) {
        "低风险" -> Color(0xFF047857)
        "高风险" -> Color(0xFFDC2626)
        else -> Color(0xFFB45309)
    }

    private fun requestAnalysis(stock: Stock) {
        aiResult = loadingResult(stock)
        val networkModule = acquireModule<NetworkModule>(NetworkModule.MODULE_NAME)
        AIAnalysisEngine.analyze(networkModule, stock) { result -> aiResult = result }
    }

    private fun loadingResult(stock: Stock): AIAnalysisResult {
        return AIAnalysisResult(
            action = "分析中",
            actionColor = "#999999",
            confidence = 0,
            riskLevel = "请稍候",
            riskColor = "#999999",
            trendJudge = "AI 正在分析行情，请稍候",
            supportLevel = stock.recentSupportLevel(),
            resistanceLevel = stock.recentResistanceLevel(),
            stopLoss = stock.recentSupportLevel() * 0.98,
            summary = "正在生成基于真实行情的智能解读…",
            source = "AI 智能解读 · 分析中"
        )
    }
}

private fun ViewContainer<*, *>.MarketMetric(label: String, value: String, compact: Boolean = false) {
    View {
        attr { flex(1f); flexDirectionColumn(); alignItemsCenter() }
        Text {
            attr {
                text(value); lines(1); textAlignCenter()
                fontSize(if (compact && value.length >= 7) 11f else 14f)
                fontWeightBold(); color(Color(0xFF1A1A1A))
            }
        }
        Text { attr { text(label); fontSize(11f); marginTop(5f); color(Color(0xFF999999)) } }
    }
}
