import math
import shutil
from pathlib import Path

import bpy
from mathutils import Vector


SRC_DIR = Path(r"E:\人物一")
OUT_DIR = Path(r"E:\人物一\组装输出")
OUT_DIR.mkdir(parents=True, exist_ok=True)

VRM_PATH = SRC_DIR / "AvatarSample_O.vrm"
HAIR_PATH = SRC_DIR / "头发.glb"
OUT_BLEND = OUT_DIR / "VRM头_目标角色改造.blend"
OUT_GLB = OUT_DIR / "VRM头_目标角色改造.glb"
OUT_PREVIEW = OUT_DIR / "VRM头_目标角色改造_预览.png"


def clear_scene():
    bpy.ops.object.select_all(action="SELECT")
    bpy.ops.object.delete()
    for family in (bpy.data.meshes, bpy.data.materials, bpy.data.images, bpy.data.armatures):
        for block in list(family):
            if block.users == 0:
                family.remove(block)


def import_vrm_as_gltf(path: Path):
    temp = OUT_DIR / "_tmp_vrm_import.glb"
    shutil.copyfile(path, temp)
    bpy.ops.import_scene.gltf(filepath=str(temp))


def import_gltf(path: Path):
    before = set(bpy.data.objects)
    bpy.ops.import_scene.gltf(filepath=str(path))
    return [obj for obj in bpy.data.objects if obj not in before]


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


def make_mat(name, color, metallic=0.0, roughness=0.55, alpha=1.0, emission=None, strength=0.0):
    mat = bpy.data.materials.new(name)
    mat.use_nodes = True
    bsdf = next((node for node in mat.node_tree.nodes if node.type == "BSDF_PRINCIPLED"), None)
    if bsdf:
        if "Base Color" in bsdf.inputs:
            bsdf.inputs["Base Color"].default_value = color
        if "Metallic" in bsdf.inputs:
            bsdf.inputs["Metallic"].default_value = metallic
        if "Roughness" in bsdf.inputs:
            bsdf.inputs["Roughness"].default_value = roughness
        if "Alpha" in bsdf.inputs:
            bsdf.inputs["Alpha"].default_value = alpha
        if emission:
            if "Emission Color" in bsdf.inputs:
                bsdf.inputs["Emission Color"].default_value = emission
            if "Emission Strength" in bsdf.inputs:
                bsdf.inputs["Emission Strength"].default_value = strength
    mat.diffuse_color = color
    if alpha < 1.0:
        mat.blend_method = "BLEND"
        mat.show_transparent_back = True
    return mat


def assign(obj, mat):
    obj.data.materials.clear()
    obj.data.materials.append(mat)


def shade_smooth(obj):
    bpy.ops.object.select_all(action="DESELECT")
    obj.select_set(True)
    bpy.context.view_layer.objects.active = obj
    try:
        bpy.ops.object.shade_smooth()
    except Exception:
        pass


def add_sphere(name, loc, scale, mat, segments=32):
    bpy.ops.mesh.primitive_uv_sphere_add(segments=segments, ring_count=16, radius=1, location=loc)
    obj = bpy.context.object
    obj.name = name
    obj.scale = scale
    assign(obj, mat)
    shade_smooth(obj)
    return obj


def add_cone(name, loc, scale, mat, rotation=(0, 0, 0), vertices=48):
    bpy.ops.mesh.primitive_cone_add(vertices=vertices, radius1=1, radius2=0.035, depth=1, location=loc, rotation=rotation)
    obj = bpy.context.object
    obj.name = name
    obj.scale = scale
    assign(obj, mat)
    shade_smooth(obj)
    return obj


def add_flat_ear(name, side, face_low, face_high, mat):
    """Create a simple flat elf ear that reads well from the front."""
    sx = -1 if side == "left" else 1
    width = face_high.x - face_low.x
    height = face_high.z - face_low.z
    y = face_low.y - 0.001
    base_x = face_low.x if side == "left" else face_high.x
    tip_x = base_x + sx * width * 0.18
    z_mid = face_low.z + height * 0.46
    z_top = z_mid + height * 0.075
    z_bot = z_mid - height * 0.075
    verts = [
        (base_x + sx * width * 0.015, y, z_top),
        (base_x + sx * width * 0.010, y, z_bot),
        (tip_x, y - 0.006, z_mid),
    ]
    faces = [(0, 1, 2)]
    mesh = bpy.data.meshes.new(name + "_mesh")
    mesh.from_pydata(verts, [], faces)
    mesh.update()
    obj = bpy.data.objects.new(name, mesh)
    bpy.context.collection.objects.link(obj)
    assign(obj, mat)
    return obj


def add_eye_disk(name, loc, radius_x, radius_z, mat, segments=48):
    verts = [(0, 0, 0)]
    for i in range(segments):
        angle = math.tau * i / segments
        verts.append((math.cos(angle) * radius_x, 0, math.sin(angle) * radius_z))
    faces = [tuple([0] + list(range(1, segments + 1)))]
    mesh = bpy.data.meshes.new(name + "_mesh")
    mesh.from_pydata(verts, [], faces)
    mesh.update()
    obj = bpy.data.objects.new(name, mesh)
    bpy.context.collection.objects.link(obj)
    obj.location = loc
    assign(obj, mat)
    return obj


def add_cube(name, loc, scale, mat, rotation=(0, 0, 0)):
    bpy.ops.mesh.primitive_cube_add(size=1, location=loc, rotation=rotation)
    obj = bpy.context.object
    obj.name = name
    obj.scale = scale
    assign(obj, mat)
    bevel = obj.modifiers.new("soft bevel", "BEVEL")
    bevel.width = 0.015
    bevel.segments = 2
    obj.modifiers.new("weighted normals", "WEIGHTED_NORMAL")
    return obj


def look_at(obj, target):
    direction = Vector(target) - obj.location
    obj.rotation_euler = direction.to_track_quat("-Z", "Y").to_euler()


def main():
    clear_scene()

    skin_mat = make_mat("target_skin_for_elf_ears", (1.0, 0.77, 0.67, 1), roughness=0.62)
    gold_eye_mat = make_mat("target_left_eye_gold_overlay", (1.0, 0.61, 0.05, 1), roughness=0.25, emission=(1.0, 0.50, 0.05, 1), strength=0.12)
    cyan_eye_mat = make_mat("target_right_eye_cyan_overlay", (0.0, 0.83, 1.0, 1), roughness=0.25, emission=(0.0, 0.70, 1.0, 1), strength=0.12)
    black_mat = make_mat("target_black_collar", (0.015, 0.014, 0.018, 1), roughness=0.5)
    gold_trim_mat = make_mat("target_gold_trim", (0.95, 0.58, 0.16, 1), metallic=0.55, roughness=0.28)

    import_vrm_as_gltf(VRM_PATH)

    face = bpy.data.objects.get("Face") or bpy.data.objects.get("Face (merged)(Clone)")
    if face is None:
        face = next((obj for obj in bpy.context.scene.objects if obj.type == "MESH" and "face" in obj.name.lower()), None)
    if face is None:
        raise RuntimeError("Could not find VRM face mesh")

    # Keep the better VRM face, hide original maid body/hair and armature clutter.
    for obj in bpy.context.scene.objects:
        lower = obj.name.lower()
        if obj != face and (obj.type == "ARMATURE" or "body" in lower or "hair" in lower or "棱角球" in obj.name):
            obj.hide_viewport = True
            obj.hide_render = True

    face.name = "VRM_base_face_modified"
    shade_smooth(face)
    face.modifiers.new("face_weighted_normals", "WEIGHTED_NORMAL")
    for slot in face.material_slots:
        if slot.material and "Face_00_SKIN" in slot.material.name:
            skin_mat = slot.material
            break

    face_bounds = bounds([face])
    low, high, center, size = face_bounds

    hair_objs = [obj for obj in import_gltf(HAIR_PATH) if obj.type == "MESH"]
    hair_bounds = bounds(hair_objs)
    h_low, h_high, h_center, h_size = hair_bounds

    # Fit target short lavender hair to the cleaner VRM face.
    target_width = size.x * 1.28
    target_height = size.z * 0.86
    scale_factor = min(target_width / max(h_size.x, 0.001), target_height / max(h_size.z, 0.001))
    desired_hair_center = Vector((center.x, center.y + size.y * 0.035, center.z + size.z * 0.29))
    for obj in hair_objs:
        obj.name = "target_lavender_hair_" + obj.name
        obj.scale = (obj.scale.x * scale_factor, obj.scale.y * scale_factor, obj.scale.z * scale_factor)
        obj.location += desired_hair_center - h_center * scale_factor
        shade_smooth(obj)
        if not any(mod.type == "WEIGHTED_NORMAL" for mod in obj.modifiers):
            obj.modifiers.new("hair_weighted_normals", "WEIGHTED_NORMAL")

    # Subtle pointed-ear extensions. The VRM ear itself stays as the base.
    left_ear = add_flat_ear("target_elf_ear_tip_left", "left", low, high, skin_mat)
    right_ear = add_flat_ear("target_elf_ear_tip_right", "right", low, high, skin_mat)

    # Heterochromia overlays: use the VRM iris material bounds instead of guessing.
    iris_coords = []
    iris_index = None
    for idx, slot in enumerate(face.material_slots):
        if slot.material and "EyeIris" in slot.material.name:
            iris_index = idx
            break
    if iris_index is not None:
        mesh = face.data
        for poly in mesh.polygons:
            if poly.material_index == iris_index:
                for vi in poly.vertices:
                    iris_coords.append(face.matrix_world @ mesh.vertices[vi].co)
    if iris_coords:
        iris_low = Vector((min(v.x for v in iris_coords), min(v.y for v in iris_coords), min(v.z for v in iris_coords)))
        iris_high = Vector((max(v.x for v in iris_coords), max(v.y for v in iris_coords), max(v.z for v in iris_coords)))
        iris_center = (iris_low + iris_high) / 2
        eye_y = iris_low.y - 0.006
        eye_z = iris_center.z
        eye_x_offset = (iris_high.x - iris_low.x) * 0.255
        eye_radius_x = (iris_high.x - iris_low.x) * 0.060
        eye_radius_z = (iris_high.z - iris_low.z) * 0.18
    else:
        eye_y = low.y - 0.006
        eye_z = center.z - size.z * 0.12
        eye_x_offset = size.x * 0.20
        eye_radius_x = size.x * 0.012
        eye_radius_z = size.z * 0.012
    add_eye_disk("target_left_gold_eye_overlay", (center.x - eye_x_offset, eye_y, eye_z), eye_radius_x, eye_radius_z, gold_eye_mat)
    add_eye_disk("target_right_cyan_eye_overlay", (center.x + eye_x_offset, eye_y, eye_z), eye_radius_x, eye_radius_z, cyan_eye_mat)

    # Keep the neck clean for assembly; collar will come from the body component.

    # Keep source note as custom properties for later assembly.
    for obj in [face, *hair_objs, left_ear, right_ear]:
        obj["codex_role"] = "modified_vrm_head_for_target_character"

    b = bounds([obj for obj in bpy.context.scene.objects if obj.type == "MESH" and not obj.hide_render])
    low, high, center, size = b
    radius = max(size.length, 1.0)

    light_data = bpy.data.lights.new("preview_softbox", "AREA")
    light = bpy.data.objects.new("preview_softbox", light_data)
    bpy.context.collection.objects.link(light)
    light.location = center + Vector((0, -radius * 1.3, radius * 1.0))
    light.data.energy = 450
    light.data.size = radius * 0.85

    cam_data = bpy.data.cameras.new("Camera_front_head_check")
    cam = bpy.data.objects.new("Camera_front_head_check", cam_data)
    bpy.context.collection.objects.link(cam)
    cam.location = Vector((center.x, low.y - radius * 2.2, center.z))
    cam.data.type = "ORTHO"
    cam.data.ortho_scale = max(size.x, size.z) * 0.92
    cam.rotation_euler = (math.radians(90), 0, 0)
    bpy.context.scene.camera = cam

    bpy.context.scene.render.engine = "BLENDER_EEVEE"
    bpy.context.scene.render.resolution_x = 1100
    bpy.context.scene.render.resolution_y = 1300
    bpy.context.scene.world.color = (0.78, 0.78, 0.78)
    bpy.context.scene.render.filepath = str(OUT_PREVIEW)
    bpy.ops.render.render(write_still=True)

    bpy.ops.wm.save_as_mainfile(filepath=str(OUT_BLEND), compress=True)
    bpy.ops.export_scene.gltf(filepath=str(OUT_GLB), export_format="GLB", export_materials="EXPORT", export_apply=True, use_visible=True)


if __name__ == "__main__":
    main()
