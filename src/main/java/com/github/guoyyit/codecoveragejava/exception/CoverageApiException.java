package com.github.guoyyit.codecoveragejava.exception;

public class CoverageApiException extends RuntimeException {

    private final int status;

    public CoverageApiException(int status, String message) {
        super(message);
        this.status = status;
    }

    public int getStatus() {
        return status;
    }

    public static CoverageApiException notFound(String message) {
        return new CoverageApiException(404, message);
    }

    public static CoverageApiException badRequest(String message) {
        return new CoverageApiException(400, message);
    }

    public static CoverageApiException conflict(String message) {
        return new CoverageApiException(409, message);
    }
}
