import bpy


for obj in bpy.data.objects:
    if obj.name.startswith("Codex ") and "reference" in obj.name.lower():
        obj.hide_render = True
        obj.show_transparent = True

for col_name in ["Codex reference boards"]:
    col = bpy.data.collections.get(col_name)
    if col:
        col.hide_render = True

camera = bpy.data.objects.get("Codex Camera - refined front")
if camera:
    bpy.context.scene.camera = camera
    camera.data.ortho_scale = 1.32
    camera.data.clip_end = 100

bpy.ops.wm.save_as_mainfile(filepath=r"E:\Mycode1\未命名_refined.blend")
