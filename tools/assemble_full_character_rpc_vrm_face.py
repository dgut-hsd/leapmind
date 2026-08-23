import shutil
import sys
from pathlib import Path


sys.path.insert(0, r"E:\goal\rust1\leapmind\leapmind\tools\blender-ai-mcp")

from server.adapters.rpc.client import RpcClient


SRC_DIR = Path(r"E:\人物一")
OUT_DIR = SRC_DIR / "组装输出"
OUT_DIR.mkdir(parents=True, exist_ok=True)

VRM_SRC = SRC_DIR / "AvatarSample_O.vrm"
VRM_GLTF_COPY = OUT_DIR / "_rpc_full_vrm_source_as_glb.glb"
HAIR_GLB = SRC_DIR / "头发.glb"
BODY_GLB = SRC_DIR / "身体.glb"
CAPE_GLB = SRC_DIR / "皮风.glb"
SHOES_GLB = SRC_DIR / "鞋子.glb"

OUT_BLEND = OUT_DIR / "RPC_完整角色_VRM脸版.blend"
OUT_GLB = OUT_DIR / "RPC_完整角色_VRM脸版.glb"


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
    shutil.copyfile(VRM_SRC, VRM_GLTF_COPY)
    client = RpcClient("127.0.0.1", 8765, rpc_timeout_seconds=120, addon_execution_timeout_seconds=120)
    try:
        call(client, "system.new_file", {}, 60)
        hide_if_exists(client, "Cube")

        body = import_meshes(client, BODY_GLB, "Character_Body")
        body_box = union_bbox(client, body)
        body_center = body_box["center"]
        body_dims = body_box["dimensions"]

        shoes = import_meshes(client, SHOES_GLB, "Character_Shoes")
        cape = import_meshes(client, CAPE_GLB, "Character_Cape")

        # Import VRM for the clean face only.
        before = set(mesh_names(client))
        call(client, "import.glb", {"filepath": str(VRM_GLTF_COPY), "import_pack_images": True, "merge_vertices": False}, 120)
        imported_vrm_meshes = [name for name in mesh_names(client) if name not in before]
        for name in imported_vrm_meshes:
            if name == "Face":
                call(client, "scene.rename_object", {"old_name": "Face", "new_name": "Character_Face"}, 30)
            elif name in ["Body", "Hair"] or "棱角球" in name or "�����" in name:
                hide_if_exists(client, name)
        hide_if_exists(client, "Armature")
        for obj in scene_objects(client):
            name = obj.get("name", "")
            if name.startswith("J_Sec_"):
                hide_if_exists(client, name)

        face = ["Character_Face"]
        face_box = union_bbox(client, face)

        # Import target character hair. We size and place everything from the face first,
        # because the VRM face origin sits far from the visible geometry.
        hair = import_meshes(client, HAIR_GLB, "Character_TargetHair")

        face_box = union_bbox(client, face)
        desired_face_height = body_dims[2] * 0.23
        face_factor = desired_face_height / max(face_box["dimensions"][2], 0.001)
        scale_many_relative(client, face + hair, face_factor)

        face_box = union_bbox(client, face)
        desired_face_center = [
            body_center[0],
            body_center[1] - body_dims[1] * 0.02,
            body_box["max"][2] - body_dims[2] * 0.11,
        ]
        transform_many(client, face, delta=[desired_face_center[i] - face_box["center"][i] for i in range(3)])

        face_box = union_bbox(client, face)
        hair_box = union_bbox(client, hair)
        hair_fit_factor = min(
            (face_box["dimensions"][0] * 1.30) / max(hair_box["dimensions"][0], 0.001),
            (face_box["dimensions"][2] * 1.05) / max(hair_box["dimensions"][2], 0.001),
        )
        scale_many_relative(client, hair, hair_fit_factor)
        hair_box = union_bbox(client, hair)
        desired_hair_center = [
            face_box["center"][0],
            face_box["center"][1] + face_box["dimensions"][1] * 0.06,
            face_box["max"][2] - hair_box["dimensions"][2] * 0.20,
        ]
        transform_many(client, hair, delta=[desired_hair_center[i] - hair_box["center"][i] for i in range(3)])

        head = face + hair

        # Fit shoes and cape after body ruler is stable.
        fit_single_module(
            client,
            shoes,
            [
                body_center[0],
                body_center[1] - body_dims[1] * 0.08,
                body_box["min"][2] + body_dims[2] * 0.145,
            ],
            target_height=body_dims[2] * 0.30,
            target_width=body_dims[0] * 1.30,
        )
        fit_single_module(
            client,
            cape,
            [
                body_center[0],
                body_center[1] - body_dims[1] * 0.05,
                body_box["max"][2] - body_dims[2] * 0.115,
            ],
            target_height=body_dims[2] * 0.27,
            target_width=body_dims[0] * 1.72,
        )

        # Hide startup helpers; leave real objects in scene.
        for helper in ["Cube", "Light", "Camera"]:
            hide_if_exists(client, helper)

        boxes = {
            "body": union_bbox(client, body),
            "head": union_bbox(client, head),
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
        print(f"HEAD={head}")
        print(f"BODY={body}")
        print(f"CAPE={cape}")
        print(f"SHOES={shoes}")
        print(f"BOXES={boxes}")
    finally:
        client.close()


if __name__ == "__main__":
    main()
