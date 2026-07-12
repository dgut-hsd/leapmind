# 实现计划

- [x] 1. 创建分段语音配置类和数据模型

  - 创建 SegmentedSpeechProperties 配置类，定义所有分段语音相关的配置参数
  - 实现 SpeechSegment 数据模型，包含片段索引、文本、音频数据等字段
  - 实现 SegmentedSpeechResult 数据模型，包含会话信息和片段列表
  - 实现 PlaybackState 和 PlaybackProgress 数据模型，用于播放状态管理
  - 在 application.yml 中添加 segmented-speech 配置节点
  - _需求: 6.1, 6.2, 6.3, 6.4_

- [x] 2. 实现文本分句功能

  - 在 SegmentedSpeechService 中实现 splitTextBySentence 方法
  - 支持中英文句号、感叹号、问号等分句符号
  - 实现智能分句逻辑，处理缩写和特殊情况
  - 添加最大片段长度限制，对过长句子进行二次分割
  - 编写文本分句功能的单元测试
  - _需求: 1.1, 1.2, 1.3_

-

- [x] 3. 实现 PlaybackStateManager 核心服务

  - 创建 PlaybackStateManager 类，管理语音片段存储和播放状态
  - 实现 storeSpeechSegment 方法，存储语音片段到内存缓存
  - 实现 getSegment 和 getSegmentsFrom 方法，检索指定的语音片段
  - 实现 savePlaybackPosition 和 getCurrentPlaybackPosition 方法，管理播放位置
  - 实现会话级别的数据隔离和并发安全机制
  - _需求: 2.1, 2.2, 2.3, 2.4_

- [x] 4. 实现 SegmentedSpeechService 核心服务

  - 创建 SegmentedSpeechService 类，实现分段语音合成的主要逻辑
  - 实现 createSegmentedSpeech 方法，协调文本分句和 TTS 合成流程
  - 实现 synthesizeSegments 方法，批量进行 TTS 合成并控制并发数量
  - 集成 PlaybackStateManager，存储合成的语音片段
  - 实现流式处理，避免大量语音数据同时加载到内存
  - _需求: 1.1, 1.4, 4.1, 4.2, 4.3_

- [x] 5. 扩展 AiTeacherService 支持分段语音

  - 在 AiTeacherService 中添加 startSegmentedLesson 方法
  - 集成文本润色和分段语音合成的完整流程
  - 实现 playFromSegment 方法，支持从指定片段开始播放
  - 实现 pausePlayback 和 resumePlayback 方法，支持播放控制
  - 保持向后兼容性，不影响现有的 startLesson 方法
  - _需求: 3.1, 3.2, 3.3, 3.4_

- [x] 6. 实现播放进度和状态管理

  - 实现 getPlaybackProgress 方法，提供播放进度信息
  - 实现 seekToSegment 方法，支持跳转到指定片段
  - 添加播放状态的实时更新机制
  - 实现播放完成后的状态重置逻辑
  - 编写播放控制功能的单元测试
  - _需求: 5.1, 5.2, 5.3, 5.4_

- [x] 7. 实现内存管理和清理机制

  - 在 PlaybackStateManager 中实现自动内存清理功能
  - 实现会话超时检测和过期数据清理
  - 添加内存使用量监控和限制机制
  - 实现 cleanupSession 方法，手动清理指定会话数据
  - 添加定时任务，定期清理过期的语音片段
  - _需求: 4.1, 4.2, 4.3, 4.4_

- [x] 8. 实现错误处理和降级机制

  - 在 SegmentedSpeechService 中添加 TTS 合成失败的重试逻辑
  - 实现单个片段合成失败时的跳过机制
  - 添加分段语音功能的降级策略，失败时回退到普通合成
  - 实现内存不足时的自动清理和限流机制
  - 编写各种异常场景的测试用例
  - _需求: 4.1, 4.2, 4.3_

- [x] 9. 更新前端支持分段播放控制

  - 修改 ai-teacher.html，添加分段播放的用户界面
  - 实现播放进度显示和片段跳转功能
  - 添加暂停/恢复按钮和播放状态指示
  - 实现播放中断后的自动恢复功能
  - 添加播放进度条和当前片段文本显示
  - _需求: 3.1, 3.2, 3.3, 5.1, 5.2_

- [x] 10. 创建分段语音的 REST API 接口

  - 在 AiTeacherController 中添加分段语音相关的 API 端点
  - 实现 /api/ai-teacher/lesson/start-segmented 接口
  - 实现 /api/ai-teacher/playback/play-from/{segmentIndex} 接口
  - 实现 /api/ai-teacher/playback/pause 和 /api/ai-teacher/playback/resume 接口
  - 实现 /api/ai-teacher/playback/progress 接口获取播放进度
  - 添加适当的请求验证和错误处理
  - _需求: 2.1, 3.1, 3.2, 3.3, 5.1_

- [x] 11. 实现预加载和缓存优化

  - 在 SegmentedSpeechService 中实现语音片段预加载功能
  - 实现智能缓存策略，预先合成后续几个片段
  - 添加缓存命中率统计和监控
  - 实现缓存大小限制和 LRU 淘汰策略
  - 优化 TTS 请求的批处理和并发控制
  - _需求: 4.1, 4.2, 4.3_

- [x] 12. 添加日志记录和监控指标

  - 在所有关键方法中添加详细的日志记录
  - 实现分段语音合成的性能指标收集
  - 添加播放状态变化的日志记录
  - 实现内存使用情况的监控和报告
  - 添加错误统计和成功率监控
  - _需求: 4.1, 4.2, 4.3, 4.4_

- [ ] 13. 编写单元测试和集成测试

  - 编写 SegmentedSpeechService 的单元测试
  - 编写 PlaybackStateManager 的单元测试
  - 编写文本分句功能的测试用例
  - 编写播放控制功能的集成测试
  - 编写并发安全和内存管理的测试
  - _需求: 1.1, 2.1, 3.1, 4.1_

- [x] 14. 性能测试和优化

  - 编写大文本分段处理的性能测试
  - 测试多用户并发使用的系统表现
  - 验证内存使用情况和清理机制的有效性
  - 测试 TTS 并发合成的性能和稳定性
  - 优化关键路径的性能瓶颈
  - _需求: 4.1, 4.2, 4.3, 4.4_

- [x] 15. 端到端集成验证

  - 部署完整的分段语音功能到测试环境
  - 验证从文本润色到分段播放的完整流程
  - 测试播放中断和恢复功能的准确性
  - 验证多会话并发处理的正确性
  - 确认分段语音的质量和用户体验符合要求
  - _需求: 1.1, 2.1, 3.1, 4.1, 5.1, 6.1_
