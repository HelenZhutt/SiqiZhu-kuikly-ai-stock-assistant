package com.example.stockaidemo.components

import com.example.stockaidemo.model.ChatMessage
import com.tencent.kuikly.core.base.Color
import com.tencent.kuikly.core.base.ViewContainer
import com.tencent.kuikly.core.views.Text
import com.tencent.kuikly.core.views.View

fun ViewContainer<*, *>.ChatBubble(
    message: ChatMessage,
    maxWidth: Float,
    evidence: String,
    onRetry: () -> Unit
) {
    val expandedUserBubble = message.role == "user" &&
        (message.content.length >= 18 || message.content.contains('\n'))
    View {
        attr {
            padding(12f)
            borderRadius(12f)
            maxWidth(maxWidth)
            if (message.role != "user" || expandedUserBubble) width(maxWidth)
            backgroundColor(if (message.role == "user") Color(0xFF2563EB) else Color.WHITE)
        }
        when {
            message.isLoading && message.content.isEmpty() -> Text {
                attr {
                    text("AI 正在理解问题并流式生成…")
                    fontSize(13f)
                    color(Color(0xFF64748B))
                }
            }
            message.role == "user" -> Text {
                attr {
                    // A definite content width is required on iOS. A maxWidth-only
                    // bubble lets the native text view measure as one long line and
                    // then clips glyphs when the container wraps it.
                    if (expandedUserBubble) width(maxWidth - 24f) else maxWidth(maxWidth - 24f)
                    text(message.content)
                    fontSize(14f)
                    lineHeight(20f)
                    color(Color.WHITE)
                }
            }
            else -> {
                val contentWidth = maxWidth - 24f
                MarkdownRenderer(message.content, contentWidth)
                if (message.source.isNotEmpty()) {
                    View {
                        attr {
                            marginTop(7f)
                            height(1f)
                            backgroundColor(Color(0xFFE2E8F0))
                        }
                    }
                    View {
                        attr { paddingTop(7f) }
                        Text {
                            attr {
                                width(contentWidth)
                                text("${displayAiSource(message.source)}\n$evidence")
                                fontSize(9f)
                                lineHeight(13f)
                                color(Color(0xFF64748B))
                            }
                        }
                    }
                }
                if (message.retryQuestion.isNotEmpty()) {
                    View {
                        attr {
                            marginTop(8f)
                            paddingTop(7f); paddingBottom(7f); paddingLeft(10f); paddingRight(10f)
                            borderRadius(8f)
                            backgroundColor(Color(0xFFFFF7ED))
                        }
                        Text {
                            attr {
                                text(when {
                                    message.source.contains("额度受限") -> "AI 额度不足 · 点此重试"
                                    message.source.contains("行情获取失败") -> "行情获取失败 · 点此重试"
                                    else -> "在线请求失败 · 点此重试"
                                })
                                fontSize(11f); fontWeightBold(); color(Color(0xFFEA580C))
                            }
                        }
                        event { click { onRetry() } }
                    }
                }
            }
        }
    }
}

private fun displayAiSource(source: String): String = when {
    source.contains("行情获取失败") -> "行情服务：部分股票获取失败"
    source.contains("额度受限") -> "AI 服务：Gemini 额度受限 · 本地行情兜底"
    source.contains("请求失败") -> "AI 服务：在线请求失败 · 本地行情兜底"
    source.contains("离线") -> "AI 服务：离线可靠模式"
    else -> "AI 服务：在线智能分析${if (source.contains("流式")) " · 流式" else ""}"
}
