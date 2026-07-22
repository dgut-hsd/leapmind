# M5 AI备课 — API 接口文档

## 概述

备课内容生成接口，接收知识点和教学目标，分三阶段流式返回：教学大纲 → PPT 结构 → 口语化讲解词。

---

## 接口

### POST /api/lesson-prep/generate

**Content-Type:** `application/json`  
**Accept:** `text/event-stream`  
**认证:** Cookie `session_id`（需先调用 `POST /api/auth/login`）

---

## 请求体

```json
{
  "user_id": 1,
  "title": "勾股定理",
  "subject": "math",
  "grade": "grade_8",
  "knowledge_point_names": ["勾股定理", "勾股定理的证明"],
  "teaching_goals": ["理解勾股定理的内容", "掌握勾股定理的证明方法", "能运用勾股定理解题"],
  "total_hours": 2,
  "style": "standard",
  "weak_points": "学生容易混淆斜边和直角边",
  "user_profile_summary": ""
}
```

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `user_id` | int | 是 | 用户ID |
| `title` | string | 是 | 备课标题 |
| `subject` | string | 是 | 科目。枚举：`math`, `chinese`, `english`, `physics`, `chemistry`, `biology` |
| `grade` | string | 是 | 年级。枚举：`grade_7` ~ `grade_12` |
| `knowledge_point_names` | string[] | 是 | 知识点名称列表 |
| `teaching_goals` | string[] | 是 | 教学目标列表 |
| `total_hours` | int | 否 | 课时数，默认 `1` |
| `style` | string | 否 | 教学风格。枚举：`standard`, `detailed`, `interactive`，默认 `standard` |
| `weak_points` | string | 否 | 学生薄弱点描述 |
| `user_profile_summary` | string | 否 | 用户画像摘要（由 M6 提供） |

---

## 响应（SSE 流式）

### 事件流顺序

```
event: syllabus_chunk * N     Stage 1：教学大纲生成（逐 token 流式）
event: syllabus_done           Stage 1 完成

event: slide * N               Stage 2：PPT 结构生成（逐页推送）
event: slides_done             Stage 2 完成

event: narration * N           Stage 3：讲解词生成（逐页推送）
event: done                    全部完成
```

---

### 事件明细

#### syllabus_chunk — 教学大纲逐 token

```json
{
  "chunk": "勾"
}
```

| 字段 | 类型 | 说明 |
|------|------|------|
| `chunk` | string | AI 返回的文本片段，前端累积拼接 |

#### syllabus_done — 教学大纲完成

```json
{
  "syllabus": {
    "title": "勾股定理",
    "subject": "math",
    "grade": "grade_8",
    "total_hours": 2,
    "overall_goal": "理解勾股定理，掌握证明方法，能运用解决实际问题",
    "sections": [
      {
        "hour_index": 1,
        "title": "第一课时：勾股定理的认识与应用",
        "teaching_goals": ["理解勾股定理的内容", "能直接用公式求斜边"],
        "key_points": ["勾股定理公式 a² + b² = c²", "区分斜边和直角边"],
        "difficult_points": ["在实际图形中识别斜边"],
        "teaching_process": [
          {"step": "导入", "duration_min": 5, "content": "通过直角三角形图片引入"},
          {"step": "新课讲授", "duration_min": 20, "content": "讲解定理并举例"},
          {"step": "课堂练习", "duration_min": 10, "content": "学生练习求斜边"},
          {"step": "小结", "duration_min": 5, "content": "总结公式"}
        ],
        "homework": "完成课后练习第1-3题"
      }
    ]
  },
  "sections_count": 2
}
```

| 字段 | 类型 | 说明 |
|------|------|------|
| `syllabus` | object | 完整教学大纲 JSON |
| `sections_count` | int | 课时数 |

#### slide — PPT 单页

```json
{
  "page_num": 3,
  "total_pages": 11,
  "type": "content",
  "title": "勾股定理公式 a² + b² = c²",
  "subtitle": "第一课时：勾股定理的认识与应用",
  "bullet_points": ["直角三角形两直角边的平方和等于斜边的平方", "a 和 b 是直角边，c 是斜边"],
  "image_suggestion": "直角三角形标注abc示意图",
  "formula": "a^2 + b^2 = c^2",
  "interaction": null
}
```

| 字段 | 类型 | 说明 |
|------|------|------|
| `page_num` | int | 页码，从 1 开始 |
| `total_pages` | int | 总页数（前期推送的页此值为 0，最终由 slides_done 确认） |
| `type` | string | 页面类型：`cover`, `content`, `interactive`, `summary`, `homework` |
| `title` | string | 页标题 |
| `subtitle` | string | 副标题 |
| `bullet_points` | string[] | 正文要点列表 |
| `image_suggestion` | string | 配图描述（供后续 AI 生图使用） |
| `formula` | string | LaTeX 公式，无则为 `null` |
| `interaction` | object | 互动问题，无则为 `null` |

#### interaction 对象（当 type=interactive 时）

```json
{
  "type": "question",
  "question": "在一个直角三角形中，如果两条直角边分别是 6 和 8，斜边是多少？",
  "expected_answer": "10"
}
```

| 字段 | 类型 | 说明 |
|------|------|------|
| `type` | string | 互动类型：`question`, `discuss`, `think` |
| `question` | string | 互动问题内容 |
| `expected_answer` | string | 参考答案 |

#### slides_done — PPT 结构完成

```json
{
  "total_pages": 11
}
```

#### narration — 单页讲解词

```json
{
  "page_num": 3,
  "total_pages": 11,
  "narration_text": "好，同学们，我们刚才看了目录，知道今天要学一个超级重要的定理——勾股定理。那这个定理到底说什么呢？简单说，就是任何直角三角形里，两条直角边的平方和，等于斜边的平方。注意，斜边是直角对着的那条最长边，千万别和两条短边搞混了。",
  "estimated_duration_seconds": 60
}
```

| 字段 | 类型 | 说明 |
|------|------|------|
| `page_num` | int | 页码 |
| `total_pages` | int | 总页数 |
| `narration_text` | string | 口语化讲解词，纯文本 |
| `estimated_duration_seconds` | int | 该页预计朗读时长（秒），按语速 200-250 字/分钟估算 |

#### done — 全部完成

```json
{
  "prep_id": 0,
  "total_pages": 11,
  "total_duration_seconds": 664,
  "errors": []
}
```

| 字段 | 类型 | 说明 |
|------|------|------|
| `prep_id` | int | 备课 ID（当前固定为 0，由调用方写入数据库后分配） |
| `total_pages` | int | 总页数 |
| `total_duration_seconds` | int | 总讲解时长（秒） |
| `errors` | string[] | 错误列表，空数组表示无错误 |

#### error — 错误

```json
{
  "stage": "all",
  "message": "教学大纲生成失败"
}
```

| 字段 | 类型 | 说明 |
|------|------|------|
| `stage` | string | 错误阶段：`1`(大纲), `2`(PPT), `3`(讲解词), `all` |
| `message` | string | 错误描述 |

---

## 调用示例

### curl

```bash
# 1. 登录
SID=$(curl -s -X POST http://localhost:8000/api/auth/login \
  -d "username=admin&password=admin123" \
  | python -c "import sys,json; print(json.load(sys.stdin)['session_id'])")

# 2. 备课生成
curl -N -X POST http://localhost:8000/api/lesson-prep/generate \
  -H "Content-Type: application/json" \
  -H "Cookie: session_id=${SID}" \
  -d '{
    "user_id": 1,
    "title": "勾股定理",
    "subject": "math",
    "grade": "grade_8",
    "knowledge_point_names": ["勾股定理", "勾股定理的证明"],
    "teaching_goals": ["理解勾股定理", "掌握证明方法", "能运用解题"],
    "total_hours": 2,
    "style": "standard",
    "weak_points": "学生容易混淆斜边和直角边"
  }'
```

### JavaScript（浏览器 EventSource 不适用，需用 fetch + ReadableStream）

```javascript
const response = await fetch('http://localhost:8000/api/lesson-prep/generate', {
  method: 'POST',
  headers: {
    'Content-Type': 'application/json',
    'Cookie': 'session_id=xxx'
  },
  body: JSON.stringify({
    user_id: 1,
    title: '勾股定理',
    subject: 'math',
    grade: 'grade_8',
    knowledge_point_names: ['勾股定理', '勾股定理的证明'],
    teaching_goals: ['理解勾股定理', '掌握证明方法', '能运用解题'],
    total_hours: 2,
    style: 'standard',
    weak_points: '学生容易混淆斜边和直角边'
  })
});

const reader = response.body.getReader();
const decoder = new TextDecoder();

while (true) {
  const { done, value } = await reader.read();
  if (done) break;
  
  const text = decoder.decode(value);
  // 按 "event:\n" 和 "data:\n" 分割处理
  const lines = text.split('\n');
  for (const line of lines) {
    if (line.startsWith('event: ')) {
      currentEvent = line.slice(7);
    } else if (line.startsWith('data: ')) {
      const data = JSON.parse(line.slice(6));
      handleEvent(currentEvent, data);
    }
  }
}

function handleEvent(event, data) {
  switch (event) {
    case 'syllabus_chunk':
      // 逐 token 追加到内容区
      break;
    case 'syllabus_done':
      // 教学大纲生成完成
      break;
    case 'slide':
      // 收到一页 PPT
      break;
    case 'narration':
      // 收到一页讲解词
      break;
    case 'done':
      // 全部完成
      break;
    case 'error':
      // 错误处理
      break;
  }
}
```

---

## 错误码

| HTTP 状态码 | 说明 |
|-------------|------|
| 200 | SSE 流式正常响应 |
| 401 | 未认证/认证失败 |
| 422 | 请求参数校验失败 |
| 500 | 服务器内部错误 |

---

## SSE 协议说明

响应格式为标准 `text/event-stream`：

```
event: syllabus_chunk
data: {"chunk":"勾"}

event: slide
data: {"page_num":1,"type":"cover",...}

event: done
data: {...}
```

- 每个 event 由 `event:` 行 + `data:` 行组成，空行分隔
- 客户端按 `event` 字段区分事件类型
- 前端应使用 `ReadableStream` 或 SSE 库解析
