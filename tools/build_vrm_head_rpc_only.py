import shutil
import sys
from pathlib import Path


sys.path.insert(0, r"E:\goal\rust1\leapmind\leapmind\tools\blender-ai-mcp")

from server.adapters.rpc.client import RpcClient


SRC_DIR = Path(r"E:\人物一")
OUT_DIR = SRC_DIR / "组装输出"
OUT_DIR.mkdir(parents=True, exist_ok=True)

VRM_SRC = SRC_DIR / "AvatarSample_O.vrm"
VRM_GLTF_COPY = OUT_DIR / "_rpc_vrm_source_as_glb.glb"
TARGET_HAIR = SRC_DIR / "头发.glb"
OUT_BLEND = OUT_DIR / "RPC_VRM脸_目标头发_干净版.blend"
OUT_GLB = OUT_DIR / "RPC_VRM脸_目标头发_干净版.glb"


def box_center(box):
    return box["center"]


def box_dims(box):
    return box["dimensions"]


def call(client, cmd, args=None, timeout=60):
    resp = client.send_request(cmd, args or {}, timeout_seconds=timeout)
    if resp.status != "ok":
        raise RuntimeError(f"{cmd} failed: {resp.error}")
    return resp.result


def main():
    shutil.copyfile(VRM_SRC, VRM_GLTF_COPY)
    client = RpcClient("127.0.0.1", 8765, rpc_timeout_seconds=90, addon_execution_timeout_seconds=90)
    try:
        call(client, "system.new_file", {})
        # Remove startup cube after new file if present.
        for obj_name in ["Cube"]:
            try:
                call(client, "scene.delete_object", {"object_name": obj_name}, timeout=20)
            except Exception:
                pass

        call(client, "import.glb", {"filepath": str(VRM_GLTF_COPY), "import_pack_images": True, "merge_vertices": False})

        # Keep the clean VRM face only. Hide everything that would confuse head replacement.
        for obj_name in ["Body", "Hair", "Armature"]:
            try:
                call(client, "scene.hide_object", {"object_name": obj_name, "hide": True, "hide_render": True}, timeout=20)
            except Exception:
                pass
        for obj in call(client, "scene.list_objects", {}):
            name = obj.get("name", "")
            if name.startswith("J_Sec_") or name in ["棱角球", "�����"]:
                try:
                    call(client, "scene.hide_object", {"object_name": name, "hide": True, "hide_render": True}, timeout=20)
                except Exception:
                    pass

        face_box = call(client, "scene.get_bounding_box", {"object_name": "Face", "world_space": True}, timeout=20)
        face_center = box_center(face_box)
        face_dims = box_dims(face_box)

        before_names = {obj["name"] for obj in call(client, "scene.list_objects", {})}
        call(client, "import.glb", {"filepath": str(TARGET_HAIR), "import_pack_images": True, "merge_vertices": False}, timeout=90)
        after = call(client, "scene.list_objects", {})
        imported = [obj for obj in after if obj["name"] not in before_names and obj["type"] == "MESH"]
        if not imported:
            raise RuntimeError("Target hair import did not create a mesh")
        hair_name = imported[0]["name"]
        try:
            call(client, "scene.rename_object", {"old_name": hair_name, "new_name": "TargetCharacter_Hair"}, timeout=20)
            hair_name = "TargetCharacter_Hair"
        except Exception:
            pass

        hair_box = call(client, "scene.get_bounding_box", {"object_name": hair_name, "world_space": True}, timeout=20)
        hair_center = box_center(hair_box)
        hair_dims = box_dims(hair_box)

        # Fit generated target hair around the cleaner VRM face. Conservative scale
        # keeps the eyes readable and avoids the helmet-like overhang from earlier attempts.
        scale_factor = min(
            (face_dims[0] * 1.28) / max(hair_dims[0], 0.001),
            (face_dims[2] * 0.92) / max(hair_dims[2], 0.001),
        )
        desired_center = [
            face_center[0],
            face_center[1] + face_dims[1] * 0.10,
            face_center[2] + face_dims[2] * 0.27,
        ]
        # transform_object scale is object scale factor, then location. For this GLB
        # root mesh starts at scale 1, so the fit factor is direct.
        call(client, "modeling.transform_object", {"name": hair_name, "scale": [scale_factor, scale_factor, scale_factor]}, timeout=20)

        # Re-query after scale to compute final placement.
        hair_box2 = call(client, "scene.get_bounding_box", {"object_name": hair_name, "world_space": True}, timeout=20)
        hair_center2 = box_center(hair_box2)
        current_loc = next(obj["location"] for obj in call(client, "scene.list_objects", {}) if obj["name"] == hair_name)
        new_loc = [
            current_loc[0] + desired_center[0] - hair_center2[0],
            current_loc[1] + desired_center[1] - hair_center2[1],
            current_loc[2] + desired_center[2] - hair_center2[2],
        ]
        call(client, "modeling.transform_object", {"name": hair_name, "location": new_loc}, timeout=20)

        call(client, "system.save_file", {"filepath": str(OUT_BLEND), "compress": True}, timeout=90)
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
            timeout=120,
        )
        print(f"SAVED_BLEND={OUT_BLEND}")
        print(f"SAVED_GLB={OUT_GLB}")
        print(f"HAIR_OBJECT={hair_name}")
    finally:
        client.close()


if __name__ == "__main__":
    main()
