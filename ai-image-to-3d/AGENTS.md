# AGENTS.md

## Architecture

- `frontend/`: Next.js App Router, TypeScript, Tailwind and React Three Fiber.
- `backend/app/api`: thin HTTP routes; business logic belongs in services.
- `backend/app/providers`: all external vendor request/response fields.
- `backend/app/services`: provider-neutral image and model workflows.
- `backend/app/workers`: Celery task entry points and persisted status updates.
- `backend/blender`: scripts that must also run under Blender's bundled Python.

## Rules

- Never commit API keys, signed provider URLs or `.env`.
- API keys must be read from `Settings`.
- Add vendor fields only inside the corresponding Provider.
- Persist failures in both the public Job and relevant ModelAsset.
- Validate file content, not only extension or MIME header.
- Keep paths under `DATA_DIR` and use generated UUID filenames.
- Do not make real paid provider calls in automated tests.
- New job states require state-transition tests and frontend labels.
- Keep SQLite development support and avoid PostgreSQL-incompatible SQL.

## Commands

Backend:

```powershell
cd backend
.\.venv\Scripts\python.exe -m pytest
```

Frontend:

```powershell
cd frontend
npm test
npm run build
```

Docker:

```powershell
docker compose config
docker compose build
```

## Required verification

- Provider contract tests.
- Job transition tests.
- Upload format/size tests.
- Mock end-to-end API flow.
- Frontend status mapping test.
- TypeScript production build.
- Docker Compose config/build when Docker is available.

