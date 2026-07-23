package com.treepeople.leapmindtts.common.util;

import org.springframework.util.DigestUtils;
import java.nio.charset.StandardCharsets;

/**
 * 标准化缓存键生成器
 */
public class CacheKeyBuilder {

    /**
     * Redis 缓存键前缀: 高频问答缓存
     */
    public static final String QUESTION_CACHE_PREFIX = "qa:cache:";

    /**
     * 对用户提问进行标准化处理（小写、去空格）和 MD5 哈希，确保缓存键的一致性。
     *
     * @param question 用户的原始提问
     * @return 标准化并哈希后的字符串
     */
    public static String questionHash(String question) {
        // 去掉首尾多余空白，把内部连续空格缩减为单空格，转为全小写，再求 MD5 保证对齐标准
        String normalized = question.trim().toLowerCase().replaceAll("\s+", " ");
        return DigestUtils.md5DigestAsHex(normalized.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 生成用于请求合并与本地并发去重的键。
     *
     * @param userId   用户 ID
     * @param question 用户的原始提问
     * @return 去重键，格式为 "userId:questionHash"
     */
    public static String dedupKey(String userId, String question) {
        return userId + ":" + questionHash(question);
    }

    /**
     * 生成用于 Redis 缓存高频问答的完整 Key。
     *
     * @param question 用户的原始提问
     * @return Redis 缓存键，格式为 "qa:cache:questionHash"
     */
    public static String questionCacheKey(String question) {
        return QUESTION_CACHE_PREFIX + questionHash(question);
    }
}
