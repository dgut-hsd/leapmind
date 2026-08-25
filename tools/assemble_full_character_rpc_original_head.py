import sys
from pathlib import Path

sys.path.insert(0, r"E:\goal\rust1\leapmind\leapmind\tools\blender-ai-mcp")

from server.adapters.rpc.client import RpcClient


SRC_DIR = Path(r"E:\人物一")
OUT_DIR = SRC_DIR / "组装输出"
OUT_DIR.mkdir(parents=True, exist_ok=True)

BODY_GLB = SRC_DIR / "身体.glb"
HEAD_GLB = SRC_DIR / "头.glb"
HAIR_GLB = SRC_DIR / "头发.glb"
CAPE_GLB = SRC_DIR / "皮风.glb"
SHOES_GLB = SRC_DIR / "鞋子.glb"

OUT_BLEND = OUT_DIR / "RPC_完整角色_原件重装配.blend"
OUT_GLB = OUT_DIR / "RPC_完整角色_原件重装配.glb"


def call(client, cmd, args=None, timeout=90):
    resp = client.send_request(cmd, args or {}, timeout_seconds=timeout)
    if resp.status != "ok":
        raise RuntimeError(f"{cmd} failed: {resp.error}")
    return resp.result


def scene_objects(client):
    return call(client, "scene.list_objects", {}, 30)


def mesh_names(client):
    return [obj["name"] for obj in scene_objects(client) if obj.get("type") == "MESH"]


def import_meshes(client, filepath, prefix):
    before = set(mesh_names(client))
    call(client, "import.glb", {"filepath": str(filepath), "import_pack_images": True, "merge_vertices": False}, 120)
    created = [name for name in mesh_names(client) if name not in before]
    renamed = []
    for index, old in enumerate(created):
        new = f"{prefix}_{index:02d}" if len(created) > 1 else prefix
        try:
            call(client, "scene.rename_object", {"old_name": old, "new_name": new}, 30)
            renamed.append(new)
        except Exception:
            renamed.append(old)
    return renamed


def bbox(client, name):
    return call(client, "scene.get_bounding_box", {"object_name": name, "world_space": True}, 30)


def union_bbox(client, names):
    boxes = [bbox(client, name) for name in names]
    mins = [min(box["min"][i] for box in boxes) for i in range(3)]
    maxs = [max(box["max"][i] for box in boxes) for i in range(3)]
    return {
        "min": mins,
        "max": maxs,
        "center": [(mins[i] + maxs[i]) / 2 for i in range(3)],
        "dimensions": [maxs[i] - mins[i] for i in range(3)],
    }


def inspect_scale(client, name):
    data = call(client, "scene.inspect_object", {"name": name}, 30)
    return data["scale"]


def transform_many(client, names, scale_map=None, delta=None):
    locations = {obj["name"]: obj["location"] for obj in scene_objects(client)}
    for name in names:
        args = {"name": name}
        if scale_map and name in scale_map:
            args["scale"] = scale_map[name]
        if delta:
            loc = locations[name]
            args["location"] = [loc[i] + delta[i] for i in range(3)]
        call(client, "modeling.transform_object", args, 30)


def scale_many_relative(client, names, factor):
    scale_map = {}
    for name in names:
        current = inspect_scale(client, name)
        scale_map[name] = [current[i] * factor for i in range(3)]
    transform_many(client, names, scale_map=scale_map)


def fit_single_module(client, names, target_center, target_height=None, target_width=None):
    box = union_bbox(client, names)
    factors = []
    if target_height:
        factors.append(target_height / max(box["dimensions"][2], 0.001))
    if target_width:
        factors.append(target_width / max(box["dimensions"][0], 0.001))
    if factors:
        scale_many_relative(client, names, min(factors))
    box = union_bbox(client, names)
    delta = [target_center[i] - box["center"][i] for i in range(3)]
    transform_many(client, names, delta=delta)
    return union_bbox(client, names)


def hide_if_exists(client, name):
    try:
        call(client, "scene.hide_object", {"object_name": name, "hide": True, "hide_render": True}, 20)
    except Exception:
        pass


def main():
    client = RpcClient("127.0.0.1", 8765, rpc_timeout_seconds=120, addon_execution_timeout_seconds=120)
    try:
        call(client, "system.new_file", {}, 60)
        for helper in ["Cube", "Light", "Camera"]:
            hide_if_exists(client, helper)

        body = import_meshes(client, BODY_GLB, "Character_Body")
        head = import_meshes(client, HEAD_GLB, "Character_Head")
        hair = import_meshes(client, HAIR_GLB, "Character_Hair")
        shoes = import_meshes(client, SHOES_GLB, "Character_Shoes")
        cape = import_meshes(client, CAPE_GLB, "Character_Cape")

        body_box = union_bbox(client, body)
        body_center = body_box["center"]
        body_dims = body_box["dimensions"]

        # Scale head to match the reference chibi ratio, then seat the collar on the torso neck.
        head_box = union_bbox(client, head)
        desired_head_height = body_dims[2] * 0.41
        head_factor = desired_head_height / max(head_box["dimensions"][2], 0.001)
        scale_many_relative(client, head, head_factor)
        head_box = union_bbox(client, head)
        desired_head_min_z = body_box["max"][2] - body_dims[2] * 0.18
        desired_head_center = [
            body_center[0],
            body_center[1] - body_dims[1] * 0.01,
            desired_head_min_z + head_box["dimensions"][2] / 2,
        ]
        transform_many(client, head, delta=[desired_head_center[i] - head_box["center"][i] for i in range(3)])

        # Fit the original hair over the skull so the bangs sit on the forehead instead of floating high.
        hair_box = union_bbox(client, hair)
        head_box = union_bbox(client, head)
        hair_factor = min(
            (head_box["dimensions"][0] * 1.16) / max(hair_box["dimensions"][0], 0.001),
            (head_box["dimensions"][2] * 1.06) / max(hair_box["dimensions"][2], 0.001),
        )
        scale_many_relative(client, hair, hair_factor)
        hair_box = union_bbox(client, hair)
        desired_hair_center = [
            head_box["center"][0],
            head_box["center"][1] - head_box["dimensions"][1] * 0.10,
            head_box["max"][2] - hair_box["dimensions"][2] * 0.40,
        ]
        transform_many(client, hair, delta=[desired_hair_center[i] - hair_box["center"][i] for i in range(3)])

        fit_single_module(
            client,
            shoes,
            [
                body_center[0],
                body_center[1] - body_dims[1] * 0.08,
                body_box["min"][2] + body_dims[2] * 0.150,
            ],
            target_height=body_dims[2] * 0.30,
            target_width=body_dims[0] * 1.30,
        )
        shoes_box = union_bbox(client, shoes)
        if shoes_box["min"][2] != 0:
            transform_many(client, shoes, delta=[0.0, 0.0, -shoes_box["min"][2]])
        fit_single_module(
            client,
            cape,
            [
                body_center[0],
                body_center[1] + body_dims[1] * 0.05,
                body_box["max"][2] - body_dims[2] * 0.23,
            ],
            target_height=body_dims[2] * 0.24,
            target_width=body_dims[0] * 1.58,
        )

        boxes = {
            "body": union_bbox(client, body),
            "head": union_bbox(client, head),
            "hair": union_bbox(client, hair),
            "cape": union_bbox(client, cape),
            "shoes": union_bbox(client, shoes),
        }
        call(client, "system.save_file", {"filepath": str(OUT_BLEND), "compress": True}, 120)
        call(
            client,
            "export.glb",
            {
                "filepath": str(OUT_GLB),
                "export_selected": False,
                "export_animations": False,
                "export_materials": True,
                "apply_modifiers": True,
            },
            180,
        )
        print(f"SAVED_BLEND={OUT_BLEND}")
        print(f"SAVED_GLB={OUT_GLB}")
        print(f"BODY={body}")
        print(f"HEAD={head}")
        print(f"HAIR={hair}")
        print(f"CAPE={cape}")
        print(f"SHOES={shoes}")
        print(f"BOXES={boxes}")
    finally:
        client.close()


if __name__ == "__main__":
    main()
