package com.example.stockaidemo.data

import com.example.stockaidemo.config.AIConfig
import com.example.stockaidemo.model.AIAnalysisResult
import com.example.stockaidemo.model.Stock
import com.example.stockaidemo.model.formatPriceValue
import com.tencent.kuikly.core.module.NetworkModule
import com.tencent.kuikly.core.nvi.serialization.json.JSONObject
import kotlin.math.abs

/**
 * Task 1 的 AI 服务层。API 由本机代理安全调用，任何异常都会返回可解释的离线结果。
 */
object AIAnalysisEngine {

    fun analyze(
        networkModule: NetworkModule,
        stock: Stock,
        callback: (AIAnalysisResult) -> Unit
    ) {
        val body = JSONObject().apply {
            put("code", stock.code)
            put("name", stock.name)
            put("price", stock.price)
            put("changeAmount", stock.changeAmount)
            put("changePercent", stock.changePercent)
            put("high", stock.high)
            put("low", stock.low)
            put("volume", stock.volume)
            put("dataSource", stock.dataSource)
            put("updatedAt", stock.updatedAt)
            put("history", stock.history.joinToString(",") { it.date + ":" + it.close })
            put("supportLevel", stock.recentSupportLevel())
            put("resistanceLevel", stock.recentResistanceLevel())
        }

        networkModule.httpRequest(
            url = AIConfig.proxyUrl,
            isPost = true,
            param = body,
            headers = JSONObject().apply { put("Content-Type", "application/json") },
            timeout = AIConfig.timeoutSeconds,
            responseCallback = { data: JSONObject, success: Boolean, _: String, _ ->
                val onlineResult = if (success && data.optBoolean("ok")) {
                    parseOnlineResult(data, stock)
                } else {
                    null
                }
                callback(onlineResult ?: RuleBasedAIAnalysisService.analyze(stock))
            }
        )
    }

    private fun parseOnlineResult(data: JSONObject, stock: Stock): AIAnalysisResult {
        val action = data.optString("action", "观望")
        val riskLevel = data.optString("riskLevel", "中风险")
        val support = stock.recentSupportLevel()
        val resistance = stock.recentResistanceLevel()
        return AIAnalysisResult(
            action = action,
            actionColor = when (action) {
                "买入" -> "#F5222D"
                "卖出" -> "#00A870"
                else -> "#FF8C00"
            },
            confidence = data.optInt("confidence", 60).coerceIn(0, 100),
            riskLevel = riskLevel,
            riskColor = when (riskLevel) {
                "低风险" -> "#00A870"
                "高风险" -> "#F5222D"
                else -> "#FF8C00"
            },
            trendJudge = data.optString("trendJudge", "趋势数据暂缺"),
            supportLevel = support,
            resistanceLevel = resistance,
            stopLoss = support * 0.98,
            summary = data.optString("summary", "暂未获得模型摘要"),
            source = data.optString("provider", "AI") + " · " + data.optString("model", "model")
        )
    }
}

/** 可解释、可演示的离线兜底，不让网络或额度问题把页面卡在加载态。 */
object RuleBasedAIAnalysisService {

    fun analyze(stock: Stock): AIAnalysisResult {
        val amplitude = if (stock.price == 0.0) 0.0 else (stock.high - stock.low) / stock.price * 100
        val action = when {
            stock.changePercent >= 2.0 -> "买入"
            stock.changePercent <= -2.0 -> "卖出"
            else -> "观望"
        }
        val riskLevel = when {
            amplitude >= 5.0 -> "高风险"
            amplitude >= 2.0 -> "中风险"
            else -> "低风险"
        }
        val trendJudge = when {
            stock.changePercent > 0.5 -> "短期趋势：震荡上行"
            stock.changePercent < -0.5 -> "短期趋势：震荡下行"
            else -> "短期趋势：横盘整理"
        }
        val support = stock.recentSupportLevel()
        val resistance = stock.recentResistanceLevel()
        val stopLoss = support * 0.98
        val direction = if (stock.isUp) "上涨" else "下跌"
        val summary = "${stock.name}（${stock.code}）当前价 ${stock.formatPrice()}，较昨日$direction" +
            " ${formatPriceValue(abs(stock.changePercent))}%，日内振幅约 ${formatPriceValue(amplitude)}%。" +
            "$trendJudge，建议关注 ${formatPriceValue(support)} 附近支撑与 " +
            "${formatPriceValue(resistance)} 附近压力，若跌破 ${formatPriceValue(stopLoss)} 建议止损离场。"

        return AIAnalysisResult(
            action = action,
            actionColor = when (action) {
                "买入" -> "#F5222D"
                "卖出" -> "#00A870"
                else -> "#FF8C00"
            },
            confidence = (50 + abs(stock.changePercent) * 8).toInt().coerceIn(40, 92),
            riskLevel = riskLevel,
            riskColor = when (riskLevel) {
                "低风险" -> "#00A870"
                "高风险" -> "#F5222D"
                else -> "#FF8C00"
            },
            trendJudge = trendJudge,
            supportLevel = support,
            resistanceLevel = resistance,
            stopLoss = stopLoss,
            summary = summary,
            source = "本地规则引擎（离线模拟）"
        )
    }
}
