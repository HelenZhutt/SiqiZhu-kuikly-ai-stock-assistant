package com.example.stockaidemo.model

data class AIAnalysisResult(
    val action: String,
    val actionColor: String,
    val confidence: Int,
    val riskLevel: String,
    val riskColor: String,
    val trendJudge: String,
    val supportLevel: Double,
    val resistanceLevel: Double,
    val stopLoss: Double,
    val summary: String,
    val source: String
)
