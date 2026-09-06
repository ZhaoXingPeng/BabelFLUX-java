package com.babelflux.backend.search;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.data.elasticsearch.core.SearchHit;
import org.springframework.data.elasticsearch.core.SearchHits;
import org.springframework.data.elasticsearch.core.document.Document;
import org.springframework.data.elasticsearch.core.mapping.IndexCoordinates;
import org.springframework.data.elasticsearch.core.query.Criteria;
import org.springframework.data.elasticsearch.core.query.CriteriaQuery;
import org.springframework.data.elasticsearch.core.query.IndexQuery;
import org.springframework.data.elasticsearch.core.query.IndexQueryBuilder;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "babelflux.infrastructure", name = "elasticsearch-enabled", havingValue = "true")
public class ElasticsearchReportSearchIndexer implements ReportSearchIndexer, ReportSearchQuery {
    public static final String INDEX = "babelflux-reports-v1";
    private static final IndexCoordinates INDEX_COORDINATES = IndexCoordinates.of(INDEX);
    private static final String MAPPING = """
            {
              "properties": {
                "reportId": {"type": "keyword"},
                "sessionId": {"type": "keyword"},
                "sessionName": {"type": "text", "fields": {"keyword": {"type": "keyword"}}},
                "sourceLanguage": {"type": "keyword"},
                "targetLanguage": {"type": "keyword"},
                "domain": {"type": "keyword"},
                "generatedAt": {"type": "date"},
                "summary": {"type": "text"},
                "searchText": {"type": "text"},
                "schemaVersion": {"type": "integer"}
              }
            }
            """;

    private final ElasticsearchOperations operations;
    private final ObjectMapper mapper;

    public ElasticsearchReportSearchIndexer(ElasticsearchOperations operations, ObjectMapper mapper) {
        this.operations = operations;
        this.mapper = mapper;
    }

    @Override
    public void index(String reportId, Map<String, Object> report) {
        ensureIndex();
        try {
            Map<String, Object> source = new LinkedHashMap<>(report);
            source.put("reportId", reportId);
            source.putIfAbsent("schemaVersion", 1);
            IndexQuery query = new IndexQueryBuilder().withId(reportId)
                    .withSource(mapper.writeValueAsString(source)).build();
            operations.index(query, INDEX_COORDINATES);
        } catch (JsonProcessingException error) {
            throw new IllegalArgumentException("report cannot be serialized", error);
        }
    }

    @Override
    public ReportSearchPage search(ReportSearchCriteria criteria) {
        try {
            return doSearch(criteria);
        } catch (RuntimeException error) {
            throw new ReportSearchUnavailableException(error);
        }
    }

    private ReportSearchPage doSearch(ReportSearchCriteria criteria) {
        Criteria filters = new Criteria();
        if (criteria.query() != null && !criteria.query().isBlank()) {
            filters = filters.and(Criteria.where("searchText").matches(criteria.query().trim()));
        }
        if (criteria.sourceLanguage() != null && !criteria.sourceLanguage().isBlank()) {
            filters = filters.and(Criteria.where("sourceLanguage").is(criteria.sourceLanguage().trim()));
        }
        if (criteria.domain() != null && !criteria.domain().isBlank()) {
            filters = filters.and(Criteria.where("domain").is(criteria.domain().trim()));
        }
        if (criteria.from() != null) filters = filters.and(Criteria.where("generatedAt").greaterThanEqual(criteria.from()));
        if (criteria.to() != null) filters = filters.and(Criteria.where("generatedAt").lessThanEqual(criteria.to()));
        CriteriaQuery query = new CriteriaQuery(filters, PageRequest.of(criteria.page(), criteria.size(),
                Sort.by(Sort.Order.desc("generatedAt"), Sort.Order.asc("reportId"))));
        SearchHits<Map> hits = operations.search(query, Map.class, INDEX_COORDINATES);
        List<ReportSearchPage.Hit> items = new ArrayList<>();
        for (SearchHit<Map> hit : hits) items.add(toHit(hit));
        return new ReportSearchPage(items, hits.getTotalHits(), criteria.page(), criteria.size());
    }

    private ReportSearchPage.Hit toHit(SearchHit<Map> hit) {
        Map<String, Object> source = hit.getContent();
        return new ReportSearchPage.Hit(string(source, "reportId", hit.getId()), string(source, "sessionId", null),
                string(source, "sessionName", null), string(source, "sourceLanguage", null),
                string(source, "targetLanguage", null), string(source, "domain", null), instant(source.get("generatedAt")),
                string(source, "summary", null), Collections.unmodifiableMap(new LinkedHashMap<>(source)));
    }

    private static String string(Map<String, Object> source, String key, String fallback) {
        Object value = source.get(key);
        return value == null ? fallback : value.toString();
    }

    private static Instant instant(Object value) {
        if (value instanceof Instant instant) return instant;
        return value == null ? null : Instant.parse(value.toString());
    }

    private void ensureIndex() {
        var index = operations.indexOps(INDEX_COORDINATES);
        if (!index.exists()) {
            index.create();
            index.putMapping(Document.parse(MAPPING));
        }
    }
}
