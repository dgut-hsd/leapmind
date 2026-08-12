package com.treepeople.leapmindtts.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Data
@Component
@ConfigurationProperties(prefix = "virtual-teacher")
public class VirtualTeacherProperties {
    private Duration cacheTtl = Duration.ofHours(24);
    private Duration synthesisTimeout = Duration.ofSeconds(125);
    private RateLimit rateLimit = new RateLimit();
    private Storage storage = new Storage();
    private Streaming streaming = new Streaming();

    @Data
    public static class RateLimit {
        private boolean enabled = true;
        private int requestsPerMinute = 20;
        private int dailyCharacters = 20000;
    }

    @Data
    public static class Storage {
        private String type = "local";
        private String localDir = "${java.io.tmpdir}/leapmind-tts";
        private String publicBaseUrl = "";
        private String endpoint = "http://localhost:9000";
        private String accessKey = "minioadmin";
        private String secretKey = "minioadmin";
        private String bucket = "leapmind-tts";
    }

    @Data
    public static class Streaming {
        /** 有界 PCM 回调队列容量（内存安全限制，非吞吐 SLA）。 */
        private int queueCapacity = 64;
        /** PCM 累积内存安全预算（字节）。默认 = 合成超时(125s) × 32000 B/s。 */
        private long maxPcmBytes = 4_000_000L;
        /** 单段最大字符数（经典短文本 SpeechSynthesizer 限制 ~300，留余量取 290）。 */
        private int segmentMaxChars = 290;
    }
}
