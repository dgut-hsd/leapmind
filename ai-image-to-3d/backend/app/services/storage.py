from __future__ import annotations

import re
from pathlib import Path


SAFE_NAME = re.compile(r"[^A-Za-z0-9._-]+")


def safe_filename(name: str, fallback: str = "file") -> str:
    cleaned = SAFE_NAME.sub("_", Path(name).name).strip("._")
    return cleaned or fallback


def ensure_within(base: Path, candidate: Path) -> Path:
    base = base.resolve()
    candidate = candidate.resolve()
    if base != candidate and base not in candidate.parents:
        raise ValueError("Resolved path escapes the storage directory")
    return candidate

