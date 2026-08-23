import time

import bpy


bpy.ops.wm.read_factory_settings(use_empty=True)
try:
    bpy.ops.preferences.addon_enable(module="blender_ai_mcp")
    print("[Codex] blender_ai_mcp addon enabled")
except Exception as exc:
    print(f"[Codex] failed to enable blender_ai_mcp addon: {exc}")
print("[Codex] Blender RPC keepalive scene ready")

while True:
    time.sleep(1)
