from pathlib import Path

import bpy


OUT_DIR = Path(r"E:\goal\rust1\leapmind\leapmind\blend_inspect")
OUT_DIR.mkdir(parents=True, exist_ok=True)

camera = bpy.data.objects.get("Codex Camera - refined front")
if camera:
    bpy.context.scene.camera = camera

bpy.context.scene.render.resolution_x = 1400
bpy.context.scene.render.resolution_y = 1800
bpy.context.scene.render.filepath = str(OUT_DIR / "refined_front.png")
bpy.ops.render.render(write_still=True)
