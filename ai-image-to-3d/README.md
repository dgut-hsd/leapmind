# AI Image to 3D

一个能够从文字或 PNG/JPG/WebP 图片生成 GLB、异步查询进度、调用 Blender
清理并在线预览的最小可用全栈项目。

## 功能

- 文字模式通过 OpenAI Image API 生成正面、左侧、背面或 45° 参考图。
- 图片模式验证真实格式、文件大小和尺寸，并执行 EXIF 修正、去背、裁剪、
  居中和 1024×1024 归一化。
- `ThreeDProvider` 统一 Meshy、Mock 以及未来 Tripo/Hunyuan 的接口。
- Celery + Redis 异步执行图片生成、3D 轮询、下载和 Blender 清理。
- SQLite 开发数据库；SQLAlchemy 结构可直接改为 PostgreSQL URL。
- React Three Fiber 在线预览 GLB，并下载原始/处理后模型。

## 目录

```text
backend/
  app/
    providers/image/openai_provider.py
    providers/three_d/{base,mock_provider,meshy_provider}.py
    services/{image_preprocessor,model_pipeline}.py
    workers/
  blender/cleanup_model.py
  tests/
frontend/
docs/TECHNICAL_DESIGN.md
docker-compose.yml
```

## 配置

复制环境变量模板：

```powershell
Copy-Item .env.example .env
```

至少配置一个真实供应商：

```dotenv
OPENAI_API_KEY=...
MESHY_API_KEY=...
THREE_D_PROVIDER=meshy
```

所有密钥只从环境变量读取，`.env` 已加入 `.gitignore`。不要把真实密钥写入
源码、Dockerfile、Compose 或前端的 `NEXT_PUBLIC_*` 变量。

### OpenAI 字段说明

默认使用：

```dotenv
OPENAI_IMAGE_MODEL=gpt-image-2
OPENAI_IMAGE_SIZE=1024x1024
OPENAI_IMAGE_QUALITY=medium
```

当前 `gpt-image-2` 不支持透明背景，Provider 因此请求纯白背景，再由本地
`rembg` 去背。若组织尚未获得对应模型权限，需在 OpenAI 平台完成必要配置，
或将模型名改为账户可用的 GPT Image 模型。

### Meshy 字段说明

当前实现依据 Meshy Image-to-3D v1 API：

```text
POST   /openapi/v1/image-to-3d
GET    /openapi/v1/image-to-3d/{id}
DELETE /openapi/v1/image-to-3d/{id}
```

请求使用 base64 data URI，主要字段为 `should_remesh`、
`target_polycount`、`should_texture`、`enable_pbr`、`pose_mode` 和
`target_formats: ["glb"]`。供应商可能更新字段或计费规则；上线前请对照
[Meshy Image-to-3D 官方文档](https://docs.meshy.ai/en/api/image-to-3d)。

Meshy 的单图 `/image-to-3d` 端点只提交第一张（通常为正面）参考图。项目会
保存全部视图并通过统一 Provider 接口传入；若需要 Meshy Multi Image API，
仅需在 `MeshyProvider` 内切换端点，无需改动 API、任务或前端。

## Docker Compose 运行

本机需安装 Docker Desktop：

```powershell
docker compose up --build
```

打开：

- 前端：http://localhost:3000
- API 文档：http://localhost:8000/docs
- 健康检查：http://localhost:8000/health

Compose 的后端镜像包含 Blender 和 `rembg[cpu]`。首次构建较慢。

## 本地开发

### 后端

Python 3.11：

```powershell
cd backend
python -m venv .venv
.\.venv\Scripts\Activate.ps1
python -m pip install -e ".[dev,background]"
$env:CELERY_TASK_ALWAYS_EAGER="true"
$env:REMOVE_BACKGROUND="false"
uvicorn app.main:app --reload
```

不安装 `background` extra 时，需设置 `REMOVE_BACKGROUND=false`。

运行测试：

```powershell
.\.venv\Scripts\python.exe -m pytest
```

### 前端

Node.js 22：

```powershell
cd frontend
npm install
npm run dev
```

测试与构建：

```powershell
npm test
npm run build
```

## Blender 本地运行

确保 `blender` 在 PATH，或者配置完整路径：

```dotenv
BLENDER_EXECUTABLE=C:\Program Files\Blender Foundation\Blender 4.3\blender.exe
```

手工验证：

```powershell
blender --background --python backend/blender/cleanup_model.py -- `
  --input data/models/example/raw.glb `
  --output data/models/example/processed.glb `
  --decimate-face-count 50000
```

## API 流程

1. `POST /api/images/generate` 或 `POST /api/images/upload`
2. `POST /api/models/generate`
3. 轮询 `GET /api/jobs/{job_id}`
4. 读取 `GET /api/models/{model_id}`
5. 可选 `POST /api/models/{model_id}/process`
6. `GET /api/models/{model_id}/download?variant=raw|processed`

状态包括：

```text
queued
generating_image
generating_3d
processing
completed
failed
```

## 当前人工配置项

- OpenAI 与 Meshy API 密钥及账户余额/权限。
- 生产环境建议改用 PostgreSQL 和对象存储。
- 真实供应商调用会产生费用，自动化测试默认使用 MockProvider。
- 本地 Blender 必须在 PATH 或通过 `BLENDER_EXECUTABLE` 指定。
- 若需要供应商原生多视图，请根据最新文档扩展 MeshyProvider 或新增
  TripoProvider/HunyuanProvider。

