package com.treepeople.leapmindtts.service.profile;

import java.util.Set;

/**
 * M6 用户画像模块常量与枚举定义。
 * <p>
 * 集中管理画像状态、掌握度状态、趋势、错误码、场景类型等魔法值，
 * 避免字符串散落在多个文件中导致维护困难。
 * </p>
 */
public final class M6Constants {

    private M6Constants() { }

    // ── Schema 版本 ──────────────────────────────────────────

    /** 当前支持的 M6 事件 Schema 版本。 */
    public static final String SCHEMA_VERSION = "1.0";

    /** 合法 Schema 版本集合。 */
    public static final Set<String> SUPPORTED_SCHEMA_VERSIONS = Set.of(SCHEMA_VERSION);

    // ── 画像状态 ──────────────────────────────────────────────

    /** 画像状态枚举。 */
    public enum ProfileStatus {
        /** 画像已就绪，可正常查询。 */
        READY("READY"),
        /** 画像未就绪，数据不足以生成。 */
        NOT_READY("NOT_READY"),
        /** 画像已过时，有新事件待重新计算。 */
        STALE("STALE");

        private final String value;
        ProfileStatus(String value) { this.value = value; }
        public String value() { return value; }

        public static boolean isValid(String s) {
            if (s == null) return false;
            for (var v : values()) if (v.value.equals(s)) return true;
            return false;
        }
    }

    /** 可查询的画像状态（READY + STALE）。 */
    public static final Set<String> VISIBLE_STATUSES = Set.of(
            ProfileStatus.READY.value, ProfileStatus.STALE.value);

    // ── 掌握度状态 ────────────────────────────────────────────

    /** 知识点掌握度状态枚举。 */
    public enum MasteryStatus {
        WEAK("WEAK"),
        CONSOLIDATING("CONSOLIDATING"),
        BASIC_MASTERY("BASIC_MASTERY"),
        MASTERED("MASTERED"),
        INSUFFICIENT_EVIDENCE("INSUFFICIENT_EVIDENCE");

        private final String value;
        MasteryStatus(String value) { this.value = value; }
        public String value() { return value; }

        public static boolean isValid(String s) {
            if (s == null) return false;
            for (var v : values()) if (v.value.equals(s)) return true;
            return false;
        }
    }

    /** 全部合法掌握度状态值。 */
    public static final Set<String> MASTERY_STATUSES = Set.of(
            MasteryStatus.WEAK.value, MasteryStatus.CONSOLIDATING.value,
            MasteryStatus.BASIC_MASTERY.value, MasteryStatus.MASTERED.value,
            MasteryStatus.INSUFFICIENT_EVIDENCE.value);

    /** 薄弱状态集合（用于场景摘要筛选）。 */
    public static final Set<String> WEAK_STATUSES = Set.of(
            MasteryStatus.WEAK.value, MasteryStatus.CONSOLIDATING.value);

    // ── 趋势 ──────────────────────────────────────────────────

    /** 掌握度趋势枚举。 */
    public enum Trend {
        IMPROVING("IMPROVING"),
        STABLE("STABLE"),
        DECLINING("DECLINING");

        private final String value;
        Trend(String value) { this.value = value; }
        public String value() { return value; }

        public static boolean isValid(String s) {
            if (s == null) return true; // trend 可空
            for (var v : values()) if (v.value.equals(s)) return true;
            return false;
        }
    }

    /** 全部合法趋势值。 */
    public static final Set<String> TRENDS = Set.of(
            Trend.IMPROVING.value, Trend.STABLE.value, Trend.DECLINING.value);

    // ── 场景类型 ──────────────────────────────────────────────

    /** 场景摘要类型枚举。 */
    public enum SceneType {
        EXPLAINING("explaining"),
        LECTURING("lecturing"),
        CONVERSATION("conversation"),
        LESSON_PREP("lesson_prep"),
        PHOTO_QA("photo_qa");

        private final String value;
        SceneType(String value) { this.value = value; }
        public String value() { return value; }

        public static boolean isValid(String s) {
            if (s == null) return false;
            for (var v : values()) if (v.value.equals(s)) return true;
            return false;
        }

        /** photo_qa 映射到 explaining（共享缓存键）。 */
        public static String canonicalize(String scene) {
            return PHOTO_QA.value.equals(scene) ? EXPLAINING.value : scene;
        }
    }

    /** 全部合法场景类型值。 */
    public static final Set<String> SCENE_TYPES = Set.of(
            SceneType.EXPLAINING.value, SceneType.LECTURING.value,
            SceneType.CONVERSATION.value, SceneType.LESSON_PREP.value,
            SceneType.PHOTO_QA.value);

    // ── 事件处理状态 ──────────────────────────────────────────

    /** 事件处理状态枚举。 */
    public enum EventProcessStatus {
        PENDING("PENDING"),
        QUARANTINED("QUARANTINED"),
        PROCESSED("PROCESSED"),
        FAILED("FAILED");

        private final String value;
        EventProcessStatus(String value) { this.value = value; }
        public String value() { return value; }
    }

    /** 全部合法事件处理状态值。 */
    public static final Set<String> EVENT_PROCESS_STATUSES = Set.of(
            EventProcessStatus.PENDING.value, EventProcessStatus.QUARANTINED.value,
            EventProcessStatus.PROCESSED.value, EventProcessStatus.FAILED.value);

    // ── 事件 ACK 状态 ─────────────────────────────────────────

    public static final String EVENT_STATUS_ACCEPTED = "ACCEPTED";
    public static final String EVENT_STATUS_DUPLICATE = "DUPLICATE";

    // ── 画像更新状态 ──────────────────────────────────────────

    public static final String PROFILE_UPDATE_PENDING = "PENDING";

    // ── 错误码 ────────────────────────────────────────────────

    /** M6 API 错误码枚举。 */
    public enum ErrorCode {
        PROFILE_EVENT_INVALID("PROFILE_EVENT_INVALID"),
        PROFILE_EVENT_TYPE_UNSUPPORTED("PROFILE_EVENT_TYPE_UNSUPPORTED"),
        PROFILE_EVENT_VERSION_UNSUPPORTED("PROFILE_EVENT_VERSION_UNSUPPORTED"),
        PROFILE_IDEMPOTENCY_CONFLICT("PROFILE_IDEMPOTENCY_CONFLICT"),
        PROFILE_UNAUTHENTICATED("PROFILE_UNAUTHENTICATED"),
        PROFILE_ACCESS_DENIED("PROFILE_ACCESS_DENIED"),
        PROFILE_NOT_READY("PROFILE_NOT_READY"),
        PROFILE_SERVICE_DEGRADED("PROFILE_SERVICE_DEGRADED"),
        PROFILE_INTERNAL_ERROR("PROFILE_INTERNAL_ERROR");

        private final String value;
        ErrorCode(String value) { this.value = value; }
        public String value() { return value; }
    }

    // ── 时间与大小限制 ────────────────────────────────────────

    /** 事件时间偏差隔离窗口（小时）。 */
    public static final long EVENT_QUARANTINE_HOURS = 24;

    /** 事件数据规范后最大字节数。 */
    public static final int MAX_EVENT_DATA_BYTES = 16 * 1024;

    /** M6 请求体最大字节数。 */
    public static final int MAX_REQUEST_BYTES = 2 * 1024 * 1024;

    /** 摘要序列化最大 code point 数。 */
    public static final int MAX_SUMMARY_CODEPOINTS = 1200;

    // ── 缓存键前缀 ────────────────────────────────────────────

    public static final String CACHE_KEY_PROFILE_PREFIX = "user:profile:";
    public static final String CACHE_KEY_SUMMARY_PREFIX = "user:profile:summary:";
    public static final String CACHE_SCHEMA_VERSION = "1.0";
}
