import com.knuddels.jtokkit.Encodings;
import com.knuddels.jtokkit.api.Encoding;
import com.knuddels.jtokkit.api.EncodingRegistry;
import com.knuddels.jtokkit.api.EncodingType;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import okhttp3.*;
import org.jetbrains.annotations.NotNull;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Pattern;

import static Utils.Utils.decompression;
import static Utils.Utils.sendError;

public class ChatProxy implements HttpHandler {

    private static final Encoding ENCODING;


    static {
        EncodingRegistry reg = Encodings.newDefaultEncodingRegistry();
        ENCODING = reg.getEncoding(EncodingType.CL100K_BASE);
    }

    /* ---------- 线程池 ---------- */
    private final ExecutorService executor = Executors.newFixedThreadPool(
            Runtime.getRuntime().availableProcessors()
    );

    /* ---------- 处理入口 ---------- */
    @Override
    public void handle(HttpExchange exchange) throws IOException {
        /* --- CORS 与预检 --- */
        Headers headers = exchange.getResponseHeaders();
        headers.add("Access-Control-Allow-Origin", "*");
        headers.add("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
        headers.add("Access-Control-Allow-Headers", "Content-Type, Authorization");

        String method = exchange.getRequestMethod().toUpperCase();
        if ("OPTIONS".equals(method)) {        // 预检
            exchange.sendResponseHeaders(204, -1);
            return;
        }
        if ("GET".equals(method)) {
            // 返回欢迎页面
            String response = "<html><head><title>欢迎使用API</title></head><body><h1>欢迎使用API</h1><p>此 API 用于与 ChatGPT / Claude 模型交互。您可以发送消息给模型并接收响应。</p></body></html>";

            exchange.getResponseHeaders().add("Content-Type", "text/html; charset=utf-8");
            exchange.sendResponseHeaders(200, response.getBytes(StandardCharsets.UTF_8).length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(response.getBytes(StandardCharsets.UTF_8));
            }
            return;
        }
        if (!"POST".equals(method)) {          // 其它方法
            exchange.sendResponseHeaders(405, -1);
            return;
        }

        /* --- POST 请求处理 --- */
        try (InputStream is = exchange.getRequestBody()) {
            /* 1. 解析请求体 */
            String reqBody = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))
                    .lines().reduce("", (acc, line) -> acc + line);

            JSONObject reqJson = new JSONObject(reqBody);

            String modelName = reqJson.optString("model", "gpt-4o");
            System.out.println("\nmodel: "+modelName+"\n");
            boolean isStream = reqJson.optBoolean("stream", false);
            boolean needUsageChunk = true;
            JSONObject requestJson = new JSONObject(reqJson.toString());

            /* 3. 根据模型类型构建请求 */
            Request upstreamReq;
            if (modelName.startsWith("claude")) {
                upstreamReq = Utils.Utils.buildClaudeStreamRequest(reqJson.toString());
            } else if (modelName.startsWith("deepseek")) {
                // deepseek 系列：使用专用构建
                upstreamReq = Utils.Utils.buildDeepSeekRequest(reqJson.toString());
            } else if (modelName.startsWith("grok")) {
                // grok 系列：使用专用构建
                upstreamReq = Utils.Utils.buildGrokRequest(reqJson.toString());
            } else if (isStream) {
                // OpenAI 流式：走流式构建
                upstreamReq = Utils.Utils.buildOpenAIStreamRequest(reqJson.toString());
            } else {
                // OpenAI 非流式：明确走非流构建
                upstreamReq = Utils.Utils.buildOpenAINormalRequest(reqJson.toString());
            }


            /* 4. 处理请求响应（异步+自动重试版本） */
            if (isStream) {
                /* -- 流式返回处理 -- */
                if (modelName.startsWith("gpt") || modelName.startsWith("chat-latest") ) {
                    handleStreamResponse(exchange, upstreamReq, requestJson, needUsageChunk);
                } else {
                    handleStreamResponse(exchange, upstreamReq, requestJson, needUsageChunk);
                }
            } else {
                /* -- 非流式返回处理 -- */
                if (modelName.startsWith("grok") || modelName.startsWith("deepseek")) {
                    // 非OpenAI模型需要累积内容并转换为OpenAI格式（deepseek/grok 统一在此处理）
                    handleNonOpenAINormalResponse(exchange, upstreamReq, requestJson, needUsageChunk);
                } else if (modelName.startsWith("claude")) {
                    handleStreamResponse(exchange, upstreamReq, requestJson, needUsageChunk);
                } else {
                    // OpenAI 非流：已按要求使用 Utils.buildOpenAINormalRequest 构建请求，原封返回
                    handleOpenAINormalResponse(exchange, upstreamReq);
                }
            }

        } catch (Exception e) {
            if (Utils.Utils.isConnectionProblem(e)) {
                Utils.Utils.closeQuietly(exchange);
                return;
            }
            e.printStackTrace();
            sendError(exchange, "内部服务器错误: " + e.getMessage(), 500);
        }
    }


    private static void coerceEmptyDeltaToObject(JSONObject dataJson) {
        JSONArray choices = dataJson.optJSONArray("choices");
        if (choices == null) return;

        for (int i = 0; i < choices.length(); i++) {
            JSONObject ch = choices.optJSONObject(i);
            if (ch == null) continue;
            Object delta = ch.opt("delta");
            if (delta instanceof JSONArray) {
                JSONArray arr = (JSONArray) delta;
                if (arr.isEmpty()) {
                    ch.put("delta", new JSONObject()); // 关键：[] -> {}
                }
            }
        }
    }

    private void handleStreamResponse(HttpExchange exchange, Request request, JSONObject requestJson,
                                      boolean includeUsage) {
        OkHttpClient okHttpClient = Utils.Client.getOkHttpClient();

        okHttpClient.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(@NotNull Call call, @NotNull IOException e) {
                e.printStackTrace();
                sendError(exchange, "请求失败: " + e.getMessage().toString(),503);
//                handleStreamResponse(exchange, request, requestJson, includeUsage);
            }

            @Override
            public void onResponse(@NotNull Call call, @NotNull Response response) {
                /* ---------- SSE 头 ---------- */
                Headers h = exchange.getResponseHeaders();
                h.add("Content-Type", "text/event-stream; charset=utf-8");
                h.add("Cache-Control", "no-cache");
                h.add("Connection", "keep-alive");
                try {
                    exchange.sendResponseHeaders(200, 0);
                } catch (IOException e) {

                }
                PrintWriter writer = new PrintWriter(
                        new OutputStreamWriter(exchange.getResponseBody(), StandardCharsets.UTF_8), true);

                /* ---------- 统计 prompt ---------- */
                int promptTokens = countPromptTokens(requestJson);

                /* ---------- 累积生成内容 ---------- */
                StringBuilder completionBuf = new StringBuilder(); // 用于 token 计数
                StringBuilder reasoningBuf = new StringBuilder(); // 可选：推理内容

                boolean finalChunkSent = false; // 是否已在 stop 处发送最终 chunk

                 /* ---------- 实时处理上游 SSE ---------- */
                String sseBody = decompression(response);
                 try (BufferedReader reader = new BufferedReader(new InputStreamReader(new java.io.ByteArrayInputStream(sseBody.getBytes(StandardCharsets.UTF_8)), StandardCharsets.UTF_8))) {

                    String line;
                    while ((line = reader.readLine()) != null) {

                        // 1) 跳过空行
                        if (line.isEmpty()) continue;

                        // 2) 跳过我们不想透传的自定义事件
                        if (line.startsWith("event: update")) continue;

                        // 3) 处理 data: 行
                        if (line.startsWith("data:")) {
                            String data = line.substring(5).trim();

                            // 3.1 结束标记：不转发，统一在循环后发 [DONE]
                            if ("<END_STREAMING_SSE>".equals(data) || "[DONE]".equals(data)) break;

                            boolean shouldForward = true; // 本行是否转发到下游

                            try {
                                JSONObject dataJson = new JSONObject(data);
                                JSONArray choices = dataJson.optJSONArray("choices");

                                if (choices != null && !choices.isEmpty()) {
                                    JSONObject choice = choices.optJSONObject(0);
                                    JSONObject delta = choice.optJSONObject("delta");
                                    if (delta != null && delta.has("content")) {
                                        String content = delta.optString("content", "");
                                        if (!content.isEmpty()) {
                                            System.out.print(content);
                                        }
                                    }

                                    // 先累计当前块内容，再判断 stop，避免 finish_reason="stop" 的块里仍带 content 时漏计 token
                                    String contentForCounting = extractContent(choice);
                                    if (!contentForCounting.isEmpty()) {
                                        completionBuf.append(contentForCounting);
                                    }

                                    String reasoningForCounting = extractReasoning(choice);
                                    if (!reasoningForCounting.isEmpty()) {
                                        reasoningBuf.append(reasoningForCounting);
                                    }

                                    // —— 判断 stop —— //
                                    String finishReason = choice.optString("finish_reason", null);
                                    if ("stop".equals(finishReason)) {
                                        // 关键：最终块也做一次规范化，避免上游给了 "delta":[]
                                        coerceEmptyDeltaToObject(dataJson);

                                        if (includeUsage) {
                                            // include_usage=true 时不能把 finish_reason=stop 的上游块原样下发；
                                            // 但如果该 stop 块仍携带了 delta.content，需要先作为普通内容块下发，
                                            // 否则会丢失最后一段内容。随后统一在循环后发送带 usage 的 stop 尾块。
                                            Object stopDelta = choice.opt("delta");
                                            if (!isEmptyDelta(stopDelta)) {
                                                JSONObject contentChunk = new JSONObject(dataJson.toString());
                                                contentChunk.remove("usage");

                                                JSONArray contentChoices = contentChunk.optJSONArray("choices");
                                                if (contentChoices != null && !contentChoices.isEmpty()) {
                                                    JSONObject contentChoice = contentChoices.optJSONObject(0);
                                                    if (contentChoice != null) {
                                                        contentChoice.put("finish_reason", JSONObject.NULL);
                                                    }
                                                }

                                                writer.write("data: " + contentChunk.toString() + "\n\n");
                                                writer.flush();
                                            }
                                        } else {
                                            writer.write("data: " + dataJson.toString() + "\n\n");
                                            writer.flush();
                                        }

                                        finalChunkSent = true;
                                        break;
                                    }


                                    // —— 非 stop 的常规块 —— //
//                            Object delta = choice.opt("delta");
                                    boolean emptyDelta = isEmptyDelta(choice.opt("delta"));

                                    if (emptyDelta) {
                                        // 空 delta：不转发、不计数
                                        shouldForward = false;
                                    }
                                }
                            } catch (Exception ignore) {
                                // 若解析失败，可选择保守透传；也可在此加入字符串兜底逻辑
                                shouldForward = true;
                            }

                            // 3.2 仅在需要转发时写回客户端
                            if (shouldForward) {
                                try {
                                    JSONObject dj = new JSONObject(data);
                                    coerceEmptyDeltaToObject(dj);
                                    addCompatibleUsageAliases(dj);
                                    writer.write("data: " + dj.toString() + "\n\n");
                                } catch (Exception e) {
                                    // 不是 JSON（或上游返回了其他片段），保持原样透传
                                    writer.write("data: " + data + "\n\n");
                                }
                                writer.flush();
                            }


                            continue; // 处理下一行
                        }
                    }

//            }

                    // include_usage=true 时，无论上游是 finish_reason=stop 还是直接 [DONE]，
                    // 都在 [DONE] 前发送一次尾块：{"usage": {...}, "choices": [{"finish_reason":"stop","delta":{},"index":0}]}
                    if (includeUsage) {
                        int completionTokens = safeCountTokens(completionBuf.toString());
                        int totalTokens = promptTokens + completionTokens;

                        JSONObject usage = new JSONObject()
                                .put("completion_tokens", completionTokens)
                                .put("prompt_tokens", promptTokens)
                                .put("total_tokens", totalTokens);

                        JSONArray tailChoices = new JSONArray().put(new JSONObject()
                                .put("finish_reason", "stop")
                                .put("delta", new JSONObject())
                                .put("index", 0)
                        );

                        JSONObject tail = new JSONObject()
                                .put("usage", usage)
                                .put("choices", tailChoices);

                        writer.write("data: " + tail.toString() + "\n\n");
                        writer.flush();
                    }

                    // 统一发送结束
                    writer.write("data: [DONE]\n\n");
                    writer.flush();

                } catch (IOException e) {
                    throw new RuntimeException(e);
                } finally {
                    response.close();
                    exchange.close();
                }
            }
        });
    }


    /* ----------------- 工具方法 ----------------- */

    private static boolean isEmptyDelta(Object delta) {
        if (delta == null) return true;
        if (delta instanceof JSONObject) return ((JSONObject) delta).isEmpty();
        if (delta instanceof JSONArray) return ((JSONArray) delta).isEmpty();
        if (delta instanceof String) return ((String) delta).isEmpty();
        // 其他类型一律视为非空（如需更严格可返回 true）
        return false;
    }

    private static JSONObject buildCompatibleUsage(int promptTokens, int completionTokens) {
        int totalTokens = promptTokens + completionTokens;

        JSONObject completionTokensDetails = new JSONObject()
                .put("accepted_prediction_tokens", 0)
                .put("audio_tokens", 0)
                .put("reasoning_tokens", 0)
                .put("rejected_prediction_tokens", 0);

        JSONObject promptTokensDetails = new JSONObject()
                .put("audio_tokens", 0)
                .put("cached_tokens", 0);

        return new JSONObject()
                .put("input_tokens", promptTokens)
                .put("output_tokens", completionTokens)
                .put("cache_read_input_tokens", 0)
                .put("cache_creation_input_tokens", 0)
                .put("prompt_tokens", promptTokens)
                .put("completion_tokens", completionTokens)
                .put("total_tokens", totalTokens)
                .put("prompt_tokens_details", promptTokensDetails)
                .put("completion_tokens_details", completionTokensDetails);
    }

    private static void addCompatibleUsageAliases(JSONObject payload) {
        if (payload == null) return;

        JSONObject usage = payload.optJSONObject("usage");
        if (usage == null) return;

        int promptTokens = usage.has("prompt_tokens")
                ? usage.optInt("prompt_tokens", usage.optInt("input_tokens", 0))
                : usage.optInt("input_tokens", 0);

        int completionTokens = usage.has("completion_tokens")
                ? usage.optInt("completion_tokens", usage.optInt("output_tokens", 0))
                : usage.optInt("output_tokens", 0);

        if (!usage.has("input_tokens")) usage.put("input_tokens", promptTokens);
        if (!usage.has("output_tokens")) usage.put("output_tokens", completionTokens);
        if (!usage.has("cache_read_input_tokens")) usage.put("cache_read_input_tokens", 0);
        if (!usage.has("cache_creation_input_tokens")) usage.put("cache_creation_input_tokens", 0);
        if (!usage.has("prompt_tokens")) usage.put("prompt_tokens", promptTokens);
        if (!usage.has("completion_tokens")) usage.put("completion_tokens", completionTokens);
        if (!usage.has("total_tokens")) usage.put("total_tokens", promptTokens + completionTokens);

        if (!usage.has("prompt_tokens_details")) {
            usage.put("prompt_tokens_details", new JSONObject()
                    .put("audio_tokens", 0)
                    .put("cached_tokens", 0));
        }

        if (!usage.has("completion_tokens_details")) {
            usage.put("completion_tokens_details", new JSONObject()
                    .put("accepted_prediction_tokens", 0)
                    .put("audio_tokens", 0)
                    .put("reasoning_tokens", 0)
                    .put("rejected_prediction_tokens", 0));
        }
    }

    private static String normalizeUsageResponseBody(String responseBody) {
        try {
            JSONObject responseJson = new JSONObject(responseBody);
            addCompatibleUsageAliases(responseJson);
            return responseJson.toString();
        } catch (Exception ignore) {
            return responseBody;
        }
    }


    private int countPromptTokens(JSONObject req) {
        int total = 0;
        JSONArray msgs = req.optJSONArray("messages");
        if (msgs == null) {
            return 0;
        }

        for (int i = 0; i < msgs.length(); i++) {
            JSONObject msg = msgs.optJSONObject(i);
            if (msg == null) continue;

            Object content = msg.opt("content");
            if (content instanceof String s) {
                total += safeCountTokens(s);
            } else if (content instanceof JSONArray arr) {
                for (int j = 0; j < arr.length(); j++) {
                    JSONObject part = arr.optJSONObject(j);
                    if (part != null && "text".equals(part.optString("type"))) {
                        total += safeCountTokens(part.optString("text"));
                    }
                }
            }
        }

        return total;
    }

    private int safeCountTokens(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        String cleaned = Pattern.compile("<\\|[^|]+\\|>").matcher(text).replaceAll("");
        return ENCODING.countTokens(cleaned);
    }


    /**
     * 提取生成内容（若没有则返回空串，不影响转发）
     */
    private String extractContent(JSONObject choice) {
        if (choice == null) return "";

        JSONObject delta = choice.optJSONObject("delta");
        if (delta != null) {
            return delta.optString("content", "");
        }

        if (choice.has("text")) {
            return choice.optString("text", "");
        }

        JSONObject message = choice.optJSONObject("message");
        if (message != null) {
            return message.optString("content", "");
        }

        return "";
    }

    /**
     * 提取 reasoning_content（可选）
     */
    private String extractReasoning(JSONObject choice) {
        if (choice == null) return "";

        JSONObject delta = choice.optJSONObject("delta");
        if (delta != null) {
            return delta.optString("reasoning_content", "");
        }

        JSONObject message = choice.optJSONObject("message");
        if (message != null) {
            return message.optString("reasoning_content", "");
        }

        return choice.optString("reasoning_content", "");
    }

    /**
     * 带重试机制的 handleNonOpenAINormalResponse 版本
     * 从 (HttpExchange, Response, JSONObject, boolean) 改为 (HttpExchange, Request, JSONObject, boolean)
     */
    private void handleNonOpenAINormalResponse(HttpExchange exchange, Request request, JSONObject requestJson,
                                               boolean includeUsage) {
        OkHttpClient okHttpClient = Utils.Client.getOkHttpClient();

        okHttpClient.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(@NotNull Call call, @NotNull IOException e) {
                e.printStackTrace();
                // 重试机制：递归调用自己
                handleNonOpenAINormalResponse(exchange, request, requestJson, includeUsage);
            }

            @Override
            public void onResponse(@NotNull Call call, @NotNull Response upstreamResp) {
                String modelName = requestJson.optString("model", "gpt-4o");
                boolean isGrokOrDeepseek = modelName.startsWith("grok") || modelName.startsWith("deepseek");
                StringBuilder fullResponse = new StringBuilder();

                // 对于Grok和Deepseek模型的SSE响应，需要实时处理和转发
                if (isGrokOrDeepseek && requestJson.optBoolean("stream", false) && Objects.requireNonNull(upstreamResp.header("Content-Type", "")).contains("text/event-stream")) {
                    // 设置SSE响应头，以便可以实时流式传输
                    Headers h = exchange.getResponseHeaders();
                    h.add("Content-Type", "text/event-stream; charset=utf-8");
                    h.add("Cache-Control", "no-cache");
                    h.add("Connection", "keep-alive");
                    try {
                        exchange.sendResponseHeaders(200, 0);
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                    // 使用异步处理流式响应
                    executor.submit(() -> {
                        try {
                            PrintWriter writer = new PrintWriter(
                                    new OutputStreamWriter(exchange.getResponseBody(), StandardCharsets.UTF_8), true);

                            // 计算prompt tokens
                            int promptTokens = 0;
                            StringBuilder promptTxt = new StringBuilder();
                            JSONArray msgs = requestJson.getJSONArray("messages");
                            if (msgs != null) {
                                for (int i = 0; i < msgs.length(); i++) {
                                    Object content = msgs.getJSONObject(i).opt("content");
                                    if (content instanceof String) {
                                        promptTxt.append((String) content).append('\n');
                                    } else if (content instanceof JSONArray) {
                                        JSONArray arr = (JSONArray) content;
                                        for (int j = 0; j < arr.length(); j++) {
                                            JSONObject part = arr.optJSONObject(j);
                                            if (part != null && "text".equals(part.optString("type"))) {
                                                promptTxt.append(part.optString("text")).append('\n');
                                            }
                                        }
                                    }
                                }
                                promptTokens = ENCODING.countTokens(promptTxt.toString());
                            }

                            // 读取并实时处理响应内容
                            BufferedReader reader = new BufferedReader(
                                    new InputStreamReader(upstreamResp.body().byteStream(), StandardCharsets.UTF_8));

                            String line;

                            try {
                                while ((line = reader.readLine()) != null) {
                                    if (line.isEmpty()) continue;

                                    // 过滤掉 "event: update" 行
                                    if (line.startsWith("event: update")) {
                                        continue;
                                    }

                                    // 处理数据行
                                    if (line.startsWith("data:")) {
                                        String data = line.substring(5).trim();

                                        // 替换结束标记
                                        if ("<END_STREAMING_SSE>".equals(data) || "[DONE]".equals(data)) {
                                            // 不立即发送[DONE]，等待循环结束后处理
                                            break;
                                        }

                                        try {
                                            // 尝试解析为JSON以提取内容用于token计算
                                            JSONObject dataJson = new JSONObject(data);
                                            String content = "";

                                            // 提取内容，适应不同的JSON结构
                                            if (dataJson.has("choices")) {
                                                JSONArray choices = dataJson.optJSONArray("choices");
                                                if (choices != null && choices.length() > 0) {
                                                    JSONObject choice = choices.optJSONObject(0);
                                                    if (choice != null) {
                                                        // 检查delta格式
                                                        if (choice.has("delta")) {
                                                            Object delta = choice.opt("delta");
                                                            if (delta instanceof JSONObject) {
                                                                content = ((JSONObject) delta).optString("content", "");
                                                            }
                                                        }
                                                        // 检查text格式
                                                        else if (choice.has("text")) {
                                                            content = choice.optString("text", "");
                                                        }
                                                        // 检查message.content格式
                                                        else if (choice.has("message")) {
                                                            JSONObject message = choice.optJSONObject("message");
                                                            if (message != null) {
                                                                content = message.optString("content", "");
                                                            }
                                                        }
                                                    }
                                                }
                                            }

                                            // 如果提取到内容，累积用于token计算
                                            if (!content.isEmpty()) {
                                                System.out.print(content);
                                                fullResponse.append(content);
                                            }

                                            // 直接转发原始数据
                                            writer.write("data: " + data + "\n\n");
                                            writer.flush();

                                            // 检查是否是最后一个块（有finish_reason）
                                            if (dataJson.has("choices")) {
                                                JSONArray choices = dataJson.optJSONArray("choices");
                                                if (choices != null && choices.length() > 0) {
                                                    JSONObject choice = choices.optJSONObject(0);
                                                    if (choice != null) {
                                                        String finishReason = choice.optString("finish_reason", null);
                                                        if (finishReason != null && !"null".equals(finishReason)) {
                                                            // 这是最后一个块，但不立即发送[DONE]，等待循环结束后处理
                                                            break;
                                                        }
                                                    }
                                                }
                                            }
                                        } catch (Exception e) {
                                            // 如果解析失败，直接转发原始数据
                                            writer.write("data: " + data + "\n\n");
                                            writer.flush();
                                        }
                                    }
                                }

                                // 使用传入的includeUsage参数
                                if (includeUsage) {
                                    // 计算完成token数
                                    int completionTokens = ENCODING.countTokens(fullResponse.toString());
                                    int totalTokens = promptTokens + completionTokens;

                                    // 构建usage JSON
                                    JSONObject usage = buildCompatibleUsage(promptTokens, completionTokens);

                                    // 构建包含usage的JSON对象
                                    JSONObject usageData = new JSONObject();
                                    usageData.put("usage", usage);
                                    usageData.put("choices", new JSONArray()); // 添加空的choices数组

                                    // 从最后一个数据块获取metadata（如果有的话）
                                    String id = "chatcmpl-" + UUID.randomUUID().toString();
                                    String model = modelName;
                                    long created = Instant.now().getEpochSecond();
                                    String systemFingerprint = "fp_" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);

                                    usageData.put("id", id);
                                    usageData.put("model", model);
                                    usageData.put("created", created);
                                    usageData.put("object", "chat.completion.chunk");
                                    usageData.put("system_fingerprint", systemFingerprint);

                                    // 发送usage信息
                                    writer.write("data: " + usageData + "\n\n");
                                    writer.flush();
                                }

                                // 确保发送结束标记
                                writer.write("data: [DONE]\n\n");
                                writer.flush();

                            } finally {
                                try {
                                    reader.close();
                                    writer.close();
                                } catch (Exception e) {
                                    e.printStackTrace();
                                }
                            }
                        } catch (Exception e) {
                            e.printStackTrace();
                        } finally {
                            try {
                                exchange.close();
                            } catch (Exception e) {
                                e.printStackTrace();
                            }
                        }
                    });

                    return; // 流式处理已提交到线程池，直接返回
                }

                // 对于非流式响应，需要累积内容并转换为OpenAI格式
                executor.submit(() -> {
                    try {
                        BufferedReader reader = new BufferedReader(
                                new InputStreamReader(upstreamResp.body().byteStream(), StandardCharsets.UTF_8));

                        // 累积推理内容
                        StringBuilder reasoningContent = new StringBuilder();
                        StringBuilder localFullResponse = new StringBuilder();

                        // 判断响应类型并相应处理
                        if (upstreamResp.header("Content-Type", "").contains("text/event-stream")) {
                            // 如果是SSE格式响应，需要解析并累积内容
                            String line;
                            while ((line = reader.readLine()) != null) {
                                if (line.isEmpty()) continue;

                                // 过滤掉 "event: update" 行
                                if (line.startsWith("event: update")) {
                                    continue;
                                }

                                // 处理数据行
                                if (line.startsWith("data:")) {
                                    String data = line.substring(5).trim();

                                    // 检查是否是结束标记
                                    if ("<END_STREAMING_SSE>".equals(data) || "[DONE]".equals(data)) {
                                        break;
                                    }

                                    try {
                                        // 尝试解析为JSON以提取内容
                                        JSONObject dataJson = new JSONObject(data);
                                        String content = "";
                                        String reasoning = "";

                                        // 提取内容，适应不同的JSON结构
                                        if (dataJson.has("choices")) {
                                            JSONArray choices = dataJson.optJSONArray("choices");
                                            if (choices != null && choices.length() > 0) {
                                                JSONObject choice = choices.optJSONObject(0);
                                                if (choice != null) {
                                                    // 检查delta格式
                                                    if (choice.has("delta")) {
                                                        Object delta = choice.opt("delta");
                                                        if (delta instanceof JSONObject) {
                                                            JSONObject deltaObj = (JSONObject) delta;
                                                            content = deltaObj.optString("content", "");

                                                            // 提取推理内容，检查是否为null
                                                            if (deltaObj.has("reasoning_content") && !deltaObj.isNull("reasoning_content")) {
                                                                reasoning = deltaObj.optString("reasoning_content", "");
                                                            }
                                                        }
                                                    }
                                                    // 检查text格式
                                                    else if (choice.has("text")) {
                                                        content = choice.optString("text", "");
                                                    }
                                                    // 检查message.content格式
                                                    else if (choice.has("message")) {
                                                        JSONObject message = choice.optJSONObject("message");
                                                        if (message != null) {
                                                            content = message.optString("content", "");

                                                            // 提取推理内容，检查是否为null
                                                            if (message.has("reasoning_content") && !message.isNull("reasoning_content")) {
                                                                reasoning = message.optString("reasoning_content", "");
                                                            }
                                                        }
                                                    }

                                                    // 检查推理内容
                                                    if (choice.has("reasoning_content") && !choice.isNull("reasoning_content")) {
                                                        reasoning = choice.optString("reasoning_content", "");
                                                    }
                                                }
                                            }
                                        }

                                        // 累积内容
                                        if (!content.isEmpty()) {
                                            localFullResponse.append(content);
                                        }

                                        // 累积推理内容（非null且非空）
                                        if (reasoning != null && !reasoning.isEmpty()) {
                                            reasoningContent.append(reasoning);
                                        }
                                    } catch (Exception e) {
                                        System.err.println("解析数据失败: " + e.getMessage());
                                    }
                                }
                            }
                        } else {
                            // 如果是普通JSON响应，直接读取
                            String responseBody = reader.lines().collect(java.util.stream.Collectors.joining("\n"));

                            try {
                                JSONObject responseJson = new JSONObject(responseBody);

                                // 尝试从不同格式中提取内容
                                if (responseJson.has("choices")) {
                                    JSONArray choices = responseJson.getJSONArray("choices");
                                    if (choices.length() > 0) {
                                        JSONObject choice = choices.getJSONObject(0);
                                        // 检查message.content格式（OpenAI格式）
                                        if (choice.has("message")) {
                                            JSONObject message = choice.getJSONObject("message");
                                            localFullResponse.append(message.optString("content", ""));
                                        }
                                        // 检查text格式（某些模型可能使用）
                                        else if (choice.has("text")) {
                                            localFullResponse.append(choice.optString("text", ""));
                                        }
                                    }
                                }
                                // 某些API可能直接返回内容
                                else if (responseJson.has("content")) {
                                    localFullResponse.append(responseJson.optString("content", ""));
                                }
                                // 或者可能有其他结构
                                else if (responseJson.has("response")) {
                                    localFullResponse.append(responseJson.optString("response", ""));
                                }
                                // 如果没有找到内容，尝试使用整个响应体
                                else if (!responseBody.isEmpty()) {
                                    localFullResponse.append(responseBody);
                                }
                            } catch (Exception e) {
                                // 如果不是有效的JSON，直接使用响应体
                                if (!responseBody.isEmpty()) {
                                    localFullResponse.append(responseBody);
                                }
                            }
                        }

                        // 计算token数量
                        int promptTokens = 0;
                        StringBuilder promptTxt = new StringBuilder();
                        JSONArray msgs = requestJson.getJSONArray("messages");
                        if (msgs != null) {
                            for (int i = 0; i < msgs.length(); i++) {
                                Object content = msgs.getJSONObject(i).opt("content");
                                if (content instanceof String) {
                                    promptTxt.append((String) content).append('\n');
                                } else if (content instanceof JSONArray) {
                                    JSONArray arr = (JSONArray) content;
                                    for (int j = 0; j < arr.length(); j++) {
                                        JSONObject part = arr.optJSONObject(j);
                                        if (part != null && "text".equals(part.optString("type"))) {
                                            promptTxt.append(part.optString("text")).append('\n');
                                        }
                                    }
                                }
                            }
                            promptTokens = ENCODING.countTokens(promptTxt.toString());
                        }

                        int completionTokens = ENCODING.countTokens(localFullResponse.toString());
                        int totalTokens = promptTokens + completionTokens;

                        // 构建OpenAI格式的响应
                        JSONObject message = new JSONObject();
                        message.put("content", localFullResponse.toString());
                        message.put("role", "assistant");
                        message.put("function_call", JSONObject.NULL);
                        message.put("tool_calls", JSONObject.NULL);
                        message.put("refusal", JSONObject.NULL);
                        message.put("annotations", new JSONArray());

                        // 如果有推理内容，添加到message中
                        if (reasoningContent.length() > 0) {
                            message.put("reasoning_content", reasoningContent.toString());
                        }

                        JSONObject choice = new JSONObject();
                        choice.put("finish_reason", "stop");
                        choice.put("index", 0);
                        choice.put("logprobs", JSONObject.NULL);
                        choice.put("message", message);

                        JSONArray choices = new JSONArray();
                        choices.put(choice);

                        JSONObject usage = buildCompatibleUsage(promptTokens, completionTokens);

                        JSONObject response = new JSONObject();
                        response.put("id", "chatcmpl-" + UUID.randomUUID().toString().replace("-", ""));
                        response.put("choices", choices);
                        response.put("created", Instant.now().getEpochSecond());
                        response.put("model", modelName);
                        response.put("object", "chat.completion");
                        response.put("service_tier", "default");
                        response.put("system_fingerprint", "fp_" + UUID.randomUUID().toString().replace("-", "").substring(0, 8));
                        response.put("usage", usage);

                        byte[] bytes = response.toString().getBytes(StandardCharsets.UTF_8);
                        Headers h = exchange.getResponseHeaders();
                        h.add("Content-Type", "application/json; charset=utf-8");
                        try {
                            exchange.sendResponseHeaders(200, bytes.length);
                            exchange.getResponseBody().write(bytes);
                        } catch (Exception ignore) {
                            //
                        }
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    } finally {
                        upstreamResp.close();
                    }

                });
            }
        });
    }

    /**
     * 带重试机制的 handleOpenAINormalResponse 版本
     * 从 (HttpExchange, Response) 改为 (HttpExchange, Request)
     */
    private void handleOpenAINormalResponse(HttpExchange exchange, Request request) {
        OkHttpClient okHttpClient = Utils.Client.getOkHttpClient();

        okHttpClient.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(@NotNull Call call, @NotNull IOException e) {
                e.printStackTrace();
                // 重试机制：递归调用自己
                handleOpenAINormalResponse(exchange, request);
            }

            @Override
            public void onResponse(@NotNull Call call, @NotNull Response upstreamResp) {
                // 读取响应内容并先解压再直接返回，不进行内容累积处理
                String responseBody = normalizeUsageResponseBody(decompression(upstreamResp));

                // 使用UTF-8编码，避免将内容转为Unicode编码形式
                Headers h = exchange.getResponseHeaders();
                h.add("Content-Type", "application/json; charset=utf-8");
                try {
                    // 直接写入原始字符串，保持原始编码
                    exchange.sendResponseHeaders(200, 0); // 使用分块传输，避免预先计算长度
                    try (OutputStreamWriter writer = new OutputStreamWriter(exchange.getResponseBody(), StandardCharsets.UTF_8)) {
                        writer.write(responseBody);
                    }
                } catch (Exception ignore) {
                    //
                } finally {
                    upstreamResp.close();
                }
            }
        });
    }
}
