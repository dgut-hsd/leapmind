export const API_BASE =
  process.env.NEXT_PUBLIC_API_BASE_URL ?? "http://localhost:8000";

export type JobStatus =
  | "queued"
  | "generating_image"
  | "generating_3d"
  | "processing"
  | "completed"
  | "failed";

export type Job = {
  id: string;
  kind: string;
  status: JobStatus;
  progress: number;
  result: Record<string, unknown>;
  error: string | null;
};

export type Model = {
  id: string;
  job_id: string;
  provider: string;
  status: JobStatus;
  has_raw_model: boolean;
  has_processed_model: boolean;
  raw_download_url: string | null;
  processed_download_url: string | null;
  preview_url: string | null;
  error: string | null;
};

export async function api<T>(
  path: string,
  init?: RequestInit,
): Promise<T> {
  const response = await fetch(`${API_BASE}${path}`, init);
  if (!response.ok) {
    let message = `${response.status} ${response.statusText}`;
    try {
      const body = await response.json();
      message = body.detail ?? body.message ?? message;
    } catch {
      // Keep the HTTP status text.
    }
    throw new Error(message);
  }
  return response.json() as Promise<T>;
}

export async function waitForJob(
  jobId: string,
  onProgress: (job: Job) => void,
  signal?: AbortSignal,
): Promise<Job> {
  for (;;) {
    if (signal?.aborted) throw new DOMException("Aborted", "AbortError");
    const job = await api<Job>(`/api/jobs/${jobId}`, { signal });
    onProgress(job);
    if (job.status === "completed") return job;
    if (job.status === "failed") {
      throw new Error(job.error ?? "任务失败");
    }
    await new Promise((resolve) => setTimeout(resolve, 1800));
  }
}

