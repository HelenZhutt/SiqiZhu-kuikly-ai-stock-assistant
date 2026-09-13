package com.example.stockaidemo.components

import com.tencent.kuikly.core.base.Color
import com.tencent.kuikly.core.base.ViewContainer
import com.tencent.kuikly.core.views.Text
import com.tencent.kuikly.core.views.View

/**
 * 聊天卡片使用的轻量结构化排版。模型仍输出 Markdown 子集，但这里会把
 * 标题、指标键值和普通说明渲染成不同层级，避免整张卡片像一堵文字墙。
 */
fun ViewContainer<*, *>.MarkdownRenderer(markdown: String, contentWidth: Float) {
    val lines = markdown.split("\n").map { rawLine ->
        rawLine.trim()
            // Old saved conversations may still contain Markdown links. Keep the
            // readable title but never expose a long provider URL in the bubble.
            .replace(Regex("\\[([^]]+)]\\([^)]*\\)"), "$1")
    }
    var index = 0
    while (index < lines.size) {
        val line = lines[index]
        if (line.isEmpty()) {
            index += 1
            continue
        }

        if (line.startsWith("#### ")) {
            val details = mutableListOf<String>()
            index += 1
            while (index < lines.size && !lines[index].startsWith("###")) {
                if (lines[index].isNotEmpty()) details.add(lines[index])
                index += 1
            }
            EventCard(line.removePrefix("#### "), details, contentWidth)
            continue
        }

        val heading = line.startsWith("###")
        val bullet = line.startsWith("- ") || line.startsWith("• ")
        val cleaned = line.removePrefix("### ").removePrefix("- ").removePrefix("• ")
        val emphasized = cleaned.startsWith("**") && cleaned.endsWith("**")
        val rendered = cleaned.replace("**", "").trim()
        val separator = rendered.indexOf('：')
        val candidateKey = if (separator in 1..10) rendered.substring(0, separator).trim() else ""
        val key = candidateKey.takeIf(::isMetricLabel).orEmpty()
        val value = if (key.isNotEmpty()) rendered.substring(separator + 1).trim() else ""

        when {
            heading -> SectionHeading(rendered, contentWidth)
            key.isNotEmpty() && value.isNotEmpty() -> MetricRow(key, value, contentWidth)
            rendered.endsWith("：") || emphasized -> Subheading(rendered.removeSuffix("："), contentWidth)
            bullet -> BulletRow(rendered, contentWidth)
            else -> BodyLine(rendered, contentWidth)
        }
        index += 1
    }
}

private fun ViewContainer<*, *>.EventCard(title: String, details: List<String>, width: Float) {
    View {
        attr {
            width(width); padding(10f); marginBottom(8f)
            borderRadius(11f); backgroundColor(Color(0xFFF8FAFC))
        }
        Text {
            attr {
                width(width - 20f); text(title.replace("**", "")); fontSize(13f); lineHeight(19f)
                fontWeightBold(); color(Color(0xFF172554)); marginBottom(5f)
            }
        }
        details.forEachIndexed { detailIndex, rawDetail ->
            val detail = rawDetail.removePrefix("- ").replace("**", "").trim()
            val separator = detail.indexOf('：')
            val key = if (separator in 1..10) detail.substring(0, separator).trim().takeIf(::isMetricLabel).orEmpty() else ""
            val value = if (key.isNotEmpty()) detail.substring(separator + 1).trim() else ""
            if (key.isNotEmpty() && value.isNotEmpty()) {
                MetricRow(key, value, width - 20f, compact = true)
            } else {
                Text {
                    attr {
                        width(width - 20f); text(detail); fontSize(10f); lineHeight(15f)
                        color(Color(0xFF64748B)); marginBottom(if (detailIndex == details.lastIndex) 0f else 5f)
                    }
                }
            }
        }
    }
}

private fun ViewContainer<*, *>.SectionHeading(title: String, width: Float) {
    View {
        attr {
            width(width); flexDirectionRow(); alignItemsCenter()
            marginTop(6f); marginBottom(7f)
        }
        View { attr { width(4f); height(18f); borderRadius(2f); backgroundColor(Color(0xFF2563EB)) } }
        Text {
            attr {
                width(width - 12f); text(title); marginLeft(8f); fontSize(15f); lineHeight(20f)
                fontWeightBold(); color(Color(0xFF172554))
            }
        }
    }
}

private fun ViewContainer<*, *>.Subheading(title: String, width: Float) {
    Text {
        attr {
            width(width); text(title); fontSize(13f); lineHeight(18f)
            fontWeightBold(); color(Color(0xFF1E3A8A)); marginTop(4f); marginBottom(5f)
        }
    }
}

private fun ViewContainer<*, *>.MetricRow(label: String, value: String, width: Float, compact: Boolean = false) {
    val labelWidth = if (label.length > 6) 104f else 76f
    View {
        attr {
            width(width); flexDirectionRow(); alignItemsCenter()
            paddingTop(if (compact) 3f else 5f); paddingBottom(if (compact) 3f else 5f)
            paddingLeft(if (compact) 0f else 7f); paddingRight(if (compact) 0f else 7f)
            marginBottom(5f); borderRadius(8f)
            backgroundColor(Color(if (compact) 0xFFFFFFFF else 0xFFF8FAFC))
        }
        View {
            attr {
                width(labelWidth); paddingTop(3f); paddingBottom(3f)
                borderRadius(7f); allCenter(); backgroundColor(metricLabelBackground(label))
            }
            Text {
                attr {
                    text(label); fontSize(10f); lineHeight(14f); fontWeightBold()
                    color(metricLabelForeground(label))
                }
            }
        }
        Text {
            attr {
                width(width - labelWidth - 24f); marginLeft(9f); text(value)
                fontSize(12f); lineHeight(18f); color(Color(0xFF334155))
            }
        }
    }
}

private fun isMetricLabel(label: String): Boolean {
    return listOf(
        "基本情况", "基本面", "技术分析", "技术", "趋势", "支撑位", "压力位", "支撑/压力",
        "成交量与风险", "成交量", "成交", "量价", "催化", "风险提示", "风险", "关键观察",
        "事件概览", "类型", "日期/来源", "市场反应", "AI研判"
    ).any { label == it }
}

private fun ViewContainer<*, *>.BulletRow(value: String, width: Float) {
    View {
        attr { width(width); flexDirectionRow(); marginBottom(5f) }
        View {
            attr {
                width(5f); height(5f); marginTop(7f); marginLeft(3f); marginRight(8f)
                borderRadius(3f); backgroundColor(Color(0xFF60A5FA))
            }
        }
        Text {
            attr {
                width(width - 16f); text(value); fontSize(13f); lineHeight(19f)
                color(Color(0xFF334155))
            }
        }
    }
}

private fun ViewContainer<*, *>.BodyLine(value: String, width: Float) {
    Text {
        attr {
            width(width); text(value); fontSize(13f); lineHeight(19f)
            marginBottom(6f); color(Color(0xFF475569))
        }
    }
}

private fun metricLabelBackground(label: String): Color = when {
    label.contains("风险") -> Color(0xFFFFEDD5)
    label.contains("市场反应") -> Color(0xFFD1FAE5)
    label.contains("支撑") || label.contains("压力") -> Color(0xFFE0E7FF)
    else -> Color(0xFFDBEAFE)
}

private fun metricLabelForeground(label: String): Color = when {
    label.contains("风险") -> Color(0xFFEA580C)
    label.contains("市场反应") -> Color(0xFF047857)
    label.contains("支撑") || label.contains("压力") -> Color(0xFF4338CA)
    else -> Color(0xFF1D4ED8)
}
