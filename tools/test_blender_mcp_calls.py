import anyio
import json
from pathlib import Path

from mcp.client.session import ClientSession
from mcp.client.stdio import StdioServerParameters, stdio_client


def summarize_content(result):
    summary = []
    for item in result.content:
        entry = {"type": getattr(item, "type", type(item).__name__)}
        if hasattr(item, "text"):
            text = item.text
            entry["text_preview"] = text[:800]
            entry["text_length"] = len(text)
        if hasattr(item, "data"):
            entry["data_length"] = len(item.data)
        if hasattr(item, "mimeType"):
            entry["mime"] = item.mimeType
        summary.append(entry)
    return summary


async def main():
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
            print(json.dumps({"server": init.serverInfo.name, "version": init.serverInfo.version}, ensure_ascii=False))

            scene = await session.call_tool("get_scene_info", {"user_prompt": "Read the current scene and list the objects."})
            print("SCENE=" + json.dumps(summarize_content(scene), ensure_ascii=False))

            obj = await session.call_tool(
                "get_object_info",
                {"object_name": "Character_Body", "user_prompt": "Read this object's transform and metadata."},
            )
            print("OBJECT=" + json.dumps(summarize_content(obj), ensure_ascii=False))

            shot = await session.call_tool(
                "get_viewport_screenshot",
                {"max_size": 1000, "user_prompt": "Capture the current viewport without changing the model."},
            )
            print("SHOT=" + json.dumps(summarize_content(shot), ensure_ascii=False))

            code = """
import json
import bpy
import mathutils
from mathutils import Vector, Matrix
from mathutils.bvhtree import BVHTree
from mathutils.kdtree import KDTree

obj = bpy.data.objects.get("Character_Body")
result = {
    "bpy": bool(bpy),
    "mathutils": bool(mathutils),
    "Vector": Vector((1, 2, 3)).to_tuple(),
    "Matrix_rows": len(Matrix.Identity(4)),
    "Matrix_cols": len(Matrix.Identity(4)[0]),
    "BVHTree": BVHTree.__name__,
    "KDTree": KDTree.__name__,
    "parent_set_exists": hasattr(bpy.ops.object, "parent_set"),
    "snap_selected_to_grid_exists": hasattr(bpy.ops.view3d, "snap_selected_to_grid"),
    "child_of_constraint_exists": hasattr(bpy.types, "ChildOfConstraint"),
    "copy_location_constraint_exists": hasattr(bpy.types, "CopyLocationConstraint"),
    "copy_rotation_constraint_exists": hasattr(bpy.types, "CopyRotationConstraint"),
    "copy_scale_constraint_exists": hasattr(bpy.types, "CopyScaleConstraint"),
    "limit_location_constraint_exists": hasattr(bpy.types, "LimitLocationConstraint"),
    "limit_rotation_constraint_exists": hasattr(bpy.types, "LimitRotationConstraint"),
    "mirror_operator_exists": hasattr(bpy.ops.transform, "mirror"),
    "origin_set_exists": hasattr(bpy.ops.object, "origin_set"),
    "cursor_location": tuple(bpy.context.scene.cursor.location),
    "object_name": obj.name if obj else None,
    "object_parent": obj.parent.name if obj and obj.parent else None,
    "matrix_world": [list(row) for row in obj.matrix_world] if obj else None,
}
print(json.dumps(result, ensure_ascii=False))
"""
            code_result = await session.call_tool(
                "execute_blender_code",
                {"code": code, "user_prompt": "Run read-only Python capability checks only."},
            )
            print("CODE=" + json.dumps(summarize_content(code_result), ensure_ascii=False))


anyio.run(main)
