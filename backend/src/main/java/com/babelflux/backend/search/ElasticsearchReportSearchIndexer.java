package com.babelflux.backend.search;

import java.util.Map;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.data.elasticsearch.core.mapping.IndexCoordinates;
import org.springframework.data.elasticsearch.core.query.IndexQuery;
import org.springframework.data.elasticsearch.core.query.IndexQueryBuilder;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "babelflux.infrastructure", name = "elasticsearch-enabled", havingValue = "true")
public class ElasticsearchReportSearchIndexer implements ReportSearchIndexer {
    private final ElasticsearchOperations operations;
    private final ObjectMapper mapper;
    public ElasticsearchReportSearchIndexer(ElasticsearchOperations operations, ObjectMapper mapper) {
        this.operations = operations;
        this.mapper = mapper;
    }
    @Override public void index(String reportId, Map<String, Object> report) {
        try {
            Map<String, Object> source = new java.util.HashMap<>(report);
            source.put("reportId", reportId);
            IndexQuery query = new IndexQueryBuilder().withId(reportId).withSource(mapper.writeValueAsString(source)).build();
        operations.index(query, IndexCoordinates.of("babelflux-reports"));
        } catch (JsonProcessingException error) {
            throw new IllegalArgumentException("report cannot be serialized", error);
        }
    }
}
