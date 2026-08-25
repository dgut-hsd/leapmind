import anyio
import base64
import json
from pathlib import Path

from mcp.client.session import ClientSession
from mcp.client.stdio import StdioServerParameters, stdio_client


OUT_DIR = Path(r"C:\Users\abcd1\.codex\visualizations\2026\08\08\blender_reassembly_audit")


def ensure_parent(path: Path) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)


def decode_image_data(data):
    if isinstance(data, bytes):
        return data
    return base64.b64decode(data)


def parse_first_text(result):
    for item in result.content:
        if hasattr(item, "text"):
            return item.text
    raise RuntimeError("No text content returned")


def save_first_image(result, path: Path) -> None:
    for item in result.content:
        if hasattr(item, "data"):
            ensure_parent(path)
            path.write_bytes(decode_image_data(item.data))
            return
    raise RuntimeError("No image content returned")


def make_view_setup_code(view_name: str) -> str:
    if view_name in {"front", "back", "left", "right"}:
        axis = view_name.upper()
        return f"""
import bpy

def get_ctx():
    for window in bpy.context.window_manager.windows:
        screen = window.screen
        for area in screen.areas:
            if area.type == 'VIEW_3D':
                for region in area.regions:
                    if region.type == 'WINDOW':
                        return window, screen, area, region
    raise RuntimeError('No VIEW_3D area found')

window, screen, area, region = get_ctx()
space = area.spaces.active
space.shading.type = 'MATERIAL'
space.overlay.show_overlays = False
region_3d = space.region_3d
character_names = ['Character_Body', 'Character_Head', 'Character_Hair', 'Character_Shoes', 'Character_Cape']
for obj in bpy.context.selected_objects:
    obj.select_set(False)
for name in character_names:
    obj = bpy.data.objects.get(name)
    if obj:
        obj.select_set(True)
        bpy.context.view_layer.objects.active = obj
with bpy.context.temp_override(window=window, screen=screen, area=area, region=region, space_data=space):
    bpy.ops.view3d.view_axis(type='{axis}', align_active=False)
    region_3d.view_perspective = 'ORTHO'
    bpy.ops.view3d.view_selected(use_all_regions=False)
print('ok')
"""
    if view_name == "three_quarter_left":
        return """
import bpy
import math
import mathutils

def get_ctx():
    for window in bpy.context.window_manager.windows:
        screen = window.screen
        for area in screen.areas:
            if area.type == 'VIEW_3D':
                for region in area.regions:
                    if region.type == 'WINDOW':
                        return window, screen, area, region
    raise RuntimeError('No VIEW_3D area found')

window, screen, area, region = get_ctx()
space = area.spaces.active
space.shading.type = 'MATERIAL'
space.overlay.show_overlays = False
region_3d = space.region_3d
character_names = ['Character_Body', 'Character_Head', 'Character_Hair', 'Character_Shoes', 'Character_Cape']
for obj in bpy.context.selected_objects:
    obj.select_set(False)
for name in character_names:
    obj = bpy.data.objects.get(name)
    if obj:
        obj.select_set(True)
        bpy.context.view_layer.objects.active = obj
with bpy.context.temp_override(window=window, screen=screen, area=area, region=region, space_data=space):
    region_3d.view_perspective = 'PERSP'
    region_3d.view_rotation = mathutils.Euler((math.radians(70), 0.0, math.radians(35)), 'XYZ').to_quaternion()
    bpy.ops.view3d.view_selected(use_all_regions=False)
print('ok')
"""
    raise ValueError(view_name)


SCENE_AUDIT_CODE = r"""
import bpy
import json
from mathutils import Vector

def world_bbox(obj):
    corners = [obj.matrix_world @ Vector(corner) for corner in obj.bound_box]
    xs = [c.x for c in corners]
    ys = [c.y for c in corners]
    zs = [c.z for c in corners]
    return {
        "min": [min(xs), min(ys), min(zs)],
        "max": [max(xs), max(ys), max(zs)],
        "center": [(min(xs)+max(xs))/2, (min(ys)+max(ys))/2, (min(zs)+max(zs))/2],
        "size": [max(xs)-min(xs), max(ys)-min(ys), max(zs)-min(zs)],
    }

def rotation_deg(obj):
    return [round(v * 57.2957795131, 4) for v in obj.rotation_euler]

scene = bpy.context.scene
data = {
    "scene_name": scene.name,
    "collections": [],
    "objects": [],
}

for coll in bpy.data.collections:
    data["collections"].append({
        "name": coll.name,
        "objects": [obj.name for obj in coll.objects],
        "children": [child.name for child in coll.children],
    })

for obj in bpy.data.objects:
    entry = {
        "name": obj.name,
        "type": obj.type,
        "parent": obj.parent.name if obj.parent else None,
        "children": [child.name for child in obj.children],
        "location": [round(v, 6) for v in obj.location],
        "rotation_deg": rotation_deg(obj),
        "scale": [round(v, 6) for v in obj.scale],
        "dimensions": [round(v, 6) for v in obj.dimensions],
        "origin_world": [round(v, 6) for v in obj.matrix_world.translation],
        "bbox_world": world_bbox(obj),
        "materials": [slot.material.name if slot.material else None for slot in obj.material_slots],
        "visible": obj.visible_get(),
        "hide_viewport": obj.hide_viewport,
        "data_name": obj.data.name if getattr(obj, "data", None) else None,
    }
    if obj.type == 'MESH':
        entry["mesh_stats"] = {
            "vertices": len(obj.data.vertices),
            "edges": len(obj.data.edges),
            "polygons": len(obj.data.polygons),
        }
    data["objects"].append(entry)

print(json.dumps(data, ensure_ascii=False, indent=2))
"""


async def main() -> None:
    OUT_DIR.mkdir(parents=True, exist_ok=True)
    params = StdioServerParameters(
        command=r"C:\Users\abcd1\.local\bin\uvx.exe",
        args=["--python", "3.11", "blender-mcp"],
        env={
            "UV_PYTHON_PREFERENCE": "only-managed",
            "BLENDER_HOST": "localhost",
            "BLENDER_PORT": "9876",
            "DISABLE_TELEMETRY": "true",
        },
    )

    async with stdio_client(params) as (read_stream, write_stream):
        async with ClientSession(read_stream, write_stream) as session:
            init = await session.initialize()
            summary = {"server": {"name": init.serverInfo.name, "version": init.serverInfo.version}}

            scene_result = await session.call_tool("get_scene_info", {"user_prompt": "Audit the current scene only, do not modify anything."})
            scene_text = parse_first_text(scene_result)
            (OUT_DIR / "scene_info.json").write_text(scene_text, encoding="utf-8")

            audit_result = await session.call_tool("execute_blender_code", {"code": SCENE_AUDIT_CODE, "user_prompt": "Read-only scene audit with transforms and bounding boxes."})
            audit_text = parse_first_text(audit_result)
            if audit_text.startswith("Code executed successfully: "):
                audit_text = audit_text[len("Code executed successfully: "):]
            (OUT_DIR / "scene_audit.json").write_text(audit_text, encoding="utf-8")

            object_names = ["Character_Body", "Character_Head", "Character_Hair", "Character_Shoes", "Character_Cape"]
            obj_dir = OUT_DIR / "objects"
            obj_dir.mkdir(exist_ok=True)
            for name in object_names:
                obj_result = await session.call_tool(
                    "get_object_info",
                    {"object_name": name, "user_prompt": f"Read object information for {name}. Do not modify anything."},
                )
                obj_text = parse_first_text(obj_result)
                (obj_dir / f"{name}.json").write_text(obj_text, encoding="utf-8")

            views = [
                ("front", "front.png"),
                ("left", "left.png"),
                ("right", "right.png"),
                ("back", "back.png"),
                ("three_quarter_left", "three_quarter_left.png"),
            ]
            for view_name, filename in views:
                await session.call_tool(
                    "execute_blender_code",
                    {"code": make_view_setup_code(view_name), "user_prompt": f"Set viewport to {view_name} view for read-only inspection only."},
                )
                shot_result = await session.call_tool(
                    "get_viewport_screenshot",
                    {"max_size": 1400, "user_prompt": f"Capture {view_name} inspection screenshot only."},
                )
                save_first_image(shot_result, OUT_DIR / filename)

            (OUT_DIR / "summary.json").write_text(json.dumps(summary, ensure_ascii=False, indent=2), encoding="utf-8")
            print(str(OUT_DIR))


if __name__ == "__main__":
    anyio.run(main)
