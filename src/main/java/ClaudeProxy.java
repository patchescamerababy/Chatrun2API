import Utils.Client;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import okhttp3.Request;
import okhttp3.Response;
import org.json.JSONObject;

import java.io.*;
import java.nio.charset.StandardCharsets;

public class ClaudeProxy implements HttpHandler {

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        Headers corsHeaders = exchange.getResponseHeaders();
        corsHeaders.add("Access-Control-Allow-Origin", "*");
        corsHeaders.add("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
        corsHeaders.add("Access-Control-Allow-Headers", "Content-Type, Authorization");

        String method = exchange.getRequestMethod().toUpperCase();

        if ("OPTIONS".equals(method)) {
            exchange.sendResponseHeaders(204, -1);
            return;
        }

        if ("GET".equals(method)) {
            String response = "<html><head><title>Claude Messages Proxy</title></head>"
                    + "<body><h1>Claude Messages Proxy</h1>"
                    + "<p>POST /v1/messages forwards the request body unchanged to the upstream /v1/messages endpoint.</p>"
                    + "</body></html>";

            byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "text/html; charset=utf-8");
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
            return;
        }

        if (!"POST".equals(method)) {
            exchange.sendResponseHeaders(405, -1);
            return;
        }

        try {
            String reqBody;
            try (InputStream is = exchange.getRequestBody()) {
                reqBody = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))
                        .lines()
                        .reduce("", (acc, line) -> acc + line);
            }

            // 解析请求体判断是否为流式请求（stream=true）。
            JSONObject requestJson = new JSONObject(reqBody);
            boolean isStream = requestJson.optBoolean("stream", false);
            //打印前5个字符和最后5个字符
            System.out.println("Request body: \n" + reqBody.substring(0, Math.min(5, reqBody.length()))+"..."+reqBody.substring(Math.max(0, reqBody.length() - 5)));
            Request upstreamReq = Utils.Utils.buildClaudeStreamRequest(reqBody);

            try (Response upstreamResp = Client.getOkHttpClient().newCall(upstreamReq).execute()) {
                forwardUpstreamResponse(exchange, upstreamResp, isStream);
            }
        } catch (Exception e) {
            e.printStackTrace();
            sendPlainError(exchange, "内部服务器错误: " + e.getMessage(), 500);
        }
    }

    /**
     * 将上游响应体透传给客户端。
     *
     * 关键修正：不再无脑复制上游的 Content-Type（上游经 Cloudflare/CDN 时可能返回
     * text/html），而是根据本次请求是否为流式，显式设置下游 Content-Type：
     *   - stream=true  -> text/event-stream; charset=utf-8（SSE）
     *   - stream=false -> application/json; charset=utf-8
     */
    private void forwardUpstreamResponse(HttpExchange exchange, Response upstreamResp, boolean isStream) throws IOException {
        Headers downstreamHeaders = exchange.getResponseHeaders();

        // 透传上游头部，但跳过 hop-by-hop 头以及 Content-Type / Content-Encoding
        // （Content-Type 由下方按 stream 显式设置；Content-Encoding 由 OkHttp 透明解压后不再适用）。
        for (String name : upstreamResp.headers().names()) {
            if (isHopByHopHeader(name)
                    || "Content-Type".equalsIgnoreCase(name)
                    || "Content-Encoding".equalsIgnoreCase(name)) {
                continue;
            }
            for (String value : upstreamResp.headers(name)) {
                downstreamHeaders.add(name, value);
            }
        }

        if (isStream) {
            // 流式：SSE 响应头
            downstreamHeaders.set("Content-Type", "text/event-stream; charset=utf-8");
            downstreamHeaders.set("Cache-Control", "no-cache");
            downstreamHeaders.set("Connection", "keep-alive");
        } else {
            // 非流式：JSON 响应头
            downstreamHeaders.set("Content-Type", "application/json; charset=utf-8");
        }

        if (upstreamResp.body() == null) {
            exchange.sendResponseHeaders(upstreamResp.code(), -1);
            exchange.close();
            return;
        }

        // 原样透传上游响应体（原始字节流，保持编码一致）。
        exchange.sendResponseHeaders(upstreamResp.code(), 0);
        try (InputStream upstreamBody = upstreamResp.body().byteStream();
             OutputStream downstreamBody = exchange.getResponseBody()) {
            upstreamBody.transferTo(downstreamBody);
        } finally {
            exchange.close();
        }
    }

    private boolean isHopByHopHeader(String name) {
        return "Connection".equalsIgnoreCase(name)
                || "Keep-Alive".equalsIgnoreCase(name)
                || "Proxy-Authenticate".equalsIgnoreCase(name)
                || "Proxy-Authorization".equalsIgnoreCase(name)
                || "TE".equalsIgnoreCase(name)
                || "Trailer".equalsIgnoreCase(name)
                || "Transfer-Encoding".equalsIgnoreCase(name)
                || "Upgrade".equalsIgnoreCase(name)
                || "Content-Length".equalsIgnoreCase(name);
    }

    private void sendPlainError(HttpExchange exchange, String message, int statusCode) {
        try {
            byte[] bytes = message.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "text/plain; charset=utf-8");
            exchange.sendResponseHeaders(statusCode, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        } catch (IOException ignored) {
        } finally {
            exchange.close();
        }
    }
}