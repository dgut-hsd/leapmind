from pathlib import Path
import sys

import bpy


ASSET = Path(sys.argv[-2])
OUT = Path(sys.argv[-1])


def clear_scene():
    bpy.ops.object.select_all(action="SELECT")
    bpy.ops.object.delete(use_global=False)


def import_asset(path: Path):
    bpy.ops.import_scene.gltf(filepath=str(path))


def frame_camera():
    scene = bpy.context.scene
    for obj in list(bpy.data.objects):
        if obj.type == "CAMERA":
            bpy.data.objects.remove(obj, do_unlink=True)

    cam_data = bpy.data.cameras.new("PreviewCamera")
    cam = bpy.data.objects.new("PreviewCamera", cam_data)
    scene.collection.objects.link(cam)
    scene.camera = cam
    cam.location = (0.0, -4.0, 1.2)
    cam.rotation_euler = (1.35, 0.0, 0.0)
    cam.data.lens = 55


def add_light():
    for obj in list(bpy.data.objects):
        if obj.type == "LIGHT":
            bpy.data.objects.remove(obj, do_unlink=True)
    light_data = bpy.data.lights.new(name="Sun", type="SUN")
    light = bpy.data.objects.new(name="Sun", object_data=light_data)
    bpy.context.scene.collection.objects.link(light)
    light.rotation_euler = (0.9, 0.0, 0.6)
    light.data.energy = 3.0


def main():
    clear_scene()
    import_asset(ASSET)
    frame_camera()
    add_light()

    scene = bpy.context.scene
    scene.render.engine = "BLENDER_WORKBENCH"
    scene.display.shading.light = "STUDIO"
    scene.display.shading.color_type = "TEXTURE"
    scene.render.resolution_x = 1000
    scene.render.resolution_y = 1000
    scene.render.image_settings.file_format = "PNG"
    scene.render.filepath = str(OUT)
    bpy.ops.render.render(write_still=True)
    print(f"PREVIEW={OUT}")


main()
