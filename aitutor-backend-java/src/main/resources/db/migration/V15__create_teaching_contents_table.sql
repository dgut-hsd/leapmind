-- 讲题记录表
CREATE TABLE IF NOT EXISTS `teaching_contents` (
  `explain_id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `user_id` bigint DEFAULT NULL COMMENT '用户ID',
  `image_url` varchar(512) DEFAULT NULL COMMENT '题目图片地址',
  `question_text` text COMMENT 'OCR识别题目文本',
  `ai_answer` text COMMENT 'AI答案',
  `ai_explain` text COMMENT 'AI解题讲解',
  `status` tinyint DEFAULT 0 COMMENT '状态 0处理中 1完成 2识别失败',
  `deleted` tinyint DEFAULT 0 COMMENT '逻辑删除 0未删 1已删',
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`explain_id`),
  KEY `idx_user_id` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='讲题记录表';
