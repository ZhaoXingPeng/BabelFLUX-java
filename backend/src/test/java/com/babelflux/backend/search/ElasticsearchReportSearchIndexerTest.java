package com.babelflux.backend.search;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.indices.ElasticsearchIndicesClient;
import co.elastic.clients.elasticsearch.indices.PutIndicesSettingsRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.data.elasticsearch.core.IndexOperations;
import org.springframework.data.elasticsearch.core.SearchHit;
import org.springframework.data.elasticsearch.core.SearchHits;

class ElasticsearchReportSearchIndexerTest {
    @Test
    void usesVersionedIndexAndStableDocumentId() throws Exception {
        ElasticsearchOperations operations = mock(ElasticsearchOperations.class);
        IndexOperations index = mock(IndexOperations.class);
        when(operations.indexOps(org.springframework.data.elasticsearch.core.mapping.IndexCoordinates.of(
                ElasticsearchReportSearchIndexer.INDEX))).thenReturn(index);
        when(index.exists()).thenReturn(false);
        ElasticsearchReportSearchIndexer search = new ElasticsearchReportSearchIndexer(operations, new ObjectMapper());

        search.index("report-1", Map.of("sessionId", "session-1", "searchText", "hello"));

        verify(index).create(org.mockito.ArgumentMatchers.anyMap());
        verify(operations).index(org.mockito.ArgumentMatchers.argThat(query -> "report-1".equals(query.getId())),
                org.mockito.ArgumentMatchers.eq(org.springframework.data.elasticsearch.core.mapping.IndexCoordinates.of(
                        ElasticsearchReportSearchIndexer.INDEX)));
    }

    @Test
    void createsIndexWithConfiguredReplicaCount() {
        ElasticsearchOperations operations = mock(ElasticsearchOperations.class);
        IndexOperations index = mock(IndexOperations.class);
        when(operations.indexOps(org.springframework.data.elasticsearch.core.mapping.IndexCoordinates.of(
                ElasticsearchReportSearchIndexer.INDEX))).thenReturn(index);
        when(index.exists()).thenReturn(false);
        ElasticsearchReportSearchIndexer search = new ElasticsearchReportSearchIndexer(operations, new ObjectMapper(), 0,
                null);

        search.index("report-1", Map.of("searchText", "hello"));

        verify(index).create(org.mockito.ArgumentMatchers.argThat(settings -> "0".equals(settings.get("number_of_replicas"))));
    }

    @Test
    void updatesReplicaCountOnExistingIndex() throws Exception {
        ElasticsearchOperations operations = mock(ElasticsearchOperations.class);
        IndexOperations index = mock(IndexOperations.class);
        ElasticsearchClient client = mock(ElasticsearchClient.class);
        ElasticsearchIndicesClient indices = mock(ElasticsearchIndicesClient.class);
        when(operations.indexOps(org.springframework.data.elasticsearch.core.mapping.IndexCoordinates.of(
                ElasticsearchReportSearchIndexer.INDEX))).thenReturn(index);
        when(index.exists()).thenReturn(true);
        when(client.indices()).thenReturn(indices);
        ElasticsearchReportSearchIndexer search = new ElasticsearchReportSearchIndexer(operations, new ObjectMapper(), 0,
                client);

        search.index("report-1", Map.of("searchText", "hello"));

        ArgumentCaptor<PutIndicesSettingsRequest> request = ArgumentCaptor.forClass(PutIndicesSettingsRequest.class);
        verify(indices).putSettings(request.capture());
        assertEquals(List.of(ElasticsearchReportSearchIndexer.INDEX), request.getValue().index());
        assertEquals("0", request.getValue().settings().numberOfReplicas());
    }

    @Test
    void mapsSearchHitsBackToReportAndSessionIdentifiers() {
        ElasticsearchOperations operations = mock(ElasticsearchOperations.class);
        SearchHits<Map> hits = mock(SearchHits.class);
        SearchHit<Map> hit = mock(SearchHit.class);
        when(hit.getId()).thenReturn("report-1");
        when(hit.getContent()).thenReturn(Map.of("reportId", "report-1", "sessionId", "session-1",
                "generatedAt", "2026-09-07T00:00:00Z", "summary", "summary"));
        when(hits.getSearchHits()).thenReturn(List.of(hit));
        when(hits.iterator()).thenReturn(List.of(hit).iterator());
        when(hits.getTotalHits()).thenReturn(1L);
        when(operations.search(org.mockito.ArgumentMatchers.<org.springframework.data.elasticsearch.core.query.Query>any(),
                org.mockito.ArgumentMatchers.eq(Map.class),
                org.mockito.ArgumentMatchers.any())).thenReturn(hits);
        ElasticsearchReportSearchIndexer search = new ElasticsearchReportSearchIndexer(operations, new ObjectMapper());

        ReportSearchPage page = search.search(new ReportSearchCriteria(null, null, null, null, null, 0, 20));

        assertEquals(1, page.total());
        assertEquals("session-1", page.items().getFirst().sessionId());
        assertEquals(Instant.parse("2026-09-07T00:00:00Z"), page.items().getFirst().generatedAt());
    }
}
