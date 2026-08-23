# AI Image to 3D - Technical Design

## Scope

`ai-image-to-3d` is a minimal full-stack pipeline that accepts text or image
inputs, prepares single-subject reference images, submits them to a 3D
provider, persists progress, optionally cleans the resulting GLB with Blender,
and exposes raw and processed downloads.

## Architecture

```text
Next.js UI
   |
   v
FastAPI API ---- SQLite/PostgreSQL-compatible SQLAlchemy models
   |
   +---- OpenAIImageProvider ---- OpenAI Image API
   |
   +---- ModelPipeline ---- ThreeDProvider
   |                         +-- MockProvider
   |                         +-- MeshyProvider
   |                         +-- future Tripo/Hunyuan
   |
   +---- Celery workers ---- Redis
                              |
                              +-- Blender headless cleanup
```

## State machine

```text
queued
  +-- text input --> generating_image --> generating_3d
  +-- image input ---------------------> generating_3d
generating_3d --> processing --> completed
any active state --> failed
```

`processing` is optional: a raw GLB can be completed before a separate cleanup
job is requested.

## Storage model

- `jobs`: public job state, progress, error and result JSON.
- `image_assets`: original/preprocessed/generated reference images and view.
- `model_assets`: provider task id, raw GLB, processed GLB, texture directory,
  provider payload and error.
- Local development stores files below `DATA_DIR`; production can replace the
  storage service without changing providers or API schemas.

## Provider contract

`ThreeDProvider` owns all vendor-specific request and response fields:

- `create_task()`
- `get_task_status()`
- `download_result()`
- `cancel_task()`

The application only consumes normalized `ProviderTask` and
`DownloadedResult` values.

## External request policy

- API keys come only from environment variables.
- OpenAI and Meshy clients use explicit connect/read/write timeouts.
- Transient network errors, 429 responses and 5xx responses are retried with
  exponential backoff.
- Provider failures are normalized and persisted without logging secrets.
- Signed provider URLs are downloaded immediately because they can expire.

## Image policy

- Accepted uploads: PNG, JPEG and WebP.
- File content is decoded and checked independently of the filename.
- Files are size and dimension limited.
- EXIF orientation is applied.
- Background removal is optional and uses `rembg` when enabled.
- The subject is cropped to its alpha/content bounds, centered on a square
  canvas and normalized to a configured resolution.
- Text-to-image prompts force one full, centered, unobstructed subject and one
  of front/left/back/three-quarter views.

## OpenAI note

The default model is configured by `OPENAI_IMAGE_MODEL`. At implementation
time, the current Image API example uses `gpt-image-2`. That model does not
support transparent output, so the provider requests an opaque white
background and the local preprocessor performs optional background removal.

## Meshy note

The Meshy implementation targets `/openapi/v1/image-to-3d`, submits a base64
data URI, requests GLB output, polls the task object and downloads `model_urls`
and `texture_urls`. All Meshy fields are isolated in `MeshyProvider`; verify
them against current vendor documentation before production rollout.

## Blender processing

The worker invokes Blender as a subprocess:

```text
blender --background --python blender/cleanup_model.py -- \
  --input raw.glb --output processed.glb
```

The script deletes empty objects and small disconnected mesh fragments,
recalculates normals, applies transforms, grounds and centers the asset,
optionally decimates/triangulates, reports missing images and exports GLB.

## Deployment

Docker Compose runs:

- `api`
- `worker`
- `redis`
- `frontend`

SQLite is mounted into the shared data volume for development. Set
`DATABASE_URL` to a PostgreSQL SQLAlchemy URL for production.

