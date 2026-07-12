# 实现计划

- [x] 1. 扩展 AudioSegment 实体类支持 PPT 上下文信息

  - 在 AudioSegment 实体类中添加 PPT 相关字段（slide_page_number, slide_title, content_point_index 等）
  - 创建数据库迁移脚本，添加新字段到 audio_segments 表
  - _需求: 4.1, 4.3_

- [x] 2. 创建批量语音合成控制器

  - 实现 BulkSpeechController 类，创建 POST /api/speech/bulk-synthesis 接口
  - 创建 BulkSynthesisRequest 和 BulkSynthesisResponse 数据传输对象
  - 实现基本的请求参数验证和错误处理

  - _需求: 1.1, 1.2, 6.1, 6.4_

- [x] 3. 实现批量语音合成核心服务

  - 创建 BulkSpeechService 接口和实现类
  - 实现 slides 按 page_number 排序的处理逻辑
  - 集成现有的 SegmentedSpeechService 进行分句和语音合成

  - _需求: 1.3, 2.3, 2.4_

- [x] 4. 实现 PPT 上下文感知的文本润色

  - 创建 PPTContextualPolishing 服务，根据 PPT 结构生成润色提示词

  - 修改文本润色流程，先根据 PPT 上下文润色，再分句合成
  - 集成现有的 TextPolishingService
  - _需求: 2.1, 2.2_

- [x] 5. 实现全局片段索引和数据库保存

  - 实现全局片段索引策略（页码*1000 + 内容点*100 + 句子索引）
  - 扩展 VoiceDatabaseService，添加保存 PPT 音频片段的方法
  - 确保保存时包含 PPT 页码、标题、内容点索引等信息
  - _需求: 4.2, 4.4, 2.3_

- [x] 6. 实现基本的错误处理机制

  - 添加单个 content_point 处理失败时的容错逻辑
  - 实现基本的错误记录和日志输出
  - 确保部分失败不影响整体处理流程
  - _需求: 5.1, 5.2_

- [x] 7. 实现 PPT 音频查询接口

  - 创建 GET /api/speech/ppt/{sessionId}/page/{pageNumber}接口，查询指定页面的所有音频片段
  - 创建 GET /api/speech/ppt/{sessionId}/segment/{segmentIndex}接口，查询指定片段的音频数据
  - 创建 GET /api/speech/ppt/{sessionId}接口，查询整个 PPT 的音频信息和统计数据
  - 在 BulkSpeechController 中添加这些查询端点
  - _需求: 新增查询需求_

- [x] 8. 扩展 VoiceDatabaseService 查询方法

  - 实现 getSlideAudioSegments 方法，根据 sessionId 和 pageNumber 查询页面音频
  - 实现 getSegmentAudioData 方法，根据 sessionId 和 segmentIndex 查询单个音频片段
  - 实现 getPPTAudioInfo 方法，查询整个 PPT 的音频统计信息
  - 确保查询结果包含完整的 PPT 上下文信息
  - _需求: 新增查询需求_

- [x] 9. 集成测试和验证

  - 使用 requestJson.json 数据测试完整的批量合成流程
  - 验证数据库中保存的音频数据包含正确的 PPT 上下文信息
  - 测试所有查询接口的功能和数据完整性
  - 测试与现有 PlaybackStateManager 的集成
  - _需求: 1.1, 1.2, 1.3, 1.4, 新增查询需求_
