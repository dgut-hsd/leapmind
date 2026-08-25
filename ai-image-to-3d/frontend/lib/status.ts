import type { JobStatus } from "./api";

export const STATUS_LABELS: Record<JobStatus, string> = {
  queued: "等待队列",
  generating_image: "生成参考图",
  generating_3d: "重建 3D",
  processing: "Blender 清理",
  completed: "已完成",
  failed: "失败",
};

export function statusTone(status: JobStatus): string {
  if (status === "failed") return "bg-red-500 text-white";
  if (status === "completed") return "bg-acid text-ink";
  return "bg-cobalt text-white";
}

