package com.babelflux.backend.provider.dashscope;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
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
    public Map<?, ?> generate(@Valid @RequestBody GenerateRequest request) { return client.generate(request.model(), request.messages()); }

    public record GenerateRequest(@NotBlank String model, List<Map<String, String>> messages) {}
}
