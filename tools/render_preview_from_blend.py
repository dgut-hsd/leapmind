from pathlib import Path
import sys

import bpy


if "--" in sys.argv:
    args = sys.argv[sys.argv.index("--") + 1 :]
else:
    args = []

if len(args) >= 2:
    BLEND = Path(args[0])
    OUT = Path(args[1])
else:
    BLEND = Path(r"E:\人物一\组装输出\RPC_完整角色_VRM脸版.blend")
    OUT = Path(r"E:\人物一\组装输出\RPC_完整角色_VRM脸版_预览.png")


def main():
    bpy.ops.wm.open_mainfile(filepath=str(BLEND))

    scene = bpy.context.scene
    scene.render.engine = "BLENDER_WORKBENCH"
    scene.display.shading.light = "STUDIO"
    scene.display.shading.color_type = "TEXTURE"
    scene.render.resolution_x = 1200
    scene.render.resolution_y = 1600
    scene.render.image_settings.file_format = "PNG"
    scene.render.film_transparent = False

    for obj in list(bpy.data.objects):
        if obj.type == "CAMERA":
            bpy.data.objects.remove(obj, do_unlink=True)

    cam_data = bpy.data.cameras.new("PreviewCamera")
    cam = bpy.data.objects.new("PreviewCamera", cam_data)
    scene.collection.objects.link(cam)
    scene.camera = cam

    cam.location = (0.0, -4.8, 1.15)
    cam.rotation_euler = (1.3963, 0.0, 0.0)
    cam.data.lens = 58

    scene.render.filepath = str(OUT)
    bpy.ops.render.render(write_still=True)
    print(f"PREVIEW={OUT}")


main()
