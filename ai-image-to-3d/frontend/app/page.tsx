"use client";

import dynamic from "next/dynamic";
import { FormEvent, useMemo, useRef, useState } from "react";
import { api, Job, JobStatus, Model, waitForJob } from "@/lib/api";
import { STATUS_LABELS, statusTone } from "@/lib/status";

const ModelViewer = dynamic(() => import("@/components/ModelViewer"), {
  ssr: false,
  loading: () => (
    <div className="flex h-[460px] items-center justify-center border-2 border-ink bg-[#ddd8cb]">
      正在加载 3D 查看器…
    </div>
  ),
});

const VIEW_OPTIONS = [
  ["front", "正面"],
  ["left", "左侧"],
  ["back", "背面"],
  ["three-quarter", "45°"],
] as const;

export default function Home() {
  const [mode, setMode] = useState<"text" | "image">("text");
  const [prompt, setPrompt] = useState("");
  const [files, setFiles] = useState<File[]>([]);
  const [views, setViews] = useState<string[]>(["front"]);
  const [provider, setProvider] = useState("mock");
  const [status, setStatus] = useState<JobStatus>("queued");
  const [progress, setProgress] = useState(0);
  const [message, setMessage] = useState("等待输入");
  const [model, setModel] = useState<Model | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const abortRef = useRef<AbortController | null>(null);

  const selectedViewLabels = useMemo(
    () =>
      VIEW_OPTIONS.filter(([value]) => views.includes(value))
        .map(([, label]) => label)
        .join(" / "),
    [views],
  );

  function onJob(job: Job) {
    setStatus(job.status);
    setProgress(job.progress);
    setMessage(STATUS_LABELS[job.status]);
  }

  async function create3D(imageIds: string[], signal: AbortSignal) {
    const created = await api<Model>("/api/models/generate", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({
        image_ids: imageIds,
        provider,
        pose_mode: "a-pose",
        target_polycount: 50000,
        should_texture: true,
        enable_pbr: true,
      }),
      signal,
    });
    setModel(created);
    await waitForJob(created.job_id, onJob, signal);
    const completed = await api<Model>(`/api/models/${created.id}`, { signal });
    setModel(completed);
  }

  async function handleSubmit(event: FormEvent) {
    event.preventDefault();
    setError(null);
    setModel(null);
    setBusy(true);
    setStatus("queued");
    setProgress(0);
    const controller = new AbortController();
    abortRef.current = controller;
    try {
      let imageIds: string[];
      if (mode === "text") {
        if (prompt.trim().length < 3) throw new Error("请输入更完整的主体描述");
        const imageJob = await api<Job>("/api/images/generate", {
          method: "POST",
          headers: { "Content-Type": "application/json" },
          body: JSON.stringify({ prompt, views }),
          signal: controller.signal,
        });
        const completed = await waitForJob(
          imageJob.id,
          onJob,
          controller.signal,
        );
        imageIds = completed.result.image_ids as string[];
      } else {
        if (!files.length) throw new Error("请选择至少一张图片");
        if (files.length > 1 && views.length !== files.length) {
          throw new Error("上传多张图片时，视图数量必须与图片数量一致");
        }
        const form = new FormData();
        files.forEach((file) => form.append("files", file));
        const uploaded = await api<Array<{ id: string }>>(
          `/api/images/upload?views=${encodeURIComponent(views.join(","))}`,
          { method: "POST", body: form, signal: controller.signal },
        );
        imageIds = uploaded.map((image) => image.id);
      }
      await create3D(imageIds, controller.signal);
    } catch (caught) {
      if ((caught as Error).name !== "AbortError") {
        setError((caught as Error).message);
        setStatus("failed");
      }
    } finally {
      setBusy(false);
    }
  }

  async function processModel() {
    if (!model) return;
    setBusy(true);
    setError(null);
    try {
      const job = await api<Job>(`/api/models/${model.id}/process`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({
          decimate_face_count: 50000,
          triangulate: false,
          min_fragment_ratio: 0.001,
        }),
      });
      await waitForJob(job.id, onJob);
      setModel(await api<Model>(`/api/models/${model.id}`));
    } catch (caught) {
      setError((caught as Error).message);
    } finally {
      setBusy(false);
    }
  }

  return (
    <main className="mx-auto min-h-screen max-w-[1500px] p-5 md:p-8">
      <header className="mb-8 flex flex-col justify-between gap-4 border-b-4 border-ink pb-6 md:flex-row md:items-end">
        <div>
          <div className="mb-2 inline-block border-2 border-ink bg-acid px-3 py-1 text-xs font-black uppercase tracking-[0.2em]">
            Image → Geometry
          </div>
          <h1 className="text-4xl font-black uppercase leading-none tracking-[-0.06em] md:text-7xl">
            FormFoundry
          </h1>
        </div>
        <p className="max-w-md text-sm font-bold leading-relaxed">
          从一句描述或四视图开始，生成、清理并在线检查 GLB。
          供应商接口与本地模型处理保持解耦。
        </p>
      </header>

      <div className="grid gap-7 lg:grid-cols-[440px_1fr]">
        <form
          onSubmit={handleSubmit}
          className="h-fit border-2 border-ink bg-paper p-5 shadow-hard"
        >
          <div className="mb-5 grid grid-cols-2 border-2 border-ink">
            {(["text", "image"] as const).map((item) => (
              <button
                type="button"
                key={item}
                onClick={() => setMode(item)}
                className={`px-4 py-3 text-sm font-black uppercase ${
                  mode === item ? "bg-ink text-white" : "bg-paper"
                }`}
              >
                {item === "text" ? "文字生成" : "上传图片"}
              </button>
            ))}
          </div>

          {mode === "text" ? (
            <label className="block">
              <span className="mb-2 block text-xs font-black uppercase tracking-widest">
                主体描述
              </span>
              <textarea
                value={prompt}
                onChange={(event) => setPrompt(event.target.value)}
                rows={7}
                maxLength={2000}
                placeholder="例如：一个原创的迷你型大学生 AI 助教，紫色短发，青紫披肩，A-pose…"
                className="w-full resize-none border-2 border-ink bg-white p-3 outline-none focus:ring-4 focus:ring-cobalt/30"
              />
            </label>
          ) : (
            <label className="block">
              <span className="mb-2 block text-xs font-black uppercase tracking-widest">
                PNG / JPG / WebP，最多 4 张
              </span>
              <input
                type="file"
                accept=".png,.jpg,.jpeg,.webp,image/png,image/jpeg,image/webp"
                multiple
                onChange={(event) =>
                  setFiles(Array.from(event.target.files ?? []).slice(0, 4))
                }
                className="w-full border-2 border-dashed border-ink bg-white p-5 text-sm"
              />
              <div className="mt-2 text-xs font-bold">
                已选择：{files.map((file) => file.name).join("、") || "无"}
              </div>
            </label>
          )}

          <fieldset className="mt-5">
            <legend className="mb-2 text-xs font-black uppercase tracking-widest">
              参考视图 · {selectedViewLabels}
            </legend>
            <div className="grid grid-cols-2 gap-2">
              {VIEW_OPTIONS.map(([value, label]) => (
                <label
                  key={value}
                  className={`cursor-pointer border-2 border-ink px-3 py-2 text-sm font-bold ${
                    views.includes(value) ? "bg-acid" : "bg-white"
                  }`}
                >
                  <input
                    type="checkbox"
                    checked={views.includes(value)}
                    onChange={() =>
                      setViews((current) =>
                        current.includes(value)
                          ? current.length > 1
                            ? current.filter((item) => item !== value)
                            : current
                          : [...current, value],
                      )
                    }
                    className="mr-2"
                  />
                  {label}
                </label>
              ))}
            </div>
          </fieldset>

          <label className="mt-5 block">
            <span className="mb-2 block text-xs font-black uppercase tracking-widest">
              3D Provider
            </span>
            <select
              value={provider}
              onChange={(event) => setProvider(event.target.value)}
              className="w-full border-2 border-ink bg-white p-3 font-bold"
            >
              <option value="mock">Mock（本地测试）</option>
              <option value="meshy">Meshy</option>
            </select>
          </label>

          <button
            type="submit"
            disabled={busy}
            className="mt-6 w-full border-2 border-ink bg-cobalt px-5 py-4 text-lg font-black uppercase text-white transition hover:-translate-y-1 hover:shadow-hard disabled:cursor-not-allowed disabled:opacity-50"
          >
            {busy ? "正在铸造…" : "生成 3D"}
          </button>

          {busy && (
            <button
              type="button"
              onClick={() => abortRef.current?.abort()}
              className="mt-3 w-full text-xs font-black uppercase underline"
            >
              取消本次等待
            </button>
          )}
        </form>

        <section className="min-w-0">
          <div className="mb-5 border-2 border-ink bg-white p-4">
            <div className="flex items-center justify-between gap-4">
              <span
                className={`border-2 border-ink px-3 py-1 text-xs font-black uppercase ${statusTone(status)}`}
              >
                {STATUS_LABELS[status]}
              </span>
              <span className="font-mono text-sm font-bold">
                {Math.round(progress)}%
              </span>
            </div>
            <div className="mt-3 h-3 border-2 border-ink bg-paper">
              <div
                className="h-full bg-cobalt transition-all"
                style={{ width: `${progress}%` }}
              />
            </div>
            <p className="mb-0 mt-2 text-sm font-bold">{message}</p>
            {error && (
              <p className="mt-3 border-2 border-red-700 bg-red-100 p-3 text-sm font-bold text-red-900">
                {error}
              </p>
            )}
          </div>

          {model?.preview_url ? (
            <>
              <ModelViewer
                key={model.preview_url}
                url={model.preview_url}
              />
              <div className="mt-4 flex flex-wrap gap-3">
                {model.raw_download_url && (
                  <a
                    href={model.raw_download_url}
                    className="border-2 border-ink bg-white px-4 py-3 text-sm font-black uppercase"
                  >
                    下载原始 GLB
                  </a>
                )}
                {model.processed_download_url && (
                  <a
                    href={model.processed_download_url}
                    className="border-2 border-ink bg-acid px-4 py-3 text-sm font-black uppercase"
                  >
                    下载处理后 GLB
                  </a>
                )}
                {model.has_raw_model && !model.has_processed_model && (
                  <button
                    type="button"
                    disabled={busy}
                    onClick={processModel}
                    className="border-2 border-ink bg-ink px-4 py-3 text-sm font-black uppercase text-white"
                  >
                    Blender 清理
                  </button>
                )}
              </div>
            </>
          ) : (
            <div className="flex h-[460px] flex-col items-center justify-center border-2 border-ink bg-[#ddd8cb] p-10 text-center">
              <div className="mb-4 text-7xl font-black text-cobalt">3D</div>
              <p className="max-w-sm font-bold">
                模型完成后会在这里加载。拖动旋转，滚轮缩放，下载原始或清理后的 GLB。
              </p>
            </div>
          )}
        </section>
      </div>
    </main>
  );
}
