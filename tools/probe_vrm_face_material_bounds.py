import json
import shutil
from pathlib import Path

import bpy
from mathutils import Vector


SRC_DIR = Path(r"E:\人物一")
OUT_DIR = Path(r"E:\goal\rust1\leapmind\leapmind\person_model_inspect")
OUT_DIR.mkdir(parents=True, exist_ok=True)

temp = OUT_DIR / "_tmp_probe_vrm.glb"
shutil.copyfile(SRC_DIR / "AvatarSample_O.vrm", temp)

bpy.ops.object.select_all(action="SELECT")
bpy.ops.object.delete()
bpy.ops.import_scene.gltf(filepath=str(temp))

face = bpy.data.objects.get("Face") or next((obj for obj in bpy.context.scene.objects if obj.type == "MESH" and "face" in obj.name.lower()), None)
if face is None:
    raise RuntimeError("Face mesh not found")

data = {"face_name": face.name, "materials": []}
mesh = face.data
for index, slot in enumerate(face.material_slots):
    mat_name = slot.material.name if slot.material else None
    coords = []
    for poly in mesh.polygons:
        if poly.material_index != index:
            continue
        for vi in poly.vertices:
            coords.append(face.matrix_world @ mesh.vertices[vi].co)
    item = {"index": index, "material": mat_name, "vertex_count": len(coords)}
    if coords:
        low = Vector((min(v.x for v in coords), min(v.y for v in coords), min(v.z for v in coords)))
        high = Vector((max(v.x for v in coords), max(v.y for v in coords), max(v.z for v in coords)))
        item["bounds"] = {
            "min": [low.x, low.y, low.z],
            "max": [high.x, high.y, high.z],
            "center": [((low + high) / 2).x, ((low + high) / 2).y, ((low + high) / 2).z],
            "size": [(high - low).x, (high - low).y, (high - low).z],
        }
    data["materials"].append(item)

(OUT_DIR / "vrm_face_material_bounds.json").write_text(json.dumps(data, ensure_ascii=False, indent=2), encoding="utf-8")
