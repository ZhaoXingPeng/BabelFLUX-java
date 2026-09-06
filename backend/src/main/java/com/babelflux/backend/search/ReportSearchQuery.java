package com.babelflux.backend.search;

public interface ReportSearchQuery {
    ReportSearchPage search(ReportSearchCriteria criteria);
}
