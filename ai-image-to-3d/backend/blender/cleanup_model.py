"""Clean an imported GLB and export an optimized GLB.

Run with:
    blender --background --python cleanup_model.py -- \
      --input raw.glb --output processed.glb
"""

from __future__ import annotations

import argparse
import json
import math
import os
import sys
from pathlib import Path

import bmesh
import bpy
from mathutils import Vector


def parse_args() -> argparse.Namespace:
    args = sys.argv[sys.argv.index("--") + 1 :] if "--" in sys.argv else []
    parser = argparse.ArgumentParser()
    parser.add_argument("--input", required=True)
    parser.add_argument("--output", required=True)
    parser.add_argument("--min-fragment-ratio", type=float, default=0.001)
    parser.add_argument("--decimate-face-count", type=int, default=0)
    parser.add_argument("--triangulate", action="store_true")
    return parser.parse_args(args)


def reset_scene() -> None:
    bpy.ops.object.select_all(action="SELECT")
    bpy.ops.object.delete(use_global=False)
    for collection in (
        bpy.data.meshes,
        bpy.data.materials,
        bpy.data.cameras,
        bpy.data.lights,
    ):
        for item in list(collection):
            if item.users == 0:
                collection.remove(item)


def import_glb(path: Path) -> None:
    if not path.is_file():
        raise FileNotFoundError(path)
    bpy.ops.import_scene.gltf(filepath=str(path))


def remove_empty_objects() -> int:
    removed = 0
    for obj in list(bpy.data.objects):
        if obj.type == "EMPTY" or (obj.type == "MESH" and len(obj.data.polygons) == 0):
            bpy.data.objects.remove(obj, do_unlink=True)
            removed += 1
    return removed


def remove_small_mesh_objects(ratio: float) -> int:
    meshes = [obj for obj in bpy.context.scene.objects if obj.type == "MESH"]
    if not meshes:
        return 0
    total_faces = sum(len(obj.data.polygons) for obj in meshes)
    threshold = max(2, math.ceil(total_faces * ratio))
    largest = max(meshes, key=lambda obj: len(obj.data.polygons))
    removed = 0
    for obj in list(meshes):
        if obj != largest and len(obj.data.polygons) < threshold:
            bpy.data.objects.remove(obj, do_unlink=True)
            removed += 1
    return removed


def remove_loose_fragments(obj: bpy.types.Object, ratio: float) -> int:
    mesh = obj.data
    bm = bmesh.new()
    bm.from_mesh(mesh)
    bm.verts.ensure_lookup_table()
    total_faces = len(bm.faces)
    if total_faces == 0:
        bm.free()
        return 0
    threshold = max(2, math.ceil(total_faces * ratio))
    unseen = set(bm.verts)
    components: list[tuple[set, set]] = []
    while unseen:
        root = unseen.pop()
        stack = [root]
        component = {root}
        while stack:
            vertex = stack.pop()
            for edge in vertex.link_edges:
                other = edge.other_vert(vertex)
                if other in unseen:
                    unseen.remove(other)
                    component.add(other)
                    stack.append(other)
        component_faces = {face for vert in component for face in vert.link_faces}
        components.append((component, component_faces))
    largest_component = max(components, key=lambda item: len(item[1]))
    removable = [
        item[0]
        for item in components
        if item is not largest_component and len(item[1]) < threshold
    ]
    remove_verts = set().union(*removable) if removable else set()
    removed_components = len(removable)
    if remove_verts:
        bmesh.ops.delete(bm, geom=list(remove_verts), context="VERTS")
        bm.to_mesh(mesh)
        mesh.update()
    bm.free()
    return removed_components


def apply_transforms_and_normals() -> None:
    for obj in [item for item in bpy.context.scene.objects if item.type == "MESH"]:
        bpy.context.view_layer.objects.active = obj
        obj.select_set(True)
        bpy.ops.object.transform_apply(location=False, rotation=True, scale=True)
        obj.select_set(False)
        bm = bmesh.new()
        bm.from_mesh(obj.data)
        bmesh.ops.recalc_face_normals(bm, faces=bm.faces)
        bm.to_mesh(obj.data)
        obj.data.update()
        bm.free()


def world_bounds() -> tuple[Vector, Vector]:
    points: list[Vector] = []
    for obj in bpy.context.scene.objects:
        if obj.type != "MESH":
            continue
        points.extend(obj.matrix_world @ Vector(corner) for corner in obj.bound_box)
    if not points:
        raise RuntimeError("No mesh geometry remains after cleanup")
    minimum = Vector((min(p.x for p in points), min(p.y for p in points), min(p.z for p in points)))
    maximum = Vector((max(p.x for p in points), max(p.y for p in points), max(p.z for p in points)))
    return minimum, maximum


def center_and_ground() -> None:
    minimum, maximum = world_bounds()
    offset = Vector(
        (
            -((minimum.x + maximum.x) / 2),
            -((minimum.y + maximum.y) / 2),
            -minimum.z,
        )
    )
    roots = [
        obj
        for obj in bpy.context.scene.objects
        if obj.parent is None and obj.type in {"MESH", "ARMATURE"}
    ]
    for obj in roots:
        obj.location += offset
    bpy.context.view_layer.update()


def decimate(target_faces: int) -> None:
    if target_faces <= 0:
        return
    meshes = [obj for obj in bpy.context.scene.objects if obj.type == "MESH"]
    total_faces = sum(len(obj.data.polygons) for obj in meshes)
    if total_faces <= target_faces:
        return
    ratio = max(0.01, target_faces / total_faces)
    for obj in meshes:
        if len(obj.data.polygons) < 20:
            continue
        modifier = obj.modifiers.new(name="AI3D_Decimate", type="DECIMATE")
        modifier.ratio = ratio
        modifier.use_collapse_triangulate = True
        bpy.context.view_layer.objects.active = obj
        obj.select_set(True)
        bpy.ops.object.modifier_apply(modifier=modifier.name)
        obj.select_set(False)


def triangulate() -> None:
    for obj in [item for item in bpy.context.scene.objects if item.type == "MESH"]:
        modifier = obj.modifiers.new(name="AI3D_Triangulate", type="TRIANGULATE")
        bpy.context.view_layer.objects.active = obj
        obj.select_set(True)
        bpy.ops.object.modifier_apply(modifier=modifier.name)
        obj.select_set(False)


def material_report() -> dict:
    missing_images = []
    for image in bpy.data.images:
        if image.source == "FILE" and image.filepath:
            resolved = bpy.path.abspath(image.filepath)
            if not os.path.isfile(resolved):
                missing_images.append(image.name)
    return {
        "materials": len(bpy.data.materials),
        "images": len(bpy.data.images),
        "missing_images": missing_images,
    }


def export_glb(path: Path) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    bpy.ops.export_scene.gltf(
        filepath=str(path),
        export_format="GLB",
        export_apply=True,
        export_texcoords=True,
        export_normals=True,
        export_materials="EXPORT",
    )


def main() -> None:
    args = parse_args()
    input_path = Path(args.input).resolve()
    output_path = Path(args.output).resolve()
    reset_scene()
    import_glb(input_path)
    removed_empty = remove_empty_objects()
    removed_objects = remove_small_mesh_objects(args.min_fragment_ratio)
    removed_components = 0
    for obj in [item for item in bpy.context.scene.objects if item.type == "MESH"]:
        removed_components += remove_loose_fragments(obj, args.min_fragment_ratio)
    apply_transforms_and_normals()
    decimate(args.decimate_face_count)
    if args.triangulate:
        triangulate()
    center_and_ground()
    report = material_report()
    report.update(
        {
            "removed_empty_objects": removed_empty,
            "removed_small_objects": removed_objects,
            "removed_loose_components": removed_components,
            "output": str(output_path),
        }
    )
    export_glb(output_path)
    print("AI3D_CLEANUP_REPORT=" + json.dumps(report, ensure_ascii=False))


if __name__ == "__main__":
    main()
