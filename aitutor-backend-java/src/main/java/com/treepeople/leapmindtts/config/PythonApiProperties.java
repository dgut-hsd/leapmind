package com.treepeople.leapmindtts.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "python.api")
public class PythonApiProperties {
    private String baseUrl = "http://localhost:8001"; // Default value
    private String compressContextUri = "/api/internal/ai/compress-context"; // Default value

    public String getCompressContextUri(){
        return this.baseUrl + this.compressContextUri;
    }
}
