package com.treepeople.leapmindtts.service.profile;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * M6 用户画像模块可配置参数。
 * <p>
 * 通过 {@code application.yml} 中 {@code leapmind.m6.*} 前缀进行配置，
 * 统一管理缓存 TTL、请求大小限制、时间窗口等参数，避免硬编码。
 * </p>
 *
 * <pre>{@code
 * leapmind:
 *   m6:
 *     cache:
 *       enabled: true
 *       profile-ttl: 30m
 *       summary-ttl: 10m
 *     event:
 *       quarantine-hours: 24
 *       max-data-bytes: 16384
 *     request:
 *       max-bytes: 2097152
 *     summary:
 *       max-codepoints: 1200
 * }</pre>
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "leapmind.m6")
public class M6Properties {

    /** 缓存配置。 */
    private Cache cache = new Cache();

    /** 事件配置。 */
    private Event event = new Event();

    /** 请求配置。 */
    private Request request = new Request();

    /** 摘要配置。 */
    private Summary summary = new Summary();

    @Data
    public static class Cache {
        /** 是否启用 Redis 缓存。 */
        private boolean enabled = false;
        /** 完整画像缓存 TTL。 */
        private Duration profileTtl = Duration.ofMinutes(30);
        /** 场景摘要缓存 TTL。 */
        private Duration summaryTtl = Duration.ofMinutes(10);
    }

    @Data
    public static class Event {
        /** 事件时间偏差隔离窗口（小时）。 */
        private long quarantineHours = M6Constants.EVENT_QUARANTINE_HOURS;
        /** 事件数据规范后最大字节数。 */
        private int maxDataBytes = M6Constants.MAX_EVENT_DATA_BYTES;
    }

    @Data
    public static class Request {
        /** M6 请求体最大字节数。 */
        private int maxBytes = M6Constants.MAX_REQUEST_BYTES;
    }

    @Data
    public static class Summary {
        /** 摘要序列化最大 code point 数。 */
        private int maxCodepoints = M6Constants.MAX_SUMMARY_CODEPOINTS;
    }
}
