import { describe, expect, it } from "vitest";
import { STATUS_LABELS, statusTone } from "./status";

describe("job status presentation", () => {
  it("defines all backend states", () => {
    expect(Object.keys(STATUS_LABELS)).toEqual([
      "queued",
      "generating_image",
      "generating_3d",
      "processing",
      "completed",
      "failed",
    ]);
  });

  it("marks failures distinctly", () => {
    expect(statusTone("failed")).toContain("red");
  });
});

