package com.babelflux.backend.provider.strategy;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/models/strategy")
public class ModelStrategyController {
    private final ModelStrategyService service;

    public ModelStrategyController(ModelStrategyService service) { this.service = service; }

    @PostMapping("/plan")
    public StrategyPlanResponse plan(@RequestBody(required = false) StrategyPlanRequest request) {
        return service.plan(request == null ? new StrategyPlanRequest(null, null, null, false, null, null) : request);
    }
}
