package com.babelflux.backend.config;

import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "babelflux")
public class BabelFluxProperties {
    private String apiPrefix = "/api";
    private List<String> corsOrigins = new ArrayList<>();
    private boolean requireModelGatewayAuth;
    private Infrastructure infrastructure = new Infrastructure();

    public String getApiPrefix() { return apiPrefix; }
    public void setApiPrefix(String apiPrefix) { this.apiPrefix = apiPrefix; }
    public List<String> getCorsOrigins() { return corsOrigins; }
    public void setCorsOrigins(List<String> corsOrigins) { this.corsOrigins = corsOrigins; }
    public boolean isRequireModelGatewayAuth() { return requireModelGatewayAuth; }
    public void setRequireModelGatewayAuth(boolean requireModelGatewayAuth) {
        this.requireModelGatewayAuth = requireModelGatewayAuth;
    }
    public Infrastructure getInfrastructure() { return infrastructure; }
    public void setInfrastructure(Infrastructure infrastructure) { this.infrastructure = infrastructure; }

    public static class Infrastructure {
        private boolean redisEnabled;
        private boolean rabbitmqEnabled;
        private boolean elasticsearchEnabled;

        public boolean isRedisEnabled() { return redisEnabled; }
        public void setRedisEnabled(boolean redisEnabled) { this.redisEnabled = redisEnabled; }
        public boolean isRabbitmqEnabled() { return rabbitmqEnabled; }
        public void setRabbitmqEnabled(boolean rabbitmqEnabled) { this.rabbitmqEnabled = rabbitmqEnabled; }
        public boolean isElasticsearchEnabled() { return elasticsearchEnabled; }
        public void setElasticsearchEnabled(boolean elasticsearchEnabled) { this.elasticsearchEnabled = elasticsearchEnabled; }
    }
}
