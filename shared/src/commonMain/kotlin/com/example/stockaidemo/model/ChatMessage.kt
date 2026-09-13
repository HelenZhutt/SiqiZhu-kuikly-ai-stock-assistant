package com.example.stockaidemo.model

data class ChatMessage(
    val id: Int,
    val role: String,
    val content: String,
    val source: String = "",
    val stockCode: String = "",
    val comparisonCodes: String = "",
    val stockName: String = "",
    val insightTitle: String = "",
    val insightSummary: String = "",
    val action: String = "",
    val riskLevel: String = "",
    val showStockCard: Boolean = false,
    val showComparisonCard: Boolean = false,
    val showRiskRankingCard: Boolean = false,
    val pendingQuestion: String = "",
    val ambiguousTerm: String = "",
    val stockChoices: String = "",
    val retryQuestion: String = "",
    val isLoading: Boolean = false
)

data class ChatAIResponse(
    val markdown: String,
    val source: String,
    val stockCode: String,
    val comparisonCodes: String,
    val insightTitle: String,
    val insightSummary: String,
    val action: String,
    val riskLevel: String,
    val showStockCard: Boolean,
    val showComparisonCard: Boolean,
    val showRiskRankingCard: Boolean
)
