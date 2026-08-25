import sys
from pathlib import Path


sys.path.insert(0, r"E:\goal\rust1\leapmind\leapmind\tools\blender-ai-mcp")

from server.adapters.rpc.client import RpcClient


SRC_DIR = Path(r"E:\人物一")
OUT_DIR = SRC_DIR / "组装输出"
HEAD_GLB = OUT_DIR / "RPC_VRM脸_目标头发_干净版.glb"
BODY_GLB = SRC_DIR / "身体.glb"
CAPE_GLB = SRC_DIR / "皮风.glb"
SHOES_GLB = SRC_DIR / "鞋子.glb"
OUT_BLEND = OUT_DIR / "RPC_完整角色_基础组装.blend"
OUT_GLB = OUT_DIR / "RPC_完整角色_基础组装.glb"


def call(client, cmd, args=None, timeout=90):
    resp = client.send_request(cmd, args or {}, timeout_seconds=timeout)
    if resp.status != "ok":
        raise RuntimeError(f"{cmd} failed: {resp.error}")
    return resp.result


def meshes(client):
    return [obj for obj in call(client, "scene.list_objects", {}, 30) if obj.get("type") == "MESH"]


def import_meshes(client, filepath, prefix):
    before = {obj["name"] for obj in meshes(client)}
    call(client, "import.glb", {"filepath": str(filepath), "import_pack_images": True, "merge_vertices": False}, 120)
    created = [obj for obj in meshes(client) if obj["name"] not in before]
    renamed = []
    for index, obj in enumerate(created):
        old = obj["name"]
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
    center = [(mins[i] + maxs[i]) / 2 for i in range(3)]
    dims = [maxs[i] - mins[i] for i in range(3)]
    return {"min": mins, "max": maxs, "center": center, "dimensions": dims}


def transform_many(client, names, scale=None, delta=None):
    current = {obj["name"]: obj["location"] for obj in call(client, "scene.list_objects", {}, 30)}
    for name in names:
        args = {"name": name}
        if scale is not None:
            args["scale"] = scale
        if delta is not None:
            loc = current.get(name, [0, 0, 0])
            args["location"] = [loc[i] + delta[i] for i in range(3)]
        call(client, "modeling.transform_object", args, 30)


def fit_module_to_box(client, names, target_center, target_height=None, target_width=None, y_offset=0.0):
    box = union_bbox(client, names)
    factors = []
    if target_height:
        factors.append(target_height / max(box["dimensions"][2], 0.001))
    if target_width:
        factors.append(target_width / max(box["dimensions"][0], 0.001))
    if factors:
        factor = min(factors)
        transform_many(client, names, scale=[factor, factor, factor])
        box = union_bbox(client, names)
    else:
        factor = 1.0
    desired = [target_center[0], target_center[1] + y_offset, target_center[2]]
    delta = [desired[i] - box["center"][i] for i in range(3)]
    transform_many(client, names, delta=delta)
    return factor, union_bbox(client, names)


def main():
    client = RpcClient("127.0.0.1", 8765, rpc_timeout_seconds=120, addon_execution_timeout_seconds=120)
    try:
        call(client, "system.new_file", {}, 60)
        for startup in ["Cube"]:
            try:
                call(client, "scene.delete_object", {"object_name": startup}, 30)
            except Exception:
                pass

        body = import_meshes(client, BODY_GLB, "Character_Body")
        body_box = union_bbox(client, body)
        body_height = body_box["dimensions"][2]
        body_center = body_box["center"]

        shoes = import_meshes(client, SHOES_GLB, "Character_Shoes")
        cape = import_meshes(client, CAPE_GLB, "Character_Cape")
        head = import_meshes(client, HEAD_GLB, "Character_Head")

        # The generated component files all use local bottom-at-zero coordinates,
        # except the VRM head module. Use body as the ruler.
        body_box = union_bbox(client, body)
        body_height = body_box["dimensions"][2]
        body_center = body_box["center"]

        # Shoes sit at the bottom of the body. Keep them slightly in front.
        shoes_target_height = body_height * 0.30
        shoes_factor, shoes_box = fit_module_to_box(
            client,
            shoes,
            target_center=[body_center[0], body_center[1] - body_box["dimensions"][1] * 0.08, body_box["min"][2] + shoes_target_height / 2],
            target_height=shoes_target_height,
            target_width=body_box["dimensions"][0] * 1.30,
        )

        # Cape/shoulder piece wraps around the neck and shoulders.
        cape_target_height = body_height * 0.27
        cape_factor, cape_box = fit_module_to_box(
            client,
            cape,
            target_center=[
                body_center[0],
                body_center[1] - body_box["dimensions"][1] * 0.05,
                body_box["max"][2] - cape_target_height * 0.42,
            ],
            target_height=cape_target_height,
            target_width=body_box["dimensions"][0] * 1.72,
        )

        # Head module: scale to a stylized large head, attach chin/neck to body collar.
        head_box = union_bbox(client, head)
        head_target_height = body_height * 0.39
        head_factor = head_target_height / max(head_box["dimensions"][2], 0.001)
        transform_many(client, head, scale=[head_factor, head_factor, head_factor])
        head_box = union_bbox(client, head)
        desired_head_min_z = body_box["max"][2] - body_height * 0.035
        desired_head_center = [
            body_center[0],
            body_center[1] - body_box["dimensions"][1] * 0.05,
            desired_head_min_z + head_box["dimensions"][2] / 2,
        ]
        delta = [desired_head_center[i] - head_box["center"][i] for i in range(3)]
        transform_many(client, head, delta=delta)

        # Hide startup/default helpers if they came along from imported head module.
        for helper in ["Cube", "Light", "Camera"]:
            try:
                call(client, "scene.hide_object", {"object_name": helper, "hide": True, "hide_render": True}, 20)
            except Exception:
                pass

        final_objects = call(client, "scene.list_objects", {}, 30)
        final_boxes = {
            "body": union_bbox(client, body),
            "shoes": union_bbox(client, shoes),
            "cape": union_bbox(client, cape),
            "head": union_bbox(client, head),
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
        print(f"CAPE={cape}")
        print(f"SHOES={shoes}")
        print(f"BOXES={final_boxes}")
        print(f"OBJECT_COUNT={len(final_objects)}")
    finally:
        client.close()


if __name__ == "__main__":
    main()
