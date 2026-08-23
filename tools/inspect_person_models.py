import json
from pathlib import Path

import bpy
from mathutils import Vector


SRC_DIR = Path(r"E:\人物一")
OUT_DIR = Path(r"E:\goal\rust1\leapmind\leapmind\person_model_inspect")
OUT_DIR.mkdir(parents=True, exist_ok=True)

MODEL_FILES = [
    SRC_DIR / "头.glb",
    SRC_DIR / "头发.glb",
    SRC_DIR / "身体.glb",
    SRC_DIR / "皮风.glb",
    SRC_DIR / "鞋子.glb",
    SRC_DIR / "6b5ff5a6255b6f8ea44f2758dc4205f4.glb",
    SRC_DIR / "AvatarSample_O.vrm",
]


def clear_scene():
    bpy.ops.object.select_all(action="SELECT")
    bpy.ops.object.delete()
    for block in list(bpy.data.meshes):
        if block.users == 0:
            bpy.data.meshes.remove(block)
    for block in list(bpy.data.materials):
        if block.users == 0:
            bpy.data.materials.remove(block)
    for block in list(bpy.data.images):
        if block.users == 0:
            bpy.data.images.remove(block)


def import_model(path: Path):
    if path.suffix.lower() == ".vrm":
        temp = OUT_DIR / f"{path.stem}_as_glb.glb"
        temp.write_bytes(path.read_bytes())
        path = temp
    bpy.ops.import_scene.gltf(filepath=str(path))


def bounds(objects):
    coords = []
    for obj in objects:
        if obj.type == "MESH":
            coords.extend(obj.matrix_world @ Vector(corner) for corner in obj.bound_box)
    if not coords:
        return None
    low = Vector((min(v.x for v in coords), min(v.y for v in coords), min(v.z for v in coords)))
    high = Vector((max(v.x for v in coords), max(v.y for v in coords), max(v.z for v in coords)))
    return low, high, (low + high) / 2, high - low


def look_at(obj, target):
    direction = Vector(target) - obj.location
    obj.rotation_euler = direction.to_track_quat("-Z", "Y").to_euler()


def render_preview(path: Path, b):
    low, high, center, size = b
    radius = max(size.length, 1.0)
    cam_data = bpy.data.cameras.new("inspect_camera")
    cam = bpy.data.objects.new("inspect_camera", cam_data)
    bpy.context.collection.objects.link(cam)
    cam.location = center + Vector((0, -radius * 1.8, radius * 0.25))
    look_at(cam, center)
    cam.data.type = "ORTHO"
    cam.data.ortho_scale = max(size.x, size.z) * 1.2
    bpy.context.scene.camera = cam

    light_data = bpy.data.lights.new("inspect_key", "AREA")
    light = bpy.data.objects.new("inspect_key", light_data)
    bpy.context.collection.objects.link(light)
    light.location = center + Vector((0, -radius, radius))
    light.data.energy = 500
    light.data.size = radius

    bpy.context.scene.render.engine = "BLENDER_EEVEE"
    bpy.context.scene.world.color = (0.78, 0.78, 0.78)
    bpy.context.scene.render.resolution_x = 900
    bpy.context.scene.render.resolution_y = 1200
    bpy.context.scene.render.filepath = str(path)
    bpy.ops.render.render(write_still=True)


summary = []
for path in MODEL_FILES:
    if not path.exists():
        continue
    clear_scene()
    status = {"file": str(path), "name": path.name}
    try:
        import_model(path)
        objects = list(bpy.context.scene.objects)
        mesh_objects = [obj for obj in objects if obj.type == "MESH"]
        armatures = [obj for obj in objects if obj.type == "ARMATURE"]
        b = bounds(mesh_objects)
        status.update(
            {
                "object_count": len(objects),
                "mesh_count": len(mesh_objects),
                "armature_count": len(armatures),
                "mesh_names": [obj.name for obj in mesh_objects[:40]],
                "armature_names": [obj.name for obj in armatures[:20]],
                "materials": sorted({slot.material.name for obj in mesh_objects for slot in obj.material_slots if slot.material})[:80],
            }
        )
        if b:
            low, high, center, size = b
            status["bounds"] = {
                "min": [low.x, low.y, low.z],
                "max": [high.x, high.y, high.z],
                "center": [center.x, center.y, center.z],
                "size": [size.x, size.y, size.z],
            }
            preview = OUT_DIR / f"{path.stem}_preview.png"
            render_preview(preview, b)
            status["preview"] = str(preview)
    except Exception as exc:
        status["error"] = str(exc)
    summary.append(status)

(OUT_DIR / "summary.json").write_text(json.dumps(summary, ensure_ascii=False, indent=2), encoding="utf-8")
