// ModelsHandler.java

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import org.json.JSONObject;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public class ModelsHandler implements HttpHandler {
    private static final List<JSONObject> models = new ArrayList<>();
    static{
        models.add(new JSONObject().put("id", "gpt-5.4-mini").put("object", "model").put("provider", "open-ai"));
        models.add(new JSONObject().put("id", "gpt-4.1").put("object", "model").put("provider", "open-ai"));
        models.add(new JSONObject().put("id", "gpt-5-nano").put("object", "model").put("provider", "open-ai"));
        models.add(new JSONObject().put("id", "claude-haiku-4-5").put("object", "model").put("provider", "anthropic"));
        // Grok 模型
        models.add(new JSONObject().put("id", "grok-1").put("object", "model").put("provider", "x-ai"));
        models.add(new JSONObject().put("id", "grok-1.5").put("object", "model").put("provider", "x-ai"));
        models.add(new JSONObject().put("id", "grok-2").put("object", "model").put("provider", "x-ai"));
        models.add(new JSONObject().put("id", "grok-3").put("object", "model").put("provider", "x-ai"));
        models.add(new JSONObject().put("id", "grok-4").put("object", "model").put("provider", "x-ai"));

        //Deepseek
        models.add(new JSONObject().put("id", "deepseek-chat").put("object", "model").put("provider", "deepseek"));
        models.add(new JSONObject().put("id", "deepseek-reasoner").put("object", "model").put("provider", "deepseek"));
    }
    @Override
    public void handle(HttpExchange exchange) throws IOException {

        // 仅允许GET请求
        if (!exchange.getRequestMethod().equalsIgnoreCase("GET")) {
            exchange.sendResponseHeaders(405, -1); // Method Not Allowed
            return;
        }

        try {
            // 构建响应JSON
            JSONObject responseJson = new JSONObject();
            responseJson.put("object", "list");
            responseJson.put("data", models);

            byte[] responseBytes = responseJson.toString().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, responseBytes.length);

            OutputStream os = exchange.getResponseBody();
            os.write(responseBytes);
            os.close();
            System.gc();
        } catch (Exception e) {
            e.printStackTrace();
            exchange.sendResponseHeaders(500, -1);
        }
    }
}
