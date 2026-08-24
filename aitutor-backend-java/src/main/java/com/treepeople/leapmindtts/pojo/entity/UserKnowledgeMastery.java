package com.treepeople.leapmindtts.pojo.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.Data;

/**
 * 知识点掌握度实体，映射 {@code user_knowledge_mastery} 表。
 * <p>
 * 每用户每知识点一行，记录该知识点的掌握程度。
 * 掌握度由外部 Python AI 引擎基于学习事件计算。
 * </p>
 */
@Data
@TableName("user_knowledge_mastery")
public class UserKnowledgeMastery {
    @TableId(type = IdType.AUTO) private Long id;
    @TableField("user_id") private Long userId;
    @TableField("kp_id") private Long kpId;
    /** 画像版本号，与 user_profiles.profile_version 对应 */
    @TableField("profile_version") private Long profileVersion;
    /** 掌握度分数，范围 [0, 1]，精度 0.0001 */
    @TableField("mastery_score") private BigDecimal masteryScore;
    /** 掌握度状态：WEAK / CONSOLIDATING / BASIC_MASTERY / MASTERED / INSUFFICIENT_EVIDENCE */
    @TableField("mastery_status") private String masteryStatus;
    /** 置信度，范围 [0, 1]，精度 0.0001 */
    @TableField("confidence") private BigDecimal confidence;
    /** 证据数量（学习事件累计计数） */
    @TableField("evidence_count") private Long evidenceCount;
    /** 趋势：IMPROVING / STABLE / DECLINING（可空） */
    @TableField("trend") private String trend;
    @TableField("algorithm_version") private String algorithmVersion;
    /** 计算时间窗口起始 */
    @TableField("window_start") private LocalDateTime windowStart;
    /** 计算时间窗口结束 */
    @TableField("window_end") private LocalDateTime windowEnd;
    @TableField("updated_at") private LocalDateTime updatedAt;
}
