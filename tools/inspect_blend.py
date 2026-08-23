import json
import math
from pathlib import Path

import bpy
from mathutils import Vector


OUT_DIR = Path(r"E:\goal\rust1\leapmind\leapmind\blend_inspect")
OUT_DIR.mkdir(parents=True, exist_ok=True)


def bounds_for(objects):
    coords = []
    for obj in objects:
        if obj.type != "MESH":
            continue
        coords.extend(obj.matrix_world @ Vector(corner) for corner in obj.bound_box)
    if not coords:
        return None
    low = Vector((min(v.x for v in coords), min(v.y for v in coords), min(v.z for v in coords)))
    high = Vector((max(v.x for v in coords), max(v.y for v in coords), max(v.z for v in coords)))
    return {"min": list(low), "max": list(high), "center": list((low + high) / 2), "size": list(high - low)}


mesh_objects = [obj for obj in bpy.context.scene.objects if obj.type == "MESH"]
armatures = [obj for obj in bpy.context.scene.objects if obj.type == "ARMATURE"]
materials = sorted({slot.material.name for obj in mesh_objects for slot in obj.material_slots if slot.material})

summary = {
    "file": bpy.data.filepath,
    "object_count": len(bpy.context.scene.objects),
    "mesh_count": len(mesh_objects),
    "armature_count": len(armatures),
    "materials": materials,
    "bounds": bounds_for(mesh_objects),
    "meshes": [
        {
            "name": obj.name,
            "vertices": len(obj.data.vertices),
            "polygons": len(obj.data.polygons),
            "materials": [slot.material.name if slot.material else None for slot in obj.material_slots],
            "modifiers": [mod.type for mod in obj.modifiers],
        }
        for obj in sorted(mesh_objects, key=lambda o: o.name)
    ],
    "armatures": [
        {
            "name": obj.name,
            "bones": len(obj.data.bones),
            "bone_names": [bone.name for bone in obj.data.bones[:80]],
        }
        for obj in armatures
    ],
}

(OUT_DIR / "summary.json").write_text(json.dumps(summary, ensure_ascii=False, indent=2), encoding="utf-8")

if mesh_objects:
    bpy.ops.object.select_all(action="DESELECT")
    for obj in mesh_objects:
        obj.select_set(True)
    bpy.context.view_layer.objects.active = mesh_objects[0]

    center = Vector(summary["bounds"]["center"])
    size = Vector(summary["bounds"]["size"])
    radius = max(size.length, 1.0)

    cam = bpy.data.objects.get("Codex_Inspect_Camera") or bpy.data.objects.new(
        "Codex_Inspect_Camera", bpy.data.cameras.new("Codex_Inspect_Camera")
    )
    if cam.name not in bpy.context.collection.objects:
        bpy.context.collection.objects.link(cam)
    cam.location = center + Vector((0, -radius * 1.55, radius * 0.35))
    direction = center - cam.location
    cam.rotation_euler = direction.to_track_quat("-Z", "Y").to_euler()
    cam.data.lens = 65
    bpy.context.scene.camera = cam

    light = bpy.data.objects.get("Codex_Inspect_Area") or bpy.data.objects.new(
        "Codex_Inspect_Area", bpy.data.lights.new("Codex_Inspect_Area", "AREA")
    )
    if light.name not in bpy.context.collection.objects:
        bpy.context.collection.objects.link(light)
    light.location = center + Vector((0, -radius * 0.8, radius * 1.1))
    light.data.energy = 450
    light.data.size = radius * 0.8

    bpy.context.scene.render.engine = "BLENDER_EEVEE"
    if hasattr(bpy.context.scene, "eevee"):
        bpy.context.scene.eevee.taa_render_samples = 64
    bpy.context.scene.render.resolution_x = 1400
    bpy.context.scene.render.resolution_y = 1800
    bpy.context.scene.render.film_transparent = False
    bpy.context.scene.world.color = (1, 1, 1)
    bpy.context.scene.render.filepath = str(OUT_DIR / "preview.png")
    bpy.ops.render.render(write_still=True)
