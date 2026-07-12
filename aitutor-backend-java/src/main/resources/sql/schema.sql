create table if not exists `leapmind-voice`.course_schedule
(
    id              int unsigned not null comment '自增主键，唯一标识每条课程记录'
        primary key,
    course_id       varchar(255) not null comment '课程id（会话id）',
    stage_name      varchar(20)  not null comment '阶段（如：小学、初中、高中、大学等）',
    grade_name      varchar(20)  not null comment '年级（如：1-12，适配小学到高中）',
    semester        varchar(20)  not null comment '学期（区分上册或下册）',
    chapter_number  int          not null comment '章节序号（单学期章节数通常较少）',
    chapter_title   varchar(200) not null comment '章节题目',
    section_content text         null comment '章节内容（支持长文本）',
    section_number  float        null comment '章',
    section_title   varchar(20)  null comment '章名',
    subject         varchar(5)   null
)
    comment '课程表，存储各阶段、年级的章节信息' collate = utf8mb4_unicode_ci;

create index idx_stage_grade
    on `leapmind-voice`.course_schedule (stage_name, grade_name);

create table if not exists `leapmind-voice`.education_stages
(
    id          bigint auto_increment comment '阶段ID'
        primary key,
    stage_name  varchar(20)                        not null comment '阶段名称',
    stage_code  varchar(20)                        not null comment '阶段代码',
    grade_code  varchar(20)                        not null comment '年级代码',
    grade_name  varchar(20)                        not null comment '年级名称',
    description varchar(200)                       null comment '阶段描述',
    sort_order  int      default 0                 null comment '排序',
    created_at  datetime default CURRENT_TIMESTAMP null comment '创建时间'
)
    comment '教育阶段表' collate = utf8mb4_unicode_ci;

create index idx_grade_code
    on `leapmind-voice`.education_stages (grade_code);

create index idx_stage_code
    on `leapmind-voice`.education_stages (stage_code);

create table if not exists `leapmind-voice`.lesson_sessions
(
    id             bigint auto_increment
        primary key,
    course_id      varchar(255)                          not null comment '课程（会话）ID',
    title          varchar(255)                          null comment '课程标题',
    original_text  text                                  null comment '原始课程内容',
    polished_text  text                                  null comment '润色后的课程内容',
    total_segments int                                   null comment '总片段数',
    total_duration bigint                                null comment '总时长(毫秒)',
    created_at     timestamp   default CURRENT_TIMESTAMP null,
    total_pages    int                                   null comment '鎬婚〉鏁',
    storage_type   varchar(20) default 'PAGE_LEVEL'      null comment '瀛樺偍绫诲瀷锛歋EGMENT_LEVEL(鐗囨?绾? 鎴?PAGE_LEVEL(椤甸潰绾?',
    constraint course_id
        unique (course_id)
);

create table if not exists `leapmind-voice`.audio_segments
(
    id                bigint auto_increment
        primary key,
    course_id         varchar(255)                          not null comment '课程（会话）ID',
    segment_index     int                                   not null comment '椤甸潰缂栧彿锛堥〉闈㈢骇瀛樺偍锛',
    text_content      text                                  null comment '椤甸潰鏍囬?鎴栨弿杩',
    audio_data        longblob                              null comment '椤甸潰鎵?湁闊抽?鐗囨?鐨勫悎骞舵暟鎹',
    audio_size        bigint                                null comment '椤甸潰鎬婚煶棰戞枃浠跺ぇ灏?瀛楄妭)',
    duration          bigint                                null comment '椤甸潰鎬婚煶棰戞椂闀?姣??)',
    audio_format      varchar(10) default 'wav'             null comment '音频格式',
    sample_rate       int         default 16000             null comment '采样率',
    checksum          varchar(64)                           null comment '音频数据校验和',
    created_at        timestamp   default CURRENT_TIMESTAMP null,
    slide_page_number int                                   null comment 'PPT页码',
    slide_title       varchar(500)                          null comment 'PPT页面标题',
    slide_type        varchar(50)                           null comment 'PPT页面类型(title, agenda, content, thankyou等)',
    slide_description text                                  null comment 'PPT页面描述',
    original_text     text                                  null comment '润色前的原始文本',
    polished_text     text                                  null comment '润色后的文本',
    segment_count     int         default 0                 null comment '璇ラ〉闈㈠寘鍚?殑闊抽?鐗囨?鏁伴噺',
    segment_metadata  mediumtext                            null comment '鐗囨?鍏冩暟鎹?JSON鏍煎紡锛屽寘鍚?瘡涓?墖娈电殑鏂囨湰鍐呭?銆佹椂闀跨瓑淇℃伅)',
    constraint uk_session_page
        unique (course_id, segment_index),
    constraint audio_segments_ibfk_1
        foreign key (course_id) references `leapmind-voice`.lesson_sessions (course_id)
);

create index idx_audio_segments_segment_count
    on `leapmind-voice`.audio_segments (course_id, segment_count);

create index idx_audio_segments_session_page
    on `leapmind-voice`.audio_segments (course_id, segment_index);

create index idx_audio_segments_session_segment
    on `leapmind-voice`.audio_segments (course_id, segment_index);

create index idx_audio_segments_session_slide
    on `leapmind-voice`.audio_segments (course_id, slide_page_number);

create index idx_audio_segments_slide_content
    on `leapmind-voice`.audio_segments (course_id, slide_page_number);

create index idx_session_id
    on `leapmind-voice`.audio_segments (course_id);

create index idx_created_at
    on `leapmind-voice`.lesson_sessions (created_at);

create index idx_session_id
    on `leapmind-voice`.lesson_sessions (course_id);

create table if not exists `leapmind-voice`.ppt_slides
(
    id           int auto_increment
        primary key,
    course_id    varchar(255)                       not null comment '课程（会话）ID',
    slide_index  int                                not null,
    slide_id     varchar(100)                       not null,
    title        varchar(255)                       null,
    content_type varchar(50)                        null,
    html_content text                               null,
    create_at    datetime default CURRENT_TIMESTAMP null
);

create table if not exists `leapmind-voice`.project_outline
(
    id           int auto_increment comment '主键'
        primary key,
    course_id    varchar(255) not null comment '课程（会话）ID',
    outline_json json         not null comment 'json格式的大纲',
    create_time  datetime     null comment '创建时间',
    update_time  datetime     null comment '更新时间',
    constraint project_outline_pk_2
        unique (id)
)
    comment 'PPT课程大纲表';

create index project_outline__id_index
    on `leapmind-voice`.project_outline (id);

create index project_outline__index_course_id
    on `leapmind-voice`.project_outline (course_id);

create table if not exists `leapmind-voice`.sms_verification_code
(
    id                bigint unsigned auto_increment comment '主键ID'
        primary key,
    phone             varchar(20)                  not null comment '接收验证码的手机号（如：+8613800138000）',
    verification_code varchar(10)                  not null comment '手机验证码（通常为6位数字）',
    expire_time       datetime                     not null comment '验证码有效期截止时间（如：创建时间+10分钟）',
    is_used           tinyint unsigned default '0' not null comment '使用状态：0-未使用 1-已使用',
    constraint uk_phone_code
        unique (phone, verification_code)
)
    comment '手机验证码存储表';

create index idx_phone_status
    on `leapmind-voice`.sms_verification_code (phone, is_used, expire_time);

create table if not exists `leapmind-voice`.student_questions
(
    id             bigint auto_increment
        primary key,
    course_id      varchar(255)                          null comment '课程id',
    segment_index  int                                   null comment '打断时的片段索引',
    question_text  text                                  null comment '问题文本',
    answer_text    text                                  null comment 'AI回答文本',
    question_audio longblob                              null comment '问题音频数据',
    answer_audio   longblob                              null comment '回答音频数据',
    question_type  varchar(20) default 'TEXT'            null comment '提问类型: TEXT, VOICE',
    created_at     timestamp   default CURRENT_TIMESTAMP null,
    constraint student_questions_ibfk_1
        foreign key (course_id) references `leapmind-voice`.lesson_sessions (course_id)
);

create index idx_created_at
    on `leapmind-voice`.student_questions (created_at);

create index idx_session_id
    on `leapmind-voice`.student_questions (course_id);

CREATE TABLE `users`
(
    `id`           bigint                                                                                                                      NOT NULL AUTO_INCREMENT COMMENT '用户ID',
    `username`     varchar(50) COLLATE utf8mb4_unicode_ci                                                                                      NOT NULL COMMENT '用户账号',
    `password`     varchar(255) COLLATE utf8mb4_unicode_ci                                                                                     NOT NULL COMMENT '加密密码',
    `grade`        enum ('GRADE_1','GRADE_2','GRADE_3','GRADE_4','GRADE_5','GRADE_6','GRADE_7','GRADE_8','GRADE_9') COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '年级',
    `stage`        varchar(10) COLLATE utf8mb4_unicode_ci  DEFAULT NULL COMMENT '年级（PRIMARY、JUNIOR、SENIOR）',
    `student_name` varchar(100) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '学生姓名',
    `email`        varchar(100) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '邮箱',
    `phone`        varchar(20) COLLATE utf8mb4_unicode_ci  DEFAULT NULL COMMENT '手机号',
    `status`       tinyint                                 DEFAULT '1' COMMENT '账号状态 1-正常 0-禁用',
    `created_at`   datetime                                DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at`   datetime                                DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `identify`     varchar(5) COLLATE utf8mb4_unicode_ci   DEFAULT NULL COMMENT '身份（普通用户、管理员）',
    PRIMARY KEY (`id`),
    UNIQUE KEY `username` (`username`),
    KEY `idx_username` (`username`),
    KEY `idx_grade` (`grade`),
    KEY `idx_email` (`email`),
    KEY `idx_phone` (`phone`)
) ENGINE = InnoDB
  AUTO_INCREMENT = 13
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci COMMENT ='用户表';



create index idx_email
    on `leapmind-voice`.users (email);

create index idx_grade
    on `leapmind-voice`.users (grade);

create index idx_phone
    on `leapmind-voice`.users (phone);

create index idx_username
    on `leapmind-voice`.users (username);

