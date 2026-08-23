from pathlib import Path

import bpy


OUT_DIR = Path(r"E:\goal\rust1\leapmind\leapmind\blend_inspect\textures")
OUT_DIR.mkdir(parents=True, exist_ok=True)

for image in bpy.data.images:
    if image.size[0] == 0 or image.size[1] == 0:
        continue
    image.filepath_raw = str(OUT_DIR / f"{image.name}.png")
    image.file_format = "PNG"
    image.save()
