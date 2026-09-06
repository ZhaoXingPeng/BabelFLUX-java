package com.babelflux.backend.provider.dashscope;

import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/models/llm")
public class DashScopeController {
    private final DashScopeClient client;

    public DashScopeController(DashScopeClient client) { this.client = client; }

    @PostMapping("/generate")
    public DashScopeClient.LlmGenerateResponse generate(@RequestBody GenerateRequest request) {
        GenerateRequest normalized = request == null ? new GenerateRequest(null, null, null, null) : request;
        return client.generate(normalized.model(), normalized.endpoint(), normalized.messages(), normalized.parameters());
    }

    public record GenerateRequest(String model, String endpoint,
                                  List<Map<String, Object>> messages,
                                  Map<String, Object> parameters) {
        public GenerateRequest {
            model = model == null || model.isBlank() ? "qwen3.7-plus" : model;
            endpoint = endpoint == null || endpoint.isBlank() ? "multimodal" : endpoint;
            messages = messages == null ? List.of() : messages;
            parameters = parameters == null ? Map.of() : parameters;
        }
    }
}
