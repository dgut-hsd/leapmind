package com.treepeople.leapmindtts.pojo.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.Data;

/**
 * 用户画像实体，映射 {@code user_profiles} 表。
 * <p>
 * 每用户一行，存储聚合后的画像数据。画像状态由外部 Python AI 引擎驱动：
 * <ul>
 *   <li>{@code READY} — 画像已就绪，包含完整 profileDataJson</li>
 *   <li>{@code NOT_READY} — 画像未就绪，数据不足</li>
 *   <li>{@code STALE} — 画像已过时，有新事件待重新计算</li>
 * </ul>
 * </p>
 */
@Data
@TableName("user_profiles")
public class UserProfile {
    @TableId(type = IdType.AUTO) private Long id;
    @TableField("user_id") private Long userId;
    /** 画像版本号，每次引擎重算 +1。NOT_READY 状态版本号为 0。 */
    @TableField("profile_version") private Long profileVersion;
    /** 画像状态：READY / NOT_READY / STALE */
    @TableField("profile_status") private String profileStatus;
    /** 状态原因（如 NO_PROFILE, ENGINE_TIMEOUT 等） */
    @TableField("status_reason") private String statusReason;
    @TableField("grade") private String grade;
    /** 偏好内容模式列表（JSON 数组字符串） */
    @TableField("preferred_content_modes_json") private String preferredContentModesJson;
    @TableField("preferred_explanation_style") private String preferredExplanationStyle;
    /** 学习节奏：slow / moderate / fast */
    @TableField("learning_pace") private String learningPace;
    /** 近期焦点列表（JSON 数组字符串） */
    @TableField("recent_focus_json") private String recentFocusJson;
    /** 画像摘要文本（最大 16383 字符） */
    @TableField("summary_profile") private String summaryProfile;
    /** 完整画像数据（JSON 对象字符串，READY/STALE 状态必填） */
    @TableField("profile_data_json") private String profileDataJson;
    @TableField("algorithm_version") private String algorithmVersion;
    /** 画像置信度，范围 [0, 1] */
    @TableField("confidence") private BigDecimal confidence;
    @TableField("last_event_at") private LocalDateTime lastEventAt;
    @TableField("last_processed_event_id") private Long lastProcessedEventId;
    @TableField("computed_at") private LocalDateTime computedAt;
    @TableField("created_at") private LocalDateTime createdAt;
    @TableField("updated_at") private LocalDateTime updatedAt;
}
