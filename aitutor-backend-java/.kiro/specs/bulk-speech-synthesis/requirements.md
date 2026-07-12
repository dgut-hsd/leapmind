# 需求文档

## 介绍

本功能旨在为AI老师系统新增批量语音合成接口，接收前端发送的完整讲课大纲数据，根据大纲内容分段生成音频并保存到数据库中。与之前的实时合成不同，这个接口将处理整个讲课内容的预生成，支持后续按需播放。

## 需求

### 需求 1

**用户故事：** 作为前端开发者，我希望能够发送完整的讲课大纲给后端，让系统自动将所有内容分段合成为音频并保存，以便后续播放时直接从数据库获取。

#### 验收标准

1. WHEN 前端发送包含title和slides数组的JSON数据 THEN 系统 SHALL 接收并解析完整的讲课大纲
2. WHEN 系统接收到大纲数据 THEN 系统 SHALL 为每个slide的content_points生成对应的音频片段
3. WHEN 处理slides数组时 THEN 系统 SHALL 按page_number顺序处理每个slide
4. WHEN slide包含多个content_points时 THEN 系统 SHALL 将每个content_point作为独立的音频片段处理

### 需求 2

**用户故事：** 作为系统管理员，我希望系统能够复用现有的分段语音合成逻辑，保持代码架构的一致性和可维护性。

#### 验收标准

1. WHEN 处理每个content_point时 THEN 系统 SHALL 调用现有的SegmentedSpeechService进行文本分句和语音合成
2. WHEN 进行语音合成时 THEN 系统 SHALL 使用现有的TextToSpeechService和文本润色功能
3. WHEN 保存音频数据时 THEN 系统 SHALL 使用现有的VoiceDatabaseService进行数据库操作
4. WHEN 管理播放状态时 THEN 系统 SHALL 使用现有的PlaybackStateManager进行状态管理

### 需求 3

**用户故事：** 作为用户，我希望系统能够提供批量合成的进度反馈，让我了解处理状态和完成情况。

#### 验收标准

1. WHEN 开始批量合成时 THEN 系统 SHALL 返回会话ID和总的处理任务数量
2. WHEN 处理过程中 THEN 系统 SHALL 记录当前处理进度和已完成的片段数量
3. WHEN 单个slide处理完成时 THEN 系统 SHALL 更新处理进度状态
4. WHEN 所有slides处理完成时 THEN 系统 SHALL 返回完成状态和生成的音频片段总数

### 需求 4

**用户故事：** 作为数据分析师，我希望保存的音频数据包含完整的元数据信息，以便进行内容管理和分析。

#### 验收标准

1. WHEN 保存音频片段时 THEN 系统 SHALL 记录slide的page_number、title和在slide中的content_point索引
2. WHEN 创建会话时 THEN 系统 SHALL 保存讲课的title作为会话标题
3. WHEN 保存片段元数据时 THEN 系统 SHALL 包含slide_type、type和description等附加信息
4. WHEN 生成会话ID时 THEN 系统 SHALL 确保ID的唯一性并便于后续查询

### 需求 5

**用户故事：** 作为系统维护人员，我希望系统能够处理批量合成过程中的异常情况，确保系统的稳定性。

#### 验收标准

1. WHEN 单个content_point合成失败时 THEN 系统 SHALL 记录错误信息但继续处理其他内容
2. WHEN 网络或TTS服务异常时 THEN 系统 SHALL 实现重试机制并记录失败的片段
3. WHEN 数据库保存失败时 THEN 系统 SHALL 记录错误日志并提供重试选项
4. WHEN 处理超时时 THEN 系统 SHALL 返回部分完成的结果和未完成的任务列表

### 需求 6

**用户故事：** 作为前端开发者，我希望接口能够提供清晰的响应格式，便于前端处理和展示结果。

#### 验收标准

1. WHEN 接口调用成功时 THEN 系统 SHALL 返回包含sessionId、totalSegments和processingStatus的响应
2. WHEN 处理完成时 THEN 系统 SHALL 返回成功生成的片段数量和失败的片段信息
3. WHEN 发生错误时 THEN 系统 SHALL 返回明确的错误码和错误描述信息
4. WHEN 返回响应时 THEN 系统 SHALL 使用标准的HTTP状态码表示请求结果

### 需求 7

**用户故事：** 作为前端开发者，我希望能够查询指定PPT页面的音频数据，以便实现按页播放功能。

#### 验收标准

1. WHEN 前端请求指定页面的音频时 THEN 系统 SHALL 返回该页面所有音频片段的信息
2. WHEN 查询页面音频时 THEN 系统 SHALL 包含片段的文本内容、音频数据和播放时长
3. WHEN 页面不存在时 THEN 系统 SHALL 返回404错误和相应的错误信息
4. WHEN 查询成功时 THEN 系统 SHALL 按content_point_index和segment_index排序返回音频片段

### 需求 8

**用户故事：** 作为前端开发者，我希望能够查询单个音频片段的详细信息，以便实现精确的音频播放控制。

#### 验收标准

1. WHEN 前端请求指定片段的音频时 THEN 系统 SHALL 返回该片段的完整音频数据和元数据
2. WHEN 查询片段信息时 THEN 系统 SHALL 包含PPT页码、内容点索引、原始文本、润色文本等信息
3. WHEN 片段不存在时 THEN 系统 SHALL 返回404错误和相应的错误信息
4. WHEN 查询成功时 THEN 系统 SHALL 返回可直接播放的音频数据格式

### 需求 9

**用户故事：** 作为前端开发者，我希望能够查询整个PPT的音频概览信息，以便了解PPT的整体结构和播放状态。

#### 验收标准

1. WHEN 前端请求PPT概览时 THEN 系统 SHALL 返回PPT的基本信息和音频统计数据
2. WHEN 查询PPT信息时 THEN 系统 SHALL 包含总页数、总片段数、总时长等统计信息
3. WHEN 查询PPT信息时 THEN 系统 SHALL 包含每页的音频片段数量和时长统计
4. WHEN PPT不存在时 THEN 系统 SHALL 返回404错误和相应的错误信息