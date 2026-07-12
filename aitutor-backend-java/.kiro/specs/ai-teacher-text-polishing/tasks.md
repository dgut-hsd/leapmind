# 实现计划

- [x] 1. 创建文本润色配置类和属性

  - 创建 TextPolishingProperties 配置类，定义所有润色相关的配置参数
  - 在 application.yml 中添加 text-polishing 配置节点
  - 实现配置验证逻辑，确保配置参数的有效性
  - _需求: 2.1, 2.2_

- [x] 2. 创建文本润色数据模型

  - 实现 TextPolishingRequest 请求模型，包含原始文本和润色参数
  - 实现 TextPolishingResponse 响应模型，包含润色结果和元数据
  - 实现 PolishingMetrics 指标模型，用于记录润色过程的性能数据
  - _需求: 3.1, 3.2_

- [x] 3. 扩展 AIModelService 支持文本润色

  - 在 AIModelService 中添加 polishText 方法，专门用于文本润色 API 调用
  - 实现润色专用的请求构建逻辑，使用配置的润色提示词
  - 添加润色请求的错误处理和重试机制
  - 编写 AIModelService 润色功能的单元测试
  - _需求: 1.1, 3.1, 3.2_

- [x] 4. 实现 TextPolishingService 核心服务

  - 创建 TextPolishingService 类，实现主要的文本润色逻辑
  - 实现 polishText 方法，调用 AIModelService 进行文本润色
  - 实现 polishTextWithTimeout 方法，添加超时控制机制
  - 实现 isServiceAvailable 方法，检查润色服务的可用性
  - _需求: 1.1, 1.2, 4.1, 4.2_

- [x] 5. 实现降级和错误处理机制

  - 在 TextPolishingService 中实现 polishTextWithFallback 方法
  - 添加超时处理逻辑，超时时自动返回原始文本
  - 实现 API 不可用时的降级策略
  - 添加文本长度验证和截断逻辑
  - _需求: 1.3, 4.1, 4.2, 4.3_

- [x] 6. 集成润色功能到 AiTeacherService

  - 修改 AiTeacherService 的 startLesson 方法，集成文本润色步骤
  - 在润色和 TTS 之间建立响应式流处理链
  - 添加润色功能的开关控制，支持动态启用/禁用
  - 确保润色失败时不影响原有的 TTS 流程
  - _需求: 1.1, 1.2, 1.3_

- [-] 7. 实现日志记录和监控

  - 在 TextPolishingService 中添加详细的日志记录
  - 记录润色请求的开始、结束、成功、失败等关键事件
  - 实现 PolishingMetrics 的收集和记录逻辑
  - 添加性能指标的统计和输出
  - _需求: 3.1, 3.2, 3.3_

- [ ] 8. 编写 TextPolishingService 单元测试

  - 编写正常润色流程的测试用例
  - 编写超时处理的测试用例
  - 编写 API 异常处理的测试用例
  - 编写降级机制的测试用例
  - _需求: 1.1, 1.3, 4.1, 4.2_

- [ ] 9. 编写 AiTeacherService 集成测试

  - 编写润色功能开启时的端到端测试
  - 编写润色功能关闭时的测试用例
  - 编写润色失败时的降级测试
  - 验证修改后的 startLesson 方法的完整流程
  - _需求: 1.1, 1.2, 1.3_

- [x] 10. 配置文件更新和文档


  - 更新 application.yml 和 application-dev.yml 配置文件
  - 添加润色功能的配置说明注释
  - 验证配置加载和默认值设置
  - 测试不同配置组合下的系统行为
  - _需求: 2.1, 2.2_

- [ ] 11. 性能测试和优化

  - 编写润色功能的性能基准测试
  - 测试不同文本长度下的响应时间
  - 验证超时机制的准确性
  - 测试并发请求下的系统表现
  - _需求: 4.1, 4.3_

- [ ] 12. 端到端集成验证
  - 部署完整的润色功能到测试环境
  - 验证从接收讲课文本到语音合成的完整流程
  - 测试各种边界情况和异常场景
  - 确认润色后的文本质量符合教学要求
  - _需求: 1.1, 5.1, 5.2, 5.3_
