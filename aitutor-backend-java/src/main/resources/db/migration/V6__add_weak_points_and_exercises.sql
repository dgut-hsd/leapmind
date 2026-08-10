-- ===============================================
-- V3: 薄弱点分析与练习推荐功能
-- 新增 user_weak_points + user_exercises 两张表
-- ===============================================

-- -----------------------------------------------
-- 1. 用户薄弱点表
-- -----------------------------------------------
CREATE TABLE user_weak_points (
    id              BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    user_id         BIGINT       NOT NULL COMMENT '用户ID',
    knowledge_point VARCHAR(200) NOT NULL COMMENT '知识点名称',
    subject         VARCHAR(50)  DEFAULT NULL COMMENT '学科',
    weakness_level  VARCHAR(20)  DEFAULT 'MEDIUM' COMMENT '薄弱程度：HIGH/MEDIUM/LOW',
    error_count     INT          DEFAULT 0 COMMENT '错误次数',
    total_count     INT          DEFAULT 0 COMMENT '总答题次数',
    accuracy_rate   DECIMAL(5,2) DEFAULT NULL COMMENT '正确率(%)',
    last_error_time DATETIME     DEFAULT NULL COMMENT '最近一次错误时间',
    status          VARCHAR(20)  DEFAULT 'ACTIVE' COMMENT '状态：ACTIVE/RESOLVED/IMPROVING',
    ai_analysis     TEXT         DEFAULT NULL COMMENT 'AI 综合分析结果(JSON)',
    ai_suggestion   TEXT         DEFAULT NULL COMMENT 'AI 个性化学习建议',
    analyzed_at     DATETIME     DEFAULT NULL COMMENT 'AI 分析时间',
    created_at      DATETIME     DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at      DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_user_knowledge (user_id, knowledge_point),
    KEY idx_user_id (user_id),
    KEY idx_user_subject (user_id, subject),
    KEY idx_status (status),
    KEY idx_user_status (user_id, status),
    CONSTRAINT fk_weak_points_user FOREIGN KEY (user_id) REFERENCES users(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='用户薄弱点分析表';

-- -----------------------------------------------
-- 2. 用户练习记录表
-- -----------------------------------------------
CREATE TABLE user_exercises (
    id              BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    user_id         BIGINT       NOT NULL COMMENT '用户ID',
    exercise_id     VARCHAR(100) NOT NULL COMMENT '练习题ID（外部题库唯一标识）',
    knowledge_point VARCHAR(200) DEFAULT NULL COMMENT '知识点名称',
    subject         VARCHAR(50)  DEFAULT NULL COMMENT '学科',
    is_correct      TINYINT      DEFAULT 0 COMMENT '是否正确：1-正确 0-错误',
    completed_at    DATETIME     DEFAULT NULL COMMENT '完成时间',
    created_at      DATETIME     DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (id),
    KEY idx_user_completed (user_id, completed_at),
    KEY idx_user_knowledge (user_id, knowledge_point),
    KEY idx_user_exercise (user_id, exercise_id),
    CONSTRAINT fk_exercises_user FOREIGN KEY (user_id) REFERENCES users(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='用户练习记录表';

-- ===============================================
-- 对齐 Java 与 Python 薄弱点引擎的 user_weak_points 表结构
-- （原 V4__align_weak_points_with_python.sql 的内容并入本迁移，
--   确保先建表再 ALTER，顺序正确）
--
-- 背景：
--   Python 引擎（刘金华）计算 weakness_score（0-1 连续值）、
--   trend、confusion_count 等字段，但上方建表语句无对应列。
--   此处新增这些列，使 Python 的 upsert 能正常落库，
--   Java 侧的事件上报和 API 也能读取 Python 计算的权威分数。
-- ===============================================

-- 1. knowledge_point 加默认值（Python INSERT 不含此列，避免 NOT NULL 报错）
ALTER TABLE user_weak_points
    MODIFY COLUMN knowledge_point VARCHAR(200) NOT NULL DEFAULT '' COMMENT '知识点名称';

-- 2. 新增 Python 引擎需要的列
ALTER TABLE user_weak_points
    -- 知识点ID（Python引擎使用，关联 knowledge_points.id）
    ADD COLUMN kp_id            BIGINT        DEFAULT NULL COMMENT '知识点ID（关联knowledge_points表）',
    -- 薄弱度分数 0-1（Python 引擎计算的核心字段）
    ADD COLUMN weakness_score   DECIMAL(5,3)  DEFAULT NULL COMMENT '薄弱度分数 0~1，越高越薄弱',
    -- 总答题次数（Python引擎使用，与 total_count 语义相同）
    ADD COLUMN total_attempts   INT           DEFAULT 0   COMMENT '总答题次数（Python引擎统计）',
    -- 错误率 0-1
    ADD COLUMN error_rate       DECIMAL(5,3)  DEFAULT NULL COMMENT '历史错误率 0~1',
    -- 最近10次正确率
    ADD COLUMN recent_correct_rate DECIMAL(5,3) DEFAULT NULL COMMENT '最近10次正确率',
    -- 提问困惑次数
    ADD COLUMN confusion_count  INT           DEFAULT 0   COMMENT '提问困惑次数（M7对话+M1错题本+M6画像）',
    -- 趋势
    ADD COLUMN trend            VARCHAR(20)   DEFAULT NULL COMMENT '趋势：improving/stable/declining',
    -- 最近错题时间（Python引擎使用，与 last_error_time 语义相同）
    ADD COLUMN last_error_at    DATETIME      DEFAULT NULL COMMENT '最近错题时间（Python引擎填充）',
    -- 计算时间
    ADD COLUMN calculated_at    DATETIME      DEFAULT NULL COMMENT '薄弱度计算时间';

-- 3. Python 引擎使用的唯一索引（user_id + kp_id）
ALTER TABLE user_weak_points
    ADD UNIQUE KEY uk_user_kp (user_id, kp_id);
