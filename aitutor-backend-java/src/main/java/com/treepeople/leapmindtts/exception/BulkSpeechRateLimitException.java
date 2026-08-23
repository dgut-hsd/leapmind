package com.treepeople.leapmindtts.exception;

/**
 * 批量语音合成限流异常
 */
public class BulkSpeechRateLimitException extends RuntimeException {

    public BulkSpeechRateLimitException(String message) {
        super(message);
    }

    public BulkSpeechRateLimitException(String message, Throwable cause) {
        super(message, cause);
    }
}
