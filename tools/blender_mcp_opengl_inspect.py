import anyio
from pathlib import Path

from mcp.client.session import ClientSession
from mcp.client.stdio import StdioServerParameters, stdio_client


OUT_DIR = Path(r"C:\Users\abcd1\.codex\visualizations\2026\08\08\blender_reassembly_audit\opengl_current")


def make_code(view_name: str, out_path: str) -> str:
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
scene = bpy.context.scene
scene.render.filepath = r'{out_path}'
with bpy.context.temp_override(window=window, screen=screen, area=area, region=region, space_data=space):
    bpy.ops.view3d.view_axis(type='{axis}', align_active=False)
    region_3d.view_perspective = 'ORTHO'
    bpy.ops.view3d.view_selected(use_all_regions=False)
    bpy.ops.render.opengl(write_still=True)
print(r'{out_path}')
"""
    if view_name == "three_quarter_left":
        return f"""
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
scene = bpy.context.scene
scene.render.filepath = r'{out_path}'
with bpy.context.temp_override(window=window, screen=screen, area=area, region=region, space_data=space):
    region_3d.view_perspective = 'PERSP'
    region_3d.view_rotation = mathutils.Euler((math.radians(70), 0.0, math.radians(35)), 'XYZ').to_quaternion()
    bpy.ops.view3d.view_selected(use_all_regions=False)
    bpy.ops.render.opengl(write_still=True)
print(r'{out_path}')
"""
    raise ValueError(view_name)


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
            await session.initialize()
            for view_name, filename in [
                ("front", "front.png"),
                ("back", "back.png"),
                ("left", "left.png"),
                ("right", "right.png"),
                ("three_quarter_left", "three_quarter_left.png"),
            ]:
                out_path = str((OUT_DIR / filename).resolve())
                await session.call_tool(
                    "execute_blender_code",
                    {
                        "code": make_code(view_name, out_path),
                        "user_prompt": f"Render a read-only OpenGL inspection image for the {view_name} view.",
                    },
                )
    print(str(OUT_DIR))


if __name__ == "__main__":
    anyio.run(main)
