create table lesson_sessions
(
    id             bigint auto_increment
        primary key,
    course_id     varchar(64)                           not null comment '会话ID',
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

create table audio_segments
(
    id                bigint auto_increment
        primary key,
    course_id        varchar(64)                           not null comment '会话ID',
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
    segment_metadata  MEDIUMTEXT                                  null comment '鐗囨?鍏冩暟鎹?JSON鏍煎紡锛屽寘鍚?瘡涓?墖娈电殑鏂囨湰鍐呭?銆佹椂闀跨瓑淇℃伅)',
    constraint uk_session_page
        unique (course_id, segment_index),
    constraint audio_segments_ibfk_1
        foreign key (course_id) references lesson_sessions (course_id)
);

create index idx_audio_segments_segment_count
    on audio_segments (course_id, segment_count);

create index idx_audio_segments_session_page
    on audio_segments (course_id, segment_index);

create index idx_audio_segments_session_segment
    on audio_segments (course_id, segment_index);

create index idx_audio_segments_session_slide
    on audio_segments (course_id, slide_page_number);

create index idx_audio_segments_slide_content
    on audio_segments (course_id, slide_page_number);

create index idx_course_id
    on audio_segments (course_id);

create index idx_created_at
    on lesson_sessions (created_at);

create index idx_course_id
    on lesson_sessions (course_id);

create table student_questions
(
    id             bigint auto_increment
        primary key,
    course_id     varchar(255)                          null,
    segment_index  int                                   null comment '打断时的片段索引',
    question_text  text                                  null comment '问题文本',
    answer_text    text                                  null comment 'AI回答文本',
    question_audio longblob                              null comment '问题音频数据',
    answer_audio   longblob                              null comment '回答音频数据',
    question_type  varchar(20) default 'TEXT'            null comment '提问类型: TEXT, VOICE',
    created_at     timestamp   default CURRENT_TIMESTAMP null,
    constraint student_questions_ibfk_1
        foreign key (course_id) references lesson_sessions (course_id)
);

create index idx_created_at
    on student_questions (created_at);

create index idx_course_id
    on student_questions (course_id);

