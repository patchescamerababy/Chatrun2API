package Utils;

import com.sun.net.httpserver.HttpExchange;
import okhttp3.*;
import org.brotli.dec.BrotliInputStream;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Base64;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.*;
import java.util.zip.GZIPInputStream;

import static Utils.EncryptionTokenGenerator.getEncryptionToken;

public class Utils {
    public static boolean isConnectionProblem(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            String message = current.getMessage();
            if (message != null) {
                String normalized = message.toLowerCase(Locale.ROOT);
                if (normalized.contains("stream is closed")
                        || normalized.contains("broken pipe")
                        || normalized.contains("connection reset")
                        || normalized.contains("forcibly closed")
                        || normalized.contains("an established connection was aborted")
                        || normalized.contains("an existing connection was forcibly closed")
                        || normalized.contains("required settings preface not received")) {
                    return true;
                }
            }
            current = current.getCause();
        }
        return false;
    }

    public static void closeQuietly(HttpExchange exchange) {
        if (exchange == null) {
            return;
        }
        try {
            exchange.close();
        } catch (Exception ignore) {
        }
    }
    /**
     * 返回根据 Content-Encoding 解码后的上游响应流。
     *
     * 调用方必须关闭返回的流。由于请求显式设置了 Accept-Encoding，不能依赖 OkHttp
     * 自动解压，流式与非流式转发都应通过此方法读取响应体。
     */
    public static InputStream decompressionStream(Response response) throws IOException {
        if (response == null || response.body() == null) {
            throw new IOException("Response body is null");
        }

        InputStream inputStream = response.body().byteStream();
        String contentEncoding = response.header("Content-Encoding", "").trim();
        if (contentEncoding.isEmpty() || "identity".equalsIgnoreCase(contentEncoding)) {
            return inputStream;
        }

        /*
         * buildChatBuddyRequest 显式设置了 Accept-Encoding，OkHttp 的 BridgeInterceptor
         * 因而不会执行透明 gzip 解压。Content-Encoding 可以包含多个按应用顺序排列的
         * 编码，解码时必须逆序处理。
         */
        String[] encodings = contentEncoding.split(",");
        for (int i = encodings.length - 1; i >= 0; i--) {
            String encoding = encodings[i].trim();
            if (encoding.isEmpty() || "identity".equalsIgnoreCase(encoding)) {
                continue;
            }
            if ("gzip".equalsIgnoreCase(encoding) || "x-gzip".equalsIgnoreCase(encoding)) {
                inputStream = new GZIPInputStream(inputStream);
            } else if ("deflate".equalsIgnoreCase(encoding)) {
                inputStream = new java.util.zip.InflaterInputStream(inputStream);
            } else if ("br".equalsIgnoreCase(encoding)) {
                inputStream = new BrotliInputStream(inputStream);
            } else {
                throw new IOException("Unsupported Content-Encoding: " + encoding);
            }
        }

        return inputStream;
    }

    public static BufferedReader decompressionReader(Response response) throws IOException {
        return new BufferedReader(new InputStreamReader(decompressionStream(response), StandardCharsets.UTF_8));
    }

    public static void sendError(HttpExchange exchange, String message, int HTTP_code) {
        try {
            JSONObject error = new JSONObject();
            error.put("error", message);
            byte[] bytes = error.toString().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
            exchange.sendResponseHeaders(HTTP_code, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        } catch (IOException e) {
            if (!isConnectionProblem(e)) {
                e.printStackTrace();
            }
        } finally {
            closeQuietly(exchange);
        }
    }

    public static void sendError(HttpExchange exchange, JSONObject message, int HTTP_code) {
        try {
            byte[] bytes = message.toString().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
            exchange.sendResponseHeaders(HTTP_code, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        } catch (IOException e) {
            if (!isConnectionProblem(e)) {
                e.printStackTrace();
            }
        } finally {
            closeQuietly(exchange);
        }
    }
    public static String decompression(Response response) {
        try {
            String responseBody;
            if (!response.isSuccessful()) {
                System.out.println("error: " + response.code() + " " + response.message());
            }
            if (response.body() == null) {
                return null;
            }

            // 获取响应内容类型和编码方式
            String contentEncoding = response.header("Content-Encoding", "");
            byte[] responseBytes = response.body().bytes();


            // 根据不同的压缩类型进行解压
            if ("gzip".equalsIgnoreCase(contentEncoding)) {
                // 处理 gzip 压缩
                try (GZIPInputStream gzipIn = new GZIPInputStream(new ByteArrayInputStream(responseBytes));
                     InputStreamReader isr = new InputStreamReader(gzipIn, StandardCharsets.UTF_8);
                     BufferedReader br = new BufferedReader(isr)) {
                    StringBuilder sb = new StringBuilder();
                    String line;
                    while ((line = br.readLine()) != null) {
                        sb.append(line);
                    }
                    responseBody = sb.toString();
                }
            } else if ("deflate".equalsIgnoreCase(contentEncoding)) {
                // 处理 deflate 压缩
                try (java.util.zip.InflaterInputStream inflaterIn = new java.util.zip.InflaterInputStream(
                        new ByteArrayInputStream(responseBytes));
                     InputStreamReader isr = new InputStreamReader(inflaterIn, StandardCharsets.UTF_8);
                     BufferedReader br = new BufferedReader(isr)) {
                    StringBuilder sb = new StringBuilder();
                    String line;
                    while ((line = br.readLine()) != null) {
                        sb.append(line);
                    }
                    responseBody = sb.toString();
                }
            } else if ("br".equalsIgnoreCase(contentEncoding)) {
                // 处理 brotli 压缩
                try (BrotliInputStream brIn = new BrotliInputStream(new ByteArrayInputStream(responseBytes));
                     InputStreamReader isr = new InputStreamReader(brIn, StandardCharsets.UTF_8);
                     BufferedReader br = new BufferedReader(isr)) {
                    StringBuilder sb = new StringBuilder();
                    String line;
                    while ((line = br.readLine()) != null) {
                        sb.append(line);
                    }
                    responseBody = sb.toString();
                }
            } else {
                // 无压缩或未知压缩类型，直接转换为字符串
                responseBody = new String(responseBytes, StandardCharsets.UTF_8);
            }
            return responseBody;
        } catch (Exception e) {
            return "{}"; // 返回null表示解压失败
        }
    }
    /**
     * 构建发送到ai-keyboard.com API的流式请求
     *
     * @param body 请求体JSON字符串
     * @return Request对象
     */
    public static Request buildOpenAIStreamRequest(String body) {
        return new Request.Builder()
//                .url("https://ai-keyboard.com/api/openapi/turbo-stream")
                .url("https://ask-ai-chat.app/api/openapi/turbo-stream")
                .addHeader("Host", "ai-keyboard.com")
                .addHeader("Content-Type", "application/json")
                .addHeader("Accept-Encoding", "gzip, deflate, br")
                .addHeader("User-Agent", "KeyboardGPT/1332 CFNetwork/3855.100.1 Darwin/25.0.0")
                .addHeader("Connection", "keep-alive")
                .addHeader("Accept", "application/json")
                .addHeader("Accept-Language", "zh-CN,zh-Hans;q=0.9")
                .addHeader("X-Encryption-Token", getEncryptionToken())
                .addHeader("Cache-Control", "no-cache")
                .post(RequestBody.create(body, MediaType.get("application/json; charset=utf-8")))
                .build();

    }
    /**
     * 构建发送到ai-keyboard.com API的流式请求
     *
     * @param body 请求体JSON字符串
     * @return Request对象
     */
    public static Request buildClaudeStreamRequest(String body) {
        return new Request.Builder()
                .url("https://mychat-ai.cloud/api/anthropic/chat-stream")
                .addHeader("Host", "mychat-ai.cloud")
                .addHeader("Content-Type", "application/json")
                .addHeader("Accept-Encoding", "gzip, deflate, br")
                .addHeader("User-Agent", "chachachat/1071 CFNetwork/3860.100.1 Darwin/25.0.0")
                .addHeader("Connection", "keep-alive")
                .addHeader("Accept", "application/json")
                .addHeader("Accept-Language", "zh-CN,zh-Hans;q=0.9")
                .addHeader("X-Encryption-Token", getEncryptionToken())
                .addHeader("Cache-Control", "no-cache")
                .post(RequestBody.create(body, MediaType.get("application/json; charset=utf-8")))
                .build();
    }

    /**
     * 构建发送到ai-keyboard.com API的非流式请求
     *
     * @param body 请求体JSON字符串
     * @return Request对象
     */
    public static Request buildOpenAINormalRequest(String body) {
        return new Request.Builder()
                .url("https://ai-keyboard.com/api/openapi/turbo")
                .addHeader("Host", "ai-keyboard.com")
                .addHeader("Content-Type", "application/json")
                .addHeader("Accept-Encoding", "gzip, deflate, br")
                .addHeader("User-Agent", "KeyboardGPT/1332 CFNetwork/3855.100.1 Darwin/25.0.0")
                .addHeader("Connection", "keep-alive")
                .addHeader("Accept", "application/json")
                .addHeader("Accept-Language", "zh-CN,zh-Hans;q=0.9")
                .addHeader("X-Encryption-Token",  getEncryptionToken())
                .addHeader("Cache-Control", "no-cache")
                .post(RequestBody.create(body, MediaType.get("application/json; charset=utf-8")))
                .build();

    }
    
    /**
     * 构建发送到ai-keyboard.com API的Grok系列模型请求
     *
     * @param body 请求体JSON字符串
     * @return Request对象
     */
    public static Request buildGrokRequest(String body) {
        return new Request.Builder()
                .url("https://ai-keyboard.com/api/xai/completions")
                .addHeader("Host", "ai-keyboard.com")
                .addHeader("Content-Type", "application/json")
                .addHeader("Accept-Encoding", "gzip, deflate, br")
                .addHeader("User-Agent", "KeyboardGPT/1332 CFNetwork/3855.100.1 Darwin/25.0.0")
                .addHeader("Connection", "keep-alive")
                .addHeader("Accept", "application/json")
                .addHeader("Accept-Language", "zh-CN,zh-Hans;q=0.9")
                .addHeader("X-Encryption-Token",  getEncryptionToken())
                .addHeader("Cache-Control", "no-cache")
                .post(RequestBody.create(body, MediaType.get("application/json; charset=utf-8")))
                .build();
    }

    /**
     * 构建发送到ai-keyboard.com API的Grok系列模型请求
     *
     * @param body 请求体JSON字符串
     * @return Request对象
     */
    public static Request buildDeepSeekRequest(String body) {
        return new Request.Builder()
                .url("https://ai-keyboard.com/api/deepseek/completions")
                .addHeader("Host", "ai-keyboard.com")
                .addHeader("Content-Type", "application/json")
                .addHeader("Accept-Encoding", "gzip, deflate, br")
                .addHeader("User-Agent", "KeyboardGPT/1332 CFNetwork/3855.100.1 Darwin/25.0.0")
                .addHeader("Connection", "keep-alive")
                .addHeader("Accept", "application/json")
                .addHeader("Accept-Language", "zh-CN,zh-Hans;q=0.9")
                .addHeader("X-Encryption-Token",  getEncryptionToken())
                .addHeader("Cache-Control", "no-cache")
                .post(RequestBody.create(body, MediaType.get("application/json; charset=utf-8")))
                .build();
    }
}
