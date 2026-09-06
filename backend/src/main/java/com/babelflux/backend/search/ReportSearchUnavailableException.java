package com.babelflux.backend.search;

public class ReportSearchUnavailableException extends RuntimeException {
    public ReportSearchUnavailableException(Throwable cause) {
        super("report search is temporarily unavailable", cause);
    }
}
