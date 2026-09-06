package com.babelflux.backend.search;

import java.util.Map;

public interface ReportSearchIndexer { void index(String reportId, Map<String, Object> report); }
