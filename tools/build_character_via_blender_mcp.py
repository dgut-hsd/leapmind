import math
import sys
from pathlib import Path

sys.path.insert(0, r"E:\goal\rust1\leapmind\leapmind\tools\blender-ai-mcp")

from server.adapters.rpc.client import RpcClient


OUT_BLEND = r"E:\Mycode1\character_mcp_blockout.blend"
OUT_GLB = r"E:\Mycode1\character_mcp_blockout.glb"


client = RpcClient("127.0.0.1", 8765, rpc_timeout_seconds=45, addon_execution_timeout_seconds=45)


def call(cmd, args=None):
    response = client.send_request(cmd, args or {}, timeout_seconds=30)
    if response.status != "ok":
        raise RuntimeError(f"{cmd} failed: {response.error}")
    return response.result


def mat(name, color, metallic=0.0, roughness=0.55, alpha=1.0, emission=None, strength=0.0):
    args = {
        "name": name,
        "base_color": color,
        "metallic": metallic,
        "roughness": roughness,
        "alpha": alpha,
    }
    if emission:
        args["emission_color"] = emission
        args["emission_strength"] = strength
    call("material.create", args)


def primitive(kind, name, loc, scale, material, radius=1.0, size=1.0, rot=(0, 0, 0), modifier=None):
    call(
        "modeling.create_primitive",
        {
            "primitive_type": kind,
            "name": name,
            "location": loc,
            "rotation": rot,
            "radius": radius,
            "size": size,
        },
    )
    call("modeling.transform_object", {"name": name, "scale": scale})
    call("material.assign", {"object_name": name, "material_name": material})
    if modifier:
        call("modeling.add_modifier", {"name": name, "modifier_type": modifier, "properties": {}})
    return name


def sphere(name, loc, scale, material):
    return primitive("sphere", name, loc, scale, material, radius=1.0, size=1.0, modifier="SUBSURF")


def cube(name, loc, scale, material, rot=(0, 0, 0), bevel=True):
    obj = primitive("cube", name, loc, scale, material, size=1.0, rot=rot)
    if bevel:
        call("modeling.add_modifier", {"name": name, "modifier_type": "BEVEL", "properties": {"width": 0.035, "segments": 3}})
    return obj


def cylinder(name, loc, scale, material, rot=(0, 0, 0), bevel=True):
    obj = primitive("cylinder", name, loc, scale, material, radius=1.0, size=1.0, rot=rot)
    if bevel:
        call("modeling.add_modifier", {"name": name, "modifier_type": "BEVEL", "properties": {"width": 0.018, "segments": 2}})
    return obj


def cone(name, loc, scale, material, rot=(0, 0, 0)):
    return primitive("cone", name, loc, scale, material, radius=1.0, size=1.0, rot=rot)


def torus(name, loc, scale, material, rot=(0, 0, 0)):
    return primitive("torus", name, loc, scale, material, size=1.0, rot=rot)


def main():
    call("scene.clean_scene")

    mat("mat_skin", [1.0, 0.78, 0.68, 1], roughness=0.62)
    mat("mat_hair_lavender", [0.62, 0.50, 0.95, 1], roughness=0.72)
    mat("mat_hair_shadow", [0.36, 0.29, 0.70, 1], roughness=0.8)
    mat("mat_eye_gold", [1.0, 0.62, 0.08, 1], roughness=0.25, emission=[1.0, 0.55, 0.05], strength=0.25)
    mat("mat_eye_cyan", [0.0, 0.82, 1.0, 1], roughness=0.25, emission=[0.0, 0.75, 1.0], strength=0.25)
    mat("mat_black", [0.02, 0.022, 0.03, 1], roughness=0.5)
    mat("mat_stocking", [0.05, 0.045, 0.05, 0.72], roughness=0.7, alpha=0.72)
    mat("mat_white_cloth", [0.96, 0.91, 0.82, 1], roughness=0.82)
    mat("mat_purple_cape", [0.28, 0.16, 0.62, 1], roughness=0.72)
    mat("mat_teal_cape", [0.02, 0.55, 0.62, 1], roughness=0.7)
    mat("mat_gold", [1.0, 0.64, 0.18, 1], metallic=0.65, roughness=0.28)
    mat("mat_gem_teal", [0.0, 0.82, 0.86, 1], roughness=0.18, emission=[0, 0.55, 0.7], strength=0.2)
    mat("mat_gem_purple", [0.50, 0.18, 0.95, 1], roughness=0.18, emission=[0.35, 0.05, 0.75], strength=0.2)
    mat("mat_book", [0.05, 0.52, 0.60, 1], roughness=0.55)

    # Head and face group.
    sphere("head_face_main", (0, -0.02, 3.10), (0.42, 0.36, 0.50), "mat_skin")
    sphere("cheek_soft_left", (-0.22, -0.33, 3.04), (0.075, 0.026, 0.055), "mat_skin")
    sphere("cheek_soft_right", (0.22, -0.33, 3.04), (0.075, 0.026, 0.055), "mat_skin")
    cylinder("neck", (0, 0, 2.50), (0.13, 0.13, 0.30), "mat_skin")
    cylinder("black_high_collar", (0, 0, 2.43), (0.19, 0.19, 0.18), "mat_black")
    cone("elf_ear_left", (-0.44, -0.03, 3.10), (0.18, 0.07, 0.31), "mat_skin", rot=(0, -math.pi / 2, 0))
    cone("elf_ear_right", (0.44, -0.03, 3.10), (0.18, 0.07, 0.31), "mat_skin", rot=(0, math.pi / 2, 0))

    # Eyes and expression.
    sphere("eye_left_gold", (-0.145, -0.365, 3.13), (0.075, 0.018, 0.052), "mat_eye_gold")
    sphere("eye_right_cyan", (0.145, -0.365, 3.13), (0.075, 0.018, 0.052), "mat_eye_cyan")
    cube("left_upper_lash", (-0.145, -0.382, 3.20), (0.105, 0.011, 0.012), "mat_black", rot=(0, 0, -0.10))
    cube("right_upper_lash", (0.145, -0.382, 3.20), (0.105, 0.011, 0.012), "mat_black", rot=(0, 0, 0.10))
    cube("smirk_mouth", (0.02, -0.386, 2.94), (0.105, 0.010, 0.010), "mat_black", rot=(0, 0, -0.08))

    # Hair mass and layered locks.
    sphere("hair_cap_lavender", (0, 0.005, 3.26), (0.49, 0.42, 0.43), "mat_hair_lavender")
    sphere("back_hair_volume", (0, 0.22, 3.02), (0.50, 0.31, 0.37), "mat_hair_shadow")
    cone("front_bang_center", (0.0, -0.34, 3.16), (0.12, 0.07, 0.48), "mat_hair_lavender", rot=(math.pi, 0, 0))
    cone("front_bang_left", (-0.16, -0.33, 3.12), (0.105, 0.06, 0.42), "mat_hair_lavender", rot=(math.pi, 0.18, -0.16))
    cone("front_bang_right", (0.16, -0.33, 3.12), (0.105, 0.06, 0.42), "mat_hair_lavender", rot=(math.pi, -0.18, 0.16))
    cone("side_lock_left", (-0.34, -0.17, 2.95), (0.10, 0.07, 0.42), "mat_hair_lavender", rot=(math.pi * 0.90, 0.18, -0.35))
    cone("side_lock_right", (0.34, -0.17, 2.95), (0.10, 0.07, 0.42), "mat_hair_lavender", rot=(math.pi * 0.90, -0.18, 0.35))
    cone("horn_hair_left_outer", (-0.36, 0.00, 3.55), (0.12, 0.07, 0.47), "mat_hair_lavender", rot=(-0.65, -0.52, -0.72))
    cone("horn_hair_right_outer", (0.36, 0.00, 3.55), (0.12, 0.07, 0.47), "mat_hair_lavender", rot=(-0.65, 0.52, 0.72))
    cone("horn_hair_left_inner", (-0.23, -0.02, 3.51), (0.09, 0.055, 0.36), "mat_hair_lavender", rot=(-0.62, -0.35, -0.50))
    cone("horn_hair_right_inner", (0.23, -0.02, 3.51), (0.09, 0.055, 0.36), "mat_hair_lavender", rot=(-0.62, 0.35, 0.50))
    cone("top_ahoge", (0.03, -0.01, 3.69), (0.055, 0.035, 0.32), "mat_hair_lavender", rot=(-0.62, 0.20, 0.05))

    # Torso, cape, arms.
    cube("upper_white_tunic", (0, -0.01, 1.92), (0.36, 0.20, 0.52), "mat_white_cloth")
    cube("front_tunic_panel", (0, -0.23, 1.70), (0.23, 0.025, 0.45), "mat_white_cloth")
    cube("cape_left_teal_panel", (-0.23, -0.03, 2.33), (0.29, 0.045, 0.28), "mat_teal_cape", rot=(0.08, 0.00, 0.22))
    cube("cape_right_purple_panel", (0.23, -0.03, 2.33), (0.29, 0.045, 0.28), "mat_purple_cape", rot=(0.08, 0.00, -0.22))
    cube("cape_back_split", (0, 0.22, 2.28), (0.48, 0.04, 0.30), "mat_purple_cape")
    cylinder("left_wide_sleeve", (-0.50, -0.02, 1.80), (0.13, 0.13, 0.52), "mat_white_cloth", rot=(0.28, 0.25, -0.22))
    cylinder("right_wide_sleeve", (0.50, -0.02, 1.80), (0.13, 0.13, 0.52), "mat_white_cloth", rot=(0.28, -0.25, 0.22))
    sphere("left_hand_simple", (-0.64, -0.10, 1.30), (0.10, 0.055, 0.075), "mat_skin")
    sphere("right_hand_simple", (0.64, -0.10, 1.30), (0.10, 0.055, 0.075), "mat_skin")

    # Lower body.
    cube("black_short_pants", (0, -0.02, 1.25), (0.34, 0.20, 0.19), "mat_black")
    cylinder("left_stocking_leg", (-0.17, 0, 0.70), (0.105, 0.105, 0.62), "mat_stocking")
    cylinder("right_stocking_leg", (0.17, 0, 0.70), (0.105, 0.105, 0.62), "mat_stocking")
    cube("left_boot_body", (-0.17, -0.02, 0.15), (0.14, 0.18, 0.22), "mat_black")
    cube("right_boot_body", (0.17, -0.02, 0.15), (0.14, 0.18, 0.22), "mat_black")
    cube("left_boot_toe", (-0.17, -0.14, -0.04), (0.16, 0.22, 0.07), "mat_black")
    cube("right_boot_toe", (0.17, -0.14, -0.04), (0.16, 0.22, 0.07), "mat_black")
    cylinder("left_boot_gold_cuff", (-0.17, 0, 0.39), (0.15, 0.15, 0.035), "mat_gold")
    cylinder("right_boot_gold_cuff", (0.17, 0, 0.39), (0.15, 0.15, 0.035), "mat_gold")
    cone("left_boot_purple_gem", (-0.17, -0.205, 0.20), (0.055, 0.020, 0.12), "mat_gem_purple", rot=(math.pi / 2, 0, 0))
    cone("right_boot_purple_gem", (0.17, -0.205, 0.20), (0.055, 0.020, 0.12), "mat_gem_purple", rot=(math.pi / 2, 0, 0))

    # Gold trims and accessories.
    for i, x in enumerate([-0.24, -0.12, 0.0, 0.12, 0.24]):
        torus(f"chest_chain_ring_{i}", (x, -0.285, 2.20 - abs(x) * 0.18), (0.038, 0.038, 0.010), "mat_gold", rot=(math.pi / 2, 0, 0))
    torus("left_shoulder_gold_ring", (-0.38, -0.16, 2.27), (0.055, 0.055, 0.012), "mat_gold", rot=(math.pi / 2, 0, 0))
    torus("right_shoulder_gold_ring", (0.38, -0.16, 2.27), (0.055, 0.055, 0.012), "mat_gold", rot=(math.pi / 2, 0, 0))
    cone("teal_chest_pendant", (0.26, -0.31, 2.08), (0.055, 0.025, 0.13), "mat_gem_teal", rot=(math.pi, 0, 0))
    cube("side_book_teal", (0.39, -0.16, 1.43), (0.09, 0.035, 0.22), "mat_book", rot=(0, 0, -0.08))
    cube("side_book_gold_frame", (0.39, -0.185, 1.43), (0.105, 0.018, 0.24), "mat_gold", rot=(0, 0, -0.08))
    cone("waist_teal_drop_gem", (0.43, -0.19, 1.00), (0.045, 0.02, 0.13), "mat_gem_teal", rot=(math.pi, 0, 0))
    cube("left_thigh_gold_diamond", (-0.17, -0.15, 0.83), (0.055, 0.012, 0.085), "mat_gold", rot=(0, 0, 0.78))
    cube("right_thigh_gold_diamond", (0.17, -0.15, 0.83), (0.055, 0.012, 0.085), "mat_gold", rot=(0, 0, 0.78))

    # Review lights/camera using MCP scene/system handlers.
    call("scene.create_light", {"light_type": "AREA", "location": [0, -3.2, 4.2], "energy": 550})
    call("scene.create_light", {"light_type": "POINT", "location": [-2.0, 1.8, 3.2], "energy": 90})
    call("scene.create_camera", {"location": [0, -5.2, 2.15], "rotation": [math.radians(72), 0, 0]})

    call("system.save_file", {"filepath": OUT_BLEND, "compress": True})
    call("export.glb", {"filepath": OUT_GLB, "export_selected": False, "export_animations": False, "export_materials": True})

    objects = call("scene.list_objects")
    print(f"Created {len(objects)} objects")
    print(OUT_BLEND)
    print(OUT_GLB)


if __name__ == "__main__":
    try:
        main()
    finally:
        client.close()
