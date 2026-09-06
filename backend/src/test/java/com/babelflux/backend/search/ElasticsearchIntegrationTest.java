package com.babelflux.backend.search;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.Tag;
import org.testcontainers.elasticsearch.ElasticsearchContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Tag("integration")
@Testcontainers
@EnabledIfEnvironmentVariable(named = "RUN_ELASTICSEARCH_IT", matches = "true")
class ElasticsearchIntegrationTest {
    @Container
    static final ElasticsearchContainer elasticsearch = new ElasticsearchContainer(
            "docker.elastic.co/elasticsearch/elasticsearch:8.15.0")
            .withEnv("xpack.security.enabled", "false");

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void versionedReportDocumentIsIdempotentAndSearchable() throws Exception {
        HttpClient client = HttpClient.newHttpClient();
        String base = "http://" + elasticsearch.getHttpHostAddress();
        request(client, "PUT", base + "/" + ElasticsearchReportSearchIndexer.INDEX,
                "{\"mappings\":{\"properties\":{\"reportId\":{\"type\":\"keyword\"},"
                        + "\"searchText\":{\"type\":\"text\"},\"generatedAt\":{\"type\":\"date\"}}}}");
        String document = "{\"reportId\":\"report-1\",\"sessionId\":\"session-1\","
                + "\"searchText\":\"distributed systems\",\"generatedAt\":\"2026-09-07T00:00:00Z\"}";
        request(client, "PUT", base + "/" + ElasticsearchReportSearchIndexer.INDEX + "/_doc/report-1", document);
        request(client, "PUT", base + "/" + ElasticsearchReportSearchIndexer.INDEX + "/_doc/report-1", document);
        request(client, "POST", base + "/" + ElasticsearchReportSearchIndexer.INDEX + "/_refresh", "");
        JsonNode response = mapper.readTree(request(client, "GET",
                base + "/" + ElasticsearchReportSearchIndexer.INDEX + "/_search",
                "{\"query\":{\"match\":{\"searchText\":\"distributed\"}}}"));
        assertEquals(1, response.path("hits").path("total").path("value").asInt());
    }

    private static String request(HttpClient client, String method, String url, String body) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                .header("Content-Type", "application/json");
        HttpRequest request = switch (method) {
            case "GET" -> builder.GET().build();
            case "POST" -> builder.POST(HttpRequest.BodyPublishers.ofString(body)).build();
            default -> builder.PUT(HttpRequest.BodyPublishers.ofString(body)).build();
        };
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() >= 300) throw new IllegalStateException(response.body());
        return response.body();
    }
}
