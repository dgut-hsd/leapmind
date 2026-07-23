package com.treepeople.leapmindtts.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "python.api.compress")
public class ContextCompressProperties {
    /**
     * The token count threshold to trigger compression.
     */
    private int tokenThreshold = 4000;
    /**
     * The maximum number of tokens expected in the compressed output.
     */
    private int maxCompressedTokens = 1000;
}
