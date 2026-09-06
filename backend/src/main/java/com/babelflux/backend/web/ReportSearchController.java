package com.babelflux.backend.web;

import com.babelflux.backend.search.ReportIndexingPort;
import com.babelflux.backend.search.ReportSearchCriteria;
import com.babelflux.backend.search.ReportSearchPage;
import com.babelflux.backend.search.ReportSearchQuery;
import java.time.Instant;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/reports")
public class ReportSearchController {
    private final ReportSearchQuery search;
    private final ReportIndexingPort indexing;

    public ReportSearchController(ReportSearchQuery search, ReportIndexingPort indexing) {
        this.search = search;
        this.indexing = indexing;
    }

    @GetMapping("/search")
    public ReportSearchPage search(@RequestParam(required = false) String q,
                                   @RequestParam(required = false) String sourceLanguage,
                                   @RequestParam(required = false) String domain,
                                   @RequestParam(required = false) Instant from,
                                   @RequestParam(required = false) Instant to,
                                   @RequestParam(defaultValue = "0") int page,
                                   @RequestParam(defaultValue = "20") int size) {
        return search.search(new ReportSearchCriteria(q, sourceLanguage, domain, from, to, page, size));
    }

    @GetMapping("/{reportId}/index-status")
    public ResponseEntity<?> status(@PathVariable String reportId) {
        var status = indexing.status(reportId);
        if (status == null) return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(Map.of("detail", "report index job not found: " + reportId));
        return ResponseEntity.ok(status);
    }

    @PostMapping("/rebuild")
    public Map<String, Integer> rebuild() {
        return Map.of("queued", indexing.rebuild());
    }
}
