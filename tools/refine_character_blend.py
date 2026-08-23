from pathlib import Path

import bpy
from mathutils import Vector


OUT_BLEND = r"E:\Mycode1\未命名_refined.blend"
REFS = [
    (r"C:\Users\abcd1\Desktop\人物一\已生成图像 1.png", "Front reference", (-1.25, 0.62, 0.68), (1.05, 1.55)),
    (r"C:\Users\abcd1\Desktop\人物一\已生成图像 2.png", "Side reference", (1.25, 0.62, 0.68), (1.05, 1.55)),
    (r"C:\Users\abcd1\Desktop\人物一\已生成图像 3.png", "Back reference", (0.0, 0.82, 0.68), (1.05, 1.55)),
    (r"C:\Users\abcd1\Desktop\人物一\已生成图像 4.png", "Three-quarter reference", (0.0, -0.72, 0.68), (1.05, 1.55)),
]


def collection(name):
    col = bpy.data.collections.get(name) or bpy.data.collections.new(name)
    if col.name not in [child.name for child in bpy.context.scene.collection.children]:
        bpy.context.scene.collection.children.link(col)
    return col


def link_to_collection(obj, col):
    for c in obj.users_collection:
        c.objects.unlink(obj)
    col.objects.link(obj)


def mesh_bounds(meshes):
    coords = [obj.matrix_world @ Vector(corner) for obj in meshes for corner in obj.bound_box]
    low = Vector((min(v.x for v in coords), min(v.y for v in coords), min(v.z for v in coords)))
    high = Vector((max(v.x for v in coords), max(v.y for v in coords), max(v.z for v in coords)))
    return low, high, (low + high) / 2, high - low


def get_input(node, names):
    for name in names:
        if name in node.inputs:
            return node.inputs[name]
    lower = [name.lower() for name in names]
    for socket in node.inputs:
        if socket.name.lower() in lower:
            return socket
    return None


def improve_material(mat):
    mat.use_nodes = True
    mat.diffuse_color = (0.95, 0.92, 1.0, 1.0)
    mat.use_screen_refraction = False

    nodes = mat.node_tree.nodes
    links = mat.node_tree.links
    bsdf = next((n for n in nodes if n.bl_idname == "ShaderNodeBsdfPrincipled"), None)
    tex = next((n for n in nodes if n.bl_idname == "ShaderNodeTexImage" and n.image and "normal" not in n.image.name.lower()), None)
    if not bsdf or not tex:
        return

    sat = nodes.new("ShaderNodeHueSaturation")
    sat.name = "Codex color lift - closer to reference"
    sat.inputs["Saturation"].default_value = 1.28
    sat.inputs["Value"].default_value = 0.88
    sat.location = ((tex.location.x + bsdf.location.x) / 2, tex.location.y + 120)

    base = get_input(bsdf, ["Base Color", "基础色"])
    if base:
        for link in list(base.links):
            links.remove(link)
        links.new(tex.outputs["Color"], sat.inputs["Color"])
        links.new(sat.outputs["Color"], base)

    rough = get_input(bsdf, ["Roughness", "粗糙度"])
    if rough and not rough.is_linked:
        rough.default_value = 0.68
    metallic = get_input(bsdf, ["Metallic", "金属度"])
    if metallic and not metallic.is_linked:
        metallic.default_value = 0.0
    alpha = get_input(bsdf, ["Alpha", "Alpha"])
    if alpha:
        mat.blend_method = "BLEND"
        mat.show_transparent_back = True


def add_image_plane(path, name, loc, size, col):
    img = bpy.data.images.load(path, check_existing=True)
    mat = bpy.data.materials.new(f"Codex {name} material")
    mat.use_nodes = True
    nodes = mat.node_tree.nodes
    bsdf = next(n for n in nodes if n.bl_idname == "ShaderNodeBsdfPrincipled")
    tex = nodes.new("ShaderNodeTexImage")
    tex.image = img
    mat.node_tree.links.new(tex.outputs["Color"], bsdf.inputs["Base Color"])
    if "Alpha" in bsdf.inputs:
        mat.node_tree.links.new(tex.outputs["Alpha"], bsdf.inputs["Alpha"])
    mat.blend_method = "BLEND"

    width, height = size
    mesh = bpy.data.meshes.new(f"Codex {name} mesh")
    verts = [(-width / 2, 0, -height / 2), (width / 2, 0, -height / 2), (width / 2, 0, height / 2), (-width / 2, 0, height / 2)]
    mesh.from_pydata(verts, [], [(0, 1, 2, 3)])
    mesh.update()
    obj = bpy.data.objects.new(f"Codex {name}", mesh)
    obj.location = loc
    obj.data.materials.append(mat)
    col.objects.link(obj)
    return obj


def look_at(obj, target):
    direction = Vector(target) - obj.location
    obj.rotation_euler = direction.to_track_quat("-Z", "Y").to_euler()


def add_camera(name, loc, target, lens=70, ortho=1.34):
    cam_data = bpy.data.cameras.new(name)
    cam = bpy.data.objects.new(name, cam_data)
    bpy.context.collection.objects.link(cam)
    cam.location = loc
    cam.data.type = "ORTHO"
    cam.data.ortho_scale = ortho
    cam.data.lens = lens
    look_at(cam, target)
    return cam


mesh_objects = [obj for obj in bpy.context.scene.objects if obj.type == "MESH" and obj.name != "Viewer Node"]
low, high, center, size = mesh_bounds(mesh_objects)

for obj in mesh_objects:
    bpy.context.view_layer.objects.active = obj
    obj.select_set(True)
    try:
        bpy.ops.object.shade_smooth()
    except Exception:
        pass
    obj.select_set(False)
    if not any(mod.type == "WEIGHTED_NORMAL" for mod in obj.modifiers):
        mod = obj.modifiers.new("Codex refined weighted normals", "WEIGHTED_NORMAL")
        mod.keep_sharp = True
        mod.weight = 50

for mat in bpy.data.materials:
    if mat.name.startswith("Material"):
        improve_material(mat)

ref_col = collection("Codex reference boards")
for path, name, loc, size2d in REFS:
    if Path(path).exists():
        add_image_plane(path, name, loc, size2d, ref_col)

light_col = collection("Codex refined lighting")
for obj in list(light_col.objects):
    bpy.data.objects.remove(obj, do_unlink=True)

key_data = bpy.data.lights.new("Codex softbox key", "AREA")
key = bpy.data.objects.new("Codex softbox key", key_data)
key.location = center + Vector((0.0, -1.2, 1.35))
key.data.energy = 520
key.data.size = 1.6
light_col.objects.link(key)

rim_data = bpy.data.lights.new("Codex cool rim", "AREA")
rim = bpy.data.objects.new("Codex cool rim", rim_data)
rim.location = center + Vector((-0.8, 0.7, 1.0))
rim.data.energy = 130
rim.data.size = 0.9
rim.data.color = (0.72, 0.82, 1.0)
light_col.objects.link(rim)

cam_col = collection("Codex review cameras")
bpy.context.view_layer.active_layer_collection = bpy.context.view_layer.layer_collection
front = add_camera("Codex Camera - refined front", center + Vector((0, -1.8, 0.12)), center + Vector((0, 0, 0.08)), ortho=1.28)
side = add_camera("Codex Camera - refined side", center + Vector((1.8, 0, 0.12)), center + Vector((0, 0, 0.08)), ortho=1.28)
back = add_camera("Codex Camera - refined back", center + Vector((0, 1.8, 0.12)), center + Vector((0, 0, 0.08)), ortho=1.28)
three = add_camera("Codex Camera - refined 3q", center + Vector((1.25, -1.45, 0.16)), center + Vector((0, 0, 0.08)), ortho=1.28)
for cam in [front, side, back, three]:
    link_to_collection(cam, cam_col)
bpy.context.scene.camera = front

bpy.context.scene.render.engine = "BLENDER_EEVEE"
if hasattr(bpy.context.scene, "eevee"):
    if hasattr(bpy.context.scene.eevee, "use_gtao"):
        bpy.context.scene.eevee.use_gtao = True
    if hasattr(bpy.context.scene.eevee, "gtao_distance"):
        bpy.context.scene.eevee.gtao_distance = 2
    if hasattr(bpy.context.scene.eevee, "gtao_factor"):
        bpy.context.scene.eevee.gtao_factor = 1.2
    if hasattr(bpy.context.scene.eevee, "taa_render_samples"):
        bpy.context.scene.eevee.taa_render_samples = 96
bpy.context.scene.view_settings.view_transform = "Standard"
bpy.context.scene.view_settings.look = "Medium High Contrast"
bpy.context.scene.view_settings.exposure = -0.15
bpy.context.scene.view_settings.gamma = 1.0
bpy.context.scene.world.color = (1, 1, 1)
bpy.context.scene.render.resolution_x = 1400
bpy.context.scene.render.resolution_y = 1800
bpy.context.scene.render.film_transparent = False

bpy.ops.wm.save_as_mainfile(filepath=OUT_BLEND)
