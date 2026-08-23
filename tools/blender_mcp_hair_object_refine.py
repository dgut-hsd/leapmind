import anyio
import json
from pathlib import Path

from mcp.client.session import ClientSession
from mcp.client.stdio import StdioServerParameters, stdio_client


OUT_PATH = Path(r"C:\Users\abcd1\.codex\visualizations\2026\08\08\blender_reassembly_audit\hair_object_refine.json")


BLENDER_CODE = r"""
import bpy
import json
import math
import bmesh
from mathutils.bvhtree import BVHTree

head = bpy.data.objects.get('Character_Head')
hair = bpy.data.objects.get('Character_Hair')
if head is None or hair is None:
    raise RuntimeError('Character_Head or Character_Hair not found')

depsgraph = bpy.context.evaluated_depsgraph_get()

orig_loc = hair.location.copy()
orig_rot = hair.rotation_euler.copy()
orig_scale = hair.scale.copy()

def build_world_bvh(obj):
    eval_obj = obj.evaluated_get(depsgraph)
    mesh = eval_obj.to_mesh(preserve_all_data_layers=False, depsgraph=depsgraph)
    try:
        bm = bmesh.new()
        bm.from_mesh(mesh)
        bm.transform(obj.matrix_world)
        tree = BVHTree.FromBMesh(bm, epsilon=0.0)
        return tree
    finally:
        bm.free()
        eval_obj.to_mesh_clear()

def collect_head_samples():
    eval_obj = head.evaluated_get(depsgraph)
    mesh = eval_obj.to_mesh(preserve_all_data_layers=False, depsgraph=depsgraph)
    try:
        coords = [v.co.copy() for v in mesh.vertices]
        xs = [v.x for v in coords]
        ys = [v.y for v in coords]
        zs = [v.z for v in coords]
        min_x, max_x = min(xs), max(xs)
        min_y, max_y = min(ys), max(ys)
        min_z, max_z = min(zs), max(zs)
        size_x = max(max_x - min_x, 1e-8)
        size_y = max(max_y - min_y, 1e-8)
        size_z = max(max_z - min_z, 1e-8)

        samples = []
        for i, v in enumerate(coords):
            nx = (v.x - min_x) / size_x
            ny = (v.y - min_y) / size_y
            nz = (v.z - min_z) / size_z

            weight = 0.0
            if nz > 0.55 and ny > 0.48:
                weight = 1.0
            if nz > 0.72 and ny > 0.58 and 0.20 < nx < 0.80:
                weight = 2.0
            if nz > 0.62 and ny > 0.52 and (nx < 0.16 or nx > 0.84):
                weight = max(weight, 1.2)

            if weight > 0.0 and (i % 3 == 0):
                samples.append((head.matrix_world @ v, weight))
        return samples
    finally:
        eval_obj.to_mesh_clear()

samples = collect_head_samples()
if not samples:
    raise RuntimeError('No scalp samples collected')

def evaluate_current():
    bpy.context.view_layer.update()
    tree = build_world_bvh(hair)
    weighted_sum = 0.0
    total_weight = 0.0
    gt_020 = 0
    gt_030 = 0
    gt_040 = 0
    max_d = 0.0
    for co, weight in samples:
        hit = tree.find_nearest(co)
        dist = 9.999 if hit is None else float(hit[3])
        weighted_sum += dist * weight
        total_weight += weight
        if dist > 0.020:
            gt_020 += 1
        if dist > 0.030:
            gt_030 += 1
        if dist > 0.040:
            gt_040 += 1
        max_d = max(max_d, dist)
    avg = weighted_sum / max(total_weight, 1e-8)
    n = len(samples)
    score = avg + (gt_020 / n) * 0.8 + (gt_030 / n) * 1.5 + (gt_040 / n) * 2.0 + max_d * 0.4
    return {
        'avg': avg,
        'gt_020': gt_020,
        'gt_030': gt_030,
        'gt_040': gt_040,
        'max': max_d,
        'count': n,
        'score': score,
    }

baseline = evaluate_current()

dy_values = [-0.004, 0.0, 0.004, 0.008, 0.012]
dz_values = [-0.010, -0.005, 0.0, 0.005]
scale_factors = [1.00, 1.04, 1.08]
rot_x_values = [-3.0, 0.0, 3.0]

best = {
    'metrics': baseline,
    'location': list(orig_loc),
    'rotation_deg': [math.degrees(orig_rot.x), math.degrees(orig_rot.y), math.degrees(orig_rot.z)],
    'scale': list(orig_scale),
    'delta': {'dy': 0.0, 'dz': 0.0, 'scale_factor': 1.0, 'rot_x_deg': 0.0},
}

for dy in dy_values:
    for dz in dz_values:
        for scale_factor in scale_factors:
            for rot_x_deg in rot_x_values:
                hair.location = orig_loc.copy()
                hair.rotation_euler = orig_rot.copy()
                hair.scale = orig_scale.copy()

                hair.location.y += dy
                hair.location.z += dz
                hair.scale *= scale_factor
                hair.rotation_euler.x += math.radians(rot_x_deg)
                bpy.context.view_layer.update()

                metrics = evaluate_current()
                if metrics['score'] < best['metrics']['score']:
                    best = {
                        'metrics': metrics,
                        'location': list(hair.location),
                        'rotation_deg': [
                            math.degrees(hair.rotation_euler.x),
                            math.degrees(hair.rotation_euler.y),
                            math.degrees(hair.rotation_euler.z),
                        ],
                        'scale': list(hair.scale),
                        'delta': {
                            'dy': dy,
                            'dz': dz,
                            'scale_factor': scale_factor,
                            'rot_x_deg': rot_x_deg,
                        },
                    }

hair.location = orig_loc.copy()
hair.rotation_euler = orig_rot.copy()
hair.scale = orig_scale.copy()
bpy.context.view_layer.update()

improved = (
    best['metrics']['score'] < baseline['score'] - 0.04 and
    best['metrics']['gt_030'] <= baseline['gt_030'] and
    best['metrics']['max'] <= baseline['max'] + 0.01
)

if improved:
    hair.location = orig_loc.copy()
    hair.rotation_euler = orig_rot.copy()
    hair.scale = orig_scale.copy()
    hair.location.y += best['delta']['dy']
    hair.location.z += best['delta']['dz']
    hair.scale *= best['delta']['scale_factor']
    hair.rotation_euler.x += math.radians(best['delta']['rot_x_deg'])
    bpy.context.view_layer.update()

result = {
    'baseline': baseline,
    'best': best,
    'applied': improved,
    'before': {
        'location': list(orig_loc),
        'rotation_deg': [math.degrees(orig_rot.x), math.degrees(orig_rot.y), math.degrees(orig_rot.z)],
        'scale': list(orig_scale),
    },
    'after': {
        'location': list(hair.location),
        'rotation_deg': [
            math.degrees(hair.rotation_euler.x),
            math.degrees(hair.rotation_euler.y),
            math.degrees(hair.rotation_euler.z),
        ],
        'scale': list(hair.scale),
    },
}

print(json.dumps(result, ensure_ascii=False, indent=2))
"""


async def main() -> None:
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
            result = await session.call_tool(
                "execute_blender_code",
                {
                    "code": BLENDER_CODE,
                },
            )
            text = None
            for item in result.content:
                if hasattr(item, "text"):
                    text = item.text
                    break
            if text is None:
                raise RuntimeError("No text result returned")
            prefix = "Code executed successfully: "
            if text.startswith(prefix):
                text = text[len(prefix):]
            OUT_PATH.parent.mkdir(parents=True, exist_ok=True)
            OUT_PATH.write_text(text, encoding="utf-8")
            print(str(OUT_PATH))


if __name__ == "__main__":
    anyio.run(main)
