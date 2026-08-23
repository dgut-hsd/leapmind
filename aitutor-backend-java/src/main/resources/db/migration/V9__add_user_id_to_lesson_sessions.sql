-- V9: 为 lesson_sessions 添加 user_id 归属字段（用于接口权限校验，防止越权访问他人会话）
ALTER TABLE lesson_sessions
    ADD COLUMN user_id BIGINT NULL COMMENT '所属用户ID（NULL表示未关联用户）' AFTER course_id;

CREATE INDEX idx_lesson_sessions_user ON lesson_sessions(user_id);
