import http from "node:http";

const port = Number(process.env.OPENAI_PROXY_PORT || 8787);
const provider = (process.env.AI_PROVIDER || (process.env.GEMINI_API_KEY ? "gemini" : "openai")).toLowerCase();
const openAIModel = process.env.OPENAI_MODEL || "gpt-5-mini";
const geminiModel = process.env.GEMINI_MODEL || "gemini-3.5-flash-lite";
const groqModel = process.env.GROQ_MODEL || "groq/compound";
const model = provider === "gemini" ? geminiModel : provider === "groq" ? groqModel : openAIModel;
const apiKey = provider === "gemini"
  ? (process.env.GEMINI_API_KEY || "")
  : provider === "groq"
    ? (process.env.GROQ_API_KEY || "")
    : (process.env.OPENAI_API_KEY || "");

function apiKeyVariableName() {
  if (provider === "gemini") return "GEMINI_API_KEY";
  if (provider === "groq") return "GROQ_API_KEY";
  return "OPENAI_API_KEY";
}

function sendJson(response, status, body) {
  response.writeHead(status, {
    "Content-Type": "application/json; charset=utf-8",
    "Access-Control-Allow-Origin": "*",
  });
  response.end(JSON.stringify(body));
}

function readJson(request) {
  return new Promise((resolve, reject) => {
    let body = "";
    request.setEncoding("utf8");
    request.on("data", chunk => {
      body += chunk;
      if (body.length > 64 * 1024) request.destroy();
    });
    request.on("end", () => {
      try {
        resolve(JSON.parse(body || "{}"));
      } catch (error) {
        reject(error);
      }
    });
    request.on("error", reject);
  });
}

function extractOutputText(response) {
  if (typeof response.output_text === "string") return response.output_text;
  for (const item of response.output || []) {
    for (const content of item.content || []) {
      if (content.type === "output_text" && typeof content.text === "string") {
        return content.text;
      }
    }
  }
  return "";
}

function cleanJson(text) {
  const cleaned = text.trim().replace(/^```json\s*/i, "").replace(/^```\s*/, "").replace(/```$/, "").trim();
  const start = cleaned.indexOf("{");
  const end = cleaned.lastIndexOf("}");
  return start >= 0 && end > start ? cleaned.slice(start, end + 1) : cleaned;
}

function eventType(title) {
  if (/财报|收入|利润|业绩|盈利|亏损/.test(title)) return "基本面";
  if (/处罚|被罚|罚款|监管|听证|调查|违规|诉讼/.test(title)) return "监管/治理";
  if (/产品|发布|新品|技术|获批/.test(title)) return "产品/技术";
  if (/并购|收购|合作|组织|人事|调整/.test(title)) return "公司事项";
  return "公司动态";
}

function eventDateKey(pubDate) {
  const parsed = new Date(pubDate);
  return Number.isNaN(parsed.getTime()) ? "" : parsed.toISOString().slice(0, 10);
}

function eventReaction(item, priceHistory) {
  const points = priceHistory?.[0]?.points || [];
  const eventDate = eventDateKey(item.date);
  const baseIndex = points.findIndex(point => String(point.date) >= eventDate && Number(point.close) > 0);
  if (!eventDate || baseIndex < 0 || baseIndex + 1 >= points.length) return null;
  const baseClose = Number(points[baseIndex].close);
  const changeAt = offset => {
    const point = points[baseIndex + offset];
    if (!point || !baseClose) return null;
    return (Number(point.close) - baseClose) / baseClose * 100;
  };
  return { eventDate, day1: changeAt(1), day3: changeAt(3), day5: changeAt(5) };
}

function signedPercent(value) {
  if (value == null || !Number.isFinite(value)) return "样本不足";
  return `${value >= 0 ? "+" : ""}${value.toFixed(2)}%`;
}

function eventInsight(reaction) {
  if (!reaction) return "行情样本不足，暂不判断事件后的价格表现";
  const { day1, day3, day5 } = reaction;
  if (day1 != null && day1 < -1 && day5 != null && day5 > 1) {
    return "短期先回落，5日后修复，市场未立即按利好交易";
  }
  if (day1 != null && day1 > 1 && day5 != null && day5 < -1) {
    return "短期冲高后转弱，初始积极反应未能延续";
  }
  const latest = day5 ?? day3 ?? day1;
  if (latest == null || Math.abs(latest) <= 1) return "价格反应有限，暂未形成明确方向";
  return latest > 0
    ? "事件后价格总体走强，但仍不足以确认因果关系"
    : "事件后价格总体偏弱，需继续观察后续进展";
}

function eventOverview(events) {
  const types = new Set(events.map(event => event.type));
  const parts = [`${events.length}个关键事件`];
  if (types.has("基本面")) parts.push("基本面偏正面");
  if (types.has("监管/治理")) parts.push("监管待确认");
  const reactions = events.flatMap(event => [event.reaction?.day1, event.reaction?.day3, event.reaction?.day5])
    .filter(value => value != null);
  const mixed = reactions.some(value => value > 1) && reactions.some(value => value < -1);
  parts.push(mixed ? "市场定价分化" : "市场反应待观察");
  return parts.join(" · ");
}

async function resolvePriceHistory(input) {
  const supplied = Array.isArray(input.priceHistory) ? input.priceHistory : [];
  if (supplied.some(stock => Array.isArray(stock.points) && stock.points.length >= 2)) return supplied;

  const code = String(input.marketContext || "").match(/\((\d{5,6})\)/)?.[1] || "";
  const query = code || input.newsCompanies;
  if (!query) return supplied;
  try {
    const candidates = await searchStocks(query);
    const matched = candidates.find(stock => stock.code === code) || candidates[0];
    if (!matched?.symbol) return supplied;
    const points = await fetchHistory(matched.symbol);
    return points.length >= 2 ? [{ code: matched.code, name: matched.name, points }] : supplied;
  } catch (error) {
    console.warn(`[event-history] ${error.message || String(error)}`);
    return supplied;
  }
}

function eventSignalMarkdown(items, priceHistory) {
  const events = [];
  const seenTypes = new Set();
  for (const item of items) {
    const reaction = eventReaction(item, priceHistory);
    const type = eventType(item.title);
    if (seenTypes.has(type)) continue;
    seenTypes.add(type);
    events.push({ item, reaction, type });
    if (events.length === 2) break;
  }
  if (!events.length) return "";
  const rows = events.map(({ item, reaction, type }) => {
    const suffix = item.source ? ` - ${item.source}` : "";
    const cleanTitle = suffix && item.title.endsWith(suffix) ? item.title.slice(0, -suffix.length) : item.title;
    const eventDate = reaction?.eventDate || eventDateKey(item.date) || "日期未知";
    let reactionText = "样本不足";
    if (reaction) {
      reactionText = `1日 ${signedPercent(reaction.day1)} ｜ 3日 ${signedPercent(reaction.day3)} ｜ 5日 ${signedPercent(reaction.day5)}`;
    }
    return `#### ${cleanTitle}\n` +
      `${type} · ${eventDate} · ${item.source || "Google News"}\n` +
      `- 市场反应：${reactionText}\n` +
      `- AI研判：${eventInsight(reaction)}`;
  });
  return `### 事件影响\n- 事件概览：${eventOverview(events)}\n${rows.join("\n")}`;
}

function ensureConditionalInsight(markdown, marketContext) {
  if (/若|如果|突破|跌破/.test(markdown)) return markdown;
  const support = String(marketContext || "").match(/支撑(?:位)?\s*([0-9.]+)/)?.[1];
  const resistance = String(marketContext || "").match(/(?:压力|阻力)(?:位)?\s*([0-9.]+)/)?.[1];
  if (!support && !resistance) return markdown;
  const stronger = resistance ? `若放量突破${resistance}，趋势改善的可信度上升` : "若价格放量突破近期压力，趋势改善的可信度上升";
  const weaker = support ? `若跌破${support}，需警惕弱势延续` : "若跌破近期支撑，需警惕弱势延续";
  return `${markdown.trim()}\n\n**条件观察**：${stronger}；${weaker}。`;
}

function decodeXml(value) {
  return String(value || "")
    .replace(/<!\[CDATA\[([\s\S]*?)\]\]>/g, "$1")
    .replace(/&amp;/g, "&")
    .replace(/&lt;/g, "<")
    .replace(/&gt;/g, ">")
    .replace(/&quot;/g, '"')
    .replace(/&#39;/g, "'")
    .trim();
}

function xmlTag(item, tag) {
  const match = item.match(new RegExp(`<${tag}(?:\\s[^>]*)?>([\\s\\S]*?)<\\/${tag}>`, "i"));
  return decodeXml(match?.[1] || "");
}

async function fetchRecentCompanyNews(companyNames) {
  const query = `${companyNames || "公司"} 股票 when:30d`;
  const companyTerms = String(companyNames || "")
    .split(/[、，,\/]/)
    .map(name => name.trim().replace(/[-－]\s*[A-Z]+$/i, "").replace(/(控股|集团|公司)$/u, ""))
    .filter(name => name.length >= 2);
  const url = `https://news.google.com/rss/search?q=${encodeURIComponent(query)}&hl=zh-CN&gl=CN&ceid=CN:zh-Hans`;
  const newsResponse = await fetch(url, {
    headers: { "User-Agent": "StockAIDemo2/1.0" },
    signal: AbortSignal.timeout(8000),
  });
  if (!newsResponse.ok) throw new Error(`news provider returned ${newsResponse.status}`);
  const xml = await newsResponse.text();
  const items = [...xml.matchAll(/<item>([\s\S]*?)<\/item>/gi)].map(match => {
    const item = match[1];
    return {
      title: xmlTag(item, "title"),
      date: xmlTag(item, "pubDate"),
      source: xmlTag(item, "source"),
      link: xmlTag(item, "link"),
    };
  }).filter(item => item.title && item.link)
    .filter(item => !companyTerms.length || companyTerms.some(term => item.title.includes(term)))
    .filter(item => !/股票股价|股价行情|行情中心|讨论_资讯|走势图|历史数据/.test(item.title))
    .sort((left, right) => {
      const relevance = title => (/财报|收入|利润|业绩|处罚|监管|产品|发布|合作|并购|回购/.test(title) ? 1 : 0);
      return relevance(right.title) - relevance(left.title);
    });
  return items.slice(0, 5);
}

function buildChatPrompt(input) {
  return [
    "你是面向初学者的 AI 股票研究助手。基于给定的行情回答问题，不得声称拥有题目以外的数据。",
    "仅返回 JSON 对象，不要用代码围栏。字段必须为：",
    "markdown（支持###小标题、**重点**、-项目符号，按问题复杂度控制在30至280字）、",
    "showStockCard（布尔值）、stockCode（股票代码或空字符串）、",
    "showComparisonCard（布尔值）、comparisonCodes（所有被比较股票的代码用英文逗号连接，非比较则为空字符串）、",
    "insightTitle（20字以内）、insightSummary（60字以内）、",
    "action（关注/观望/谨慎三选一）、riskLevel（低风险/中风险/高风险三选一）。",
    "回答规则：必须优先回答最新用户问题；历史只用于理解‘它/刚才/为什么’等追问。",
    "用自然、有对话感的中文直接回答；不要每次套用相同标题或固定三段式。",
    "简单问题1至2句即可；复杂问题再用小标题和要点；条件不足时先说明缺少什么，并只提一个最有价值的追问。",
    "如果行情数据已包含用户询问的股票，直接分析，禁止说没有数据，也禁止以‘抱歉’或‘非常抱歉’开头。",
    "不要复述上一轮答案。追问原因时要补充不同维度的依据、反例和风险。",
    "只有最新问题明确需要某一只股票的行情/走势/风险详情时，showStockCard才为true并填写stockCode；",
    "问候、能力询问、一般解释、非股票话题或多股票比较时showStockCard必须为false且stockCode为空。",
    "当最新问题明确比较两只或更多给定股票时，showComparisonCard必须为true并在comparisonCodes中保留所有股票代码；否则必须为false。",
    "若问题超出给定行情，坦诚说明范围，不要总是回答列表中的第一只股票。",
    "多股比较只能比较给定数据中的涨跌幅、日内振幅和价格位置；不同股票的绝对成交量单位不可直接判定资金强弱。",
    "用户询问‘留哪个’时给出按风险偏好和观察周期区分的条件式结论，不替用户作真实投资决定。",
    "严禁在 markdown 中逐日抄写历史收盘价，也不得显示 insightTitle、insightSummary、stockCode 等 JSON 字段名。",
    "个股研判优先归纳：20日趋势与涨跌、所处区间、最大回撤、波动率、支撑/压力和突破条件；成交量只有在给出历史均量或量比时才判断放量缩量。",
    "不要只写买入/卖出/观望。使用条件式判断，例如‘突破压力并放量则转强；跌破支撑则弱势延续风险增加’。",
    `行情数据：${input.marketContext || ""}\n`,
    `最近对话：${input.history || "无"}\n`,
    `用户问题：${input.question || ""}\n`,
    "回答要解释依据和风险，不构成投资建议。",
  ].join("");
}

function extractPartialMarkdown(rawJson) {
  const marker = rawJson.match(/"markdown"\s*:\s*"/);
  if (!marker || marker.index == null) return "";
  const start = marker.index + marker[0].length;
  let escaped = false;
  let encoded = "";
  for (let index = start; index < rawJson.length; index += 1) {
    const character = rawJson[index];
    if (!escaped && character === '"') break;
    encoded += character;
    if (escaped) escaped = false;
    else if (character === "\\") escaped = true;
  }
  return encoded
    .replace(/\\n/g, "\n")
    .replace(/\\r/g, "")
    .replace(/\\"/g, '"')
    .replace(/\\\\/g, "\\");
}

const chatStreams = new Map();

async function runGeminiChatStream(id, input) {
  const state = chatStreams.get(id);
  try {
    const useEventSignal = Boolean(input.useNewsGrounding);
    let eventItems = [];
    if (useEventSignal) {
      try {
        eventItems = await fetchRecentCompanyNews(input.newsCompanies || input.question);
      } catch (error) {
        console.warn(`[news-rss] ${error.message || String(error)}`);
      }
    }
    const requestBody = {
      contents: [{ parts: [{ text: buildChatPrompt({ ...input, useNewsGrounding: false }) }] }],
      generationConfig: { maxOutputTokens: 900, responseMimeType: "application/json" },
    };
    const apiResponse = await fetch(
      `https://generativelanguage.googleapis.com/v1beta/models/${encodeURIComponent(model)}:streamGenerateContent?alt=sse`,
      {
        method: "POST",
        headers: { "x-goog-api-key": apiKey, "Content-Type": "application/json" },
        body: JSON.stringify(requestBody),
      },
    );
    if (!apiResponse.ok) {
      const payload = await apiResponse.json().catch(() => ({}));
      throw new Error(payload?.error?.message || `Gemini stream returned ${apiResponse.status}`);
    }

    const reader = apiResponse.body.getReader();
    const decoder = new TextDecoder();
    let lineBuffer = "";
    let rawOutput = "";
    while (true) {
      const { value, done } = await reader.read();
      lineBuffer += decoder.decode(value || new Uint8Array(), { stream: !done });
      const lines = lineBuffer.split("\n");
      lineBuffer = lines.pop() || "";
      for (const line of lines) {
        if (!line.startsWith("data:")) continue;
        const event = JSON.parse(line.slice(5).trim());
        const fragment = (event?.candidates?.[0]?.content?.parts || [])
          .map(part => part.text || "")
          .join("");
        if (!fragment) continue;
        rawOutput += fragment;
        state.partial = extractPartialMarkdown(rawOutput);
        state.revision += 1;
      }
      if (done) break;
    }
    const analysis = JSON.parse(cleanJson(rawOutput));
    const priceHistory = useEventSignal ? await resolvePriceHistory(input) : input.priceHistory;
    const eventBlock = useEventSignal ? eventSignalMarkdown(eventItems, priceHistory) : "";
    analysis.markdown = ensureConditionalInsight(analysis.markdown || "", input.marketContext);
    if (eventBlock) analysis.markdown = `${analysis.markdown.trim()}\n\n${eventBlock}`;
    state.result = {
      ok: true,
      provider: "Gemini",
      model,
      ...analysis,
      newsGrounded: Boolean(eventBlock),
    };
  } catch (error) {
    state.error = error.message || String(error);
  } finally {
    state.done = true;
    state.revision += 1;
    setTimeout(() => chatStreams.delete(id), 60_000);
  }
}

async function waitForStreamUpdate(state, afterRevision) {
  for (let attempt = 0; attempt < 45; attempt += 1) {
    if (state.done || state.revision > afterRevision) return;
    await new Promise(resolve => setTimeout(resolve, 100));
  }
}

const defaultQuoteSymbols = ["sz000001", "sh600519", "sz300750", "hk00700"];

function supportedQuoteSymbols(values) {
  return [...new Set(values.map(value => String(value).trim().toLowerCase()))]
    .filter(value => /^(sh|sz)\d{6}$/.test(value) || /^hk\d{5}$/.test(value))
    .slice(0, 12);
}

async function searchStocks(query) {
  const normalized = String(query || "").trim().slice(0, 40);
  if (!normalized) return [];
  const searchResponse = await fetch(
    `https://smartbox.gtimg.cn/s3/?q=${encodeURIComponent(normalized)}&t=all`,
    { headers: { "User-Agent": "StockAIDemo2/1.0" }, signal: AbortSignal.timeout(6000) },
  );
  if (!searchResponse.ok) throw new Error(`search provider returned ${searchResponse.status}`);
  const text = await searchResponse.text();
  const literal = text.match(/v_hint\s*=\s*("[\s\S]*")\s*;?\s*$/)?.[1];
  if (!literal) return [];
  const decoded = JSON.parse(literal);
  if (!decoded || decoded === "N") return [];
  const seen = new Set();
  return decoded.split("^").map(row => {
    const [market, code, name, pinyin, type] = row.split("~");
    const symbol = `${market || ""}${code || ""}`.toLowerCase();
    return { symbol, code: code || "", name: name || "", market: (market || "").toUpperCase(), pinyin: pinyin || "", type };
  }).filter(item => {
    const supported = ((item.market === "SH" || item.market === "SZ") && /^\d{6}$/.test(item.code)) ||
      (item.market === "HK" && /^\d{5}$/.test(item.code));
    // The app is a stock demo: do not surface similarly named funds, REITs,
    // indexes or derivatives that the quote/detail flow cannot represent well.
    const isEquity = String(item.type || "").startsWith("GP");
    if (!supported || !isEquity || seen.has(item.symbol)) return false;
    seen.add(item.symbol);
    return true;
  }).slice(0, 8);
}

function formatVolume(rawVolume, isHongKong) {
  const value = Number(rawVolume || 0);
  if (!Number.isFinite(value)) return "--";
  if (value >= 10000) return `${(value / 10000).toFixed(1)}万${isHongKong ? "股" : "手"}`;
  return `${Math.round(value)}${isHongKong ? "股" : "手"}`;
}

async function fetchRealQuotes(requestedSymbols = defaultQuoteSymbols) {
  const quoteSymbols = supportedQuoteSymbols(requestedSymbols);
  if (quoteSymbols.length === 0) throw new Error("no supported quote symbols");
  const quoteResponse = await fetch(`https://qt.gtimg.cn/q=${quoteSymbols.join(",")}`, {
    headers: { "User-Agent": "StockAIDemo2/1.0" },
    signal: AbortSignal.timeout(6000),
  });
  if (!quoteResponse.ok) throw new Error(`quote provider returned ${quoteResponse.status}`);

  const text = new TextDecoder("gbk").decode(await quoteResponse.arrayBuffer());
  const stocks = [];
  for (const line of text.split(";")) {
    const match = line.match(/v_([a-z]+\d+)="([^"]*)"/i);
    if (!match) continue;
    const symbol = match[1].toLowerCase();
    const fields = match[2].split("~");
    const price = Number(fields[3]);
    if (!fields[2] || !Number.isFinite(price) || price <= 0) continue;
    const isHongKong = symbol.startsWith("hk");
    stocks.push({
      code: fields[2],
      name: fields[1],
      price,
      changeAmount: Number(fields[31]) || 0,
      changePercent: Number(fields[32]) || 0,
      high: Number(fields[33]) || price,
      low: Number(fields[34]) || price,
      volume: formatVolume(fields[6], isHongKong),
      volumeValue: Number(fields[6]) || 0,
      updatedAt: fields[30] || "",
      symbol,
    });
  }
  if (stocks.length === 0) throw new Error("quote provider returned no usable records");

  const histories = await Promise.all(quoteSymbols.map(fetchHistory));
  return stocks.map(stock => ({
    ...stock,
    history: histories[quoteSymbols.indexOf(stock.symbol)] || [],
  }));
}

async function fetchHistory(symbol) {
  try {
    const url = "https://web.ifzq.gtimg.cn/appstock/app/fqkline/get?param=" +
      `${encodeURIComponent(symbol)},day,,,20,qfq`;
    const response = await fetch(url, {
      headers: { "User-Agent": "StockAIDemo2/1.0" },
      signal: AbortSignal.timeout(6000),
    });
    if (!response.ok) return [];
    const payload = await response.json();
    const symbolData = payload?.data?.[symbol] || {};
    const rows = symbolData.qfqday || symbolData.day || [];
    return rows.slice(-20).map(row => ({ date: row[0], close: Number(row[2]), volume: Number(row[5]) || 0 }))
      .filter(point => point.date && Number.isFinite(point.close) && point.close > 0);
  } catch (error) {
    console.warn(`[history:${symbol}] ${error.message || String(error)}`);
    return [];
  }
}

const server = http.createServer(async (request, response) => {
  if (request.method === "GET" && request.url === "/health") {
    return sendJson(response, 200, { ok: true, configured: Boolean(apiKey), provider, model });
  }

  if (request.method === "POST" && request.url === "/stock-search") {
    try {
      const input = await readJson(request);
      const results = await searchStocks(input.query);
      return sendJson(response, 200, { ok: true, results });
    } catch (error) {
      console.error(`[stock-search] ${error.message || String(error)}`);
      return sendJson(response, 200, { ok: false, error: error.message || String(error) });
    }
  }

  if (request.method === "GET" && request.url.startsWith("/quotes")) {
    try {
      const url = new URL(request.url, "http://localhost");
      const requested = url.searchParams.get("symbols");
      const symbols = requested ? requested.split(",") : defaultQuoteSymbols;
      const stocks = await fetchRealQuotes(symbols);
      return sendJson(response, 200, {
        ok: true,
        source: "腾讯证券行情 · 复权日线",
        fetchedAt: new Date().toISOString(),
        stocks,
      });
    } catch (error) {
      console.error(`[quotes] ${error.message || String(error)}`);
      // Keep transport successful so Kuikly can render its own retry state instead of a native error alert.
      return sendJson(response, 200, { ok: false, error: error.message || String(error) });
    }
  }

  if (request.method === "POST" && request.url === "/chat-stream/start") {
    if (provider !== "gemini") {
      return sendJson(response, 200, { ok: false, error: "streaming is currently configured for Gemini" });
    }
    if (!apiKey) return sendJson(response, 200, { ok: false, error: "GEMINI_API_KEY is not configured" });
    try {
      const input = await readJson(request);
      const id = `${Date.now().toString(36)}-${Math.random().toString(36).slice(2)}`;
      chatStreams.set(id, { revision: 0, partial: "", done: false, error: "", result: null });
      runGeminiChatStream(id, input);
      return sendJson(response, 202, { ok: true, id, revision: 0 });
    } catch (error) {
      return sendJson(response, 200, { ok: false, error: error.message || String(error) });
    }
  }

  if (request.method === "GET" && request.url.startsWith("/chat-stream/poll")) {
    const url = new URL(request.url, "http://localhost");
    const id = url.searchParams.get("id") || "";
    const afterRevision = Number(url.searchParams.get("after") || 0);
    const state = chatStreams.get(id);
    if (!state) return sendJson(response, 200, { ok: false, error: "stream not found or expired" });
    await waitForStreamUpdate(state, afterRevision);
    return sendJson(response, 200, {
      ok: true,
      revision: state.revision,
      partial: state.partial,
      done: state.done,
      error: state.error,
      result: state.result,
    });
  }

  const isAnalyze = request.method === "POST" && request.url === "/analyze";
  const isChat = request.method === "POST" && request.url === "/chat";
  if (!isAnalyze && !isChat) {
    return sendJson(response, 404, { ok: false, error: "Not found" });
  }

  if (!apiKey) {
    const variableName = apiKeyVariableName();
    return sendJson(response, 200, { ok: false, error: `${variableName} is not configured` });
  }

  try {
    const input = await readJson(request);
    const useNewsResearch = isChat && Boolean(input.useNewsGrounding);
    let groundedNewsItems = [];

    // News is a compact, deterministic event signal: RSS supplies auditable
    // metadata, while local price history supplies the observed market reaction.
    if (useNewsResearch) {
      try {
        groundedNewsItems = await fetchRecentCompanyNews(input.newsCompanies || input.question);
        if (!groundedNewsItems.length) console.warn("[news-rss] no matching company events");
      } catch (error) {
        console.warn(`[news-rss] ${error.message || String(error)}`);
      }
    }

    const promptInput = { ...input, useNewsGrounding: false };
    const prompt = isChat
      ? buildChatPrompt(promptInput)
      : [
          "请分析下面的股票演示数据，仅返回 JSON 对象，不要 Markdown。",
          "字段必须为：action（买入/卖出/观望）、confidence（0-100整数）、",
          "riskLevel（低风险/中风险/高风险）、trendJudge、supportLevel、",
          "resistanceLevel、stopLoss、summary（80字以内）。",
          `数据：${JSON.stringify(input)}`,
          "这只是教学 Demo，不构成投资建议。",
        ].join("");

    const geminiRequestBody = {
      contents: [{ parts: [{ text: prompt }] }],
      generationConfig: { maxOutputTokens: isChat ? 900 : 500 },
    };
    geminiRequestBody.generationConfig.responseMimeType = "application/json";

    let apiResponse;
    if (provider === "gemini") {
      apiResponse = await fetch(`https://generativelanguage.googleapis.com/v1beta/models/${encodeURIComponent(model)}:generateContent`, {
          method: "POST",
          headers: {
            "x-goog-api-key": apiKey,
            "Content-Type": "application/json",
          },
          body: JSON.stringify(geminiRequestBody),
        });
    } else if (provider === "groq") {
      const groqRequestModel = model;
      const groqRequestBody = {
        model: groqRequestModel,
        messages: [{ role: "user", content: prompt }],
        max_completion_tokens: 800,
        response_format: { type: "json_object" },
      };
      if (groqRequestModel.startsWith("groq/compound")) {
        groqRequestBody.compound_custom = {
          tools: { enabled_tools: [] },
        };
      }
      apiResponse = await fetch("https://api.groq.com/openai/v1/chat/completions", {
        method: "POST",
        headers: {
          "Authorization": `Bearer ${apiKey}`,
          "Content-Type": "application/json",
          "Groq-Model-Version": "latest",
        },
        body: JSON.stringify(groqRequestBody),
      });
    } else {
      apiResponse = await fetch("https://api.openai.com/v1/responses", {
          method: "POST",
          headers: {
            "Authorization": `Bearer ${apiKey}`,
            "Content-Type": "application/json",
          },
          body: JSON.stringify({
            model,
            input: prompt,
            max_output_tokens: isChat ? 900 : 500,
            store: false,
          }),
        });
    }

    const payload = await apiResponse.json();
    if (!apiResponse.ok) {
      console.error(`[${provider}] ${apiResponse.status}: ${payload?.error?.message || "request failed"}`);
      return sendJson(response, 200, {
        ok: false,
        error: payload?.error?.message || `${provider} request failed`,
      });
    }

    const outputText = provider === "gemini"
      ? (payload?.candidates?.[0]?.content?.parts || []).map(part => part.text || "").join("")
      : provider === "groq"
        ? (payload?.choices?.[0]?.message?.content || "")
        : extractOutputText(payload);
    const priceHistory = useNewsResearch ? await resolvePriceHistory(input) : input.priceHistory;
    const eventBlock = useNewsResearch ? eventSignalMarkdown(groundedNewsItems, priceHistory) : "";
    const newsGrounded = Boolean(eventBlock);
    const analysis = JSON.parse(cleanJson(outputText));
    analysis.markdown = ensureConditionalInsight(analysis.markdown || "", input.marketContext);
    if (eventBlock) analysis.markdown = `${analysis.markdown.trim()}\n\n${eventBlock}`;
    const providerLabel = provider === "gemini" ? "Gemini" : provider === "groq" ? "Groq" : "OpenAI";
    console.log(`[${provider}] ${isChat ? "chat" : "analysis"} completed for ${input.code || input.question || "unknown"}`);
    const responseModel = provider === "groq" ? (payload?.model || model) : model;
    return sendJson(response, 200, { ok: true, provider: providerLabel, model: responseModel, ...analysis, newsGrounded });
  } catch (error) {
    console.error(`[${provider}] ${error.message || String(error)}`);
    return sendJson(response, 200, { ok: false, error: error.message || String(error) });
  }
});

server.listen(port, "0.0.0.0", () => {
  console.log(`AI proxy listening on http://localhost:${port}`);
  console.log(`Provider: ${provider}`);
  console.log(`Model: ${model}`);
  const variableName = apiKeyVariableName();
  console.log(apiKey ? `${variableName} detected` : `${variableName} missing`);
});
