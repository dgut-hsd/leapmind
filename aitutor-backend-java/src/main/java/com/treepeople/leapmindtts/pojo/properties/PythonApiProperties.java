package com.treepeople.leapmindtts.pojo.properties;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "python.api")
public class PythonApiProperties {
    private String baseUrl;

    public String getCompressContextUri() {
        return baseUrl + "/internal/ai/compress-context";
    }
}
