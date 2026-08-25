import bpy


for mat in bpy.data.materials:
    if not mat.use_nodes:
        continue
    for node in mat.node_tree.nodes:
        if node.name.startswith("Codex color lift"):
            node.inputs["Saturation"].default_value = 1.18
            node.inputs["Value"].default_value = 0.72

for obj in bpy.data.objects:
    if obj.name == "Codex softbox key" and obj.type == "LIGHT":
        obj.data.energy = 260
        obj.data.size = 1.9
    if obj.name == "Codex cool rim" and obj.type == "LIGHT":
        obj.data.energy = 70
        obj.data.size = 1.2

bpy.context.scene.view_settings.view_transform = "Standard"
bpy.context.scene.view_settings.look = "None"
bpy.context.scene.view_settings.exposure = -0.45
bpy.context.scene.view_settings.gamma = 1.0
bpy.context.scene.world.color = (0.78, 0.78, 0.78)

camera = bpy.data.objects.get("Codex Camera - refined front")
if camera:
    bpy.context.scene.camera = camera
    camera.data.ortho_scale = 1.38

bpy.ops.wm.save_as_mainfile(filepath=r"E:\Mycode1\未命名_refined.blend")
