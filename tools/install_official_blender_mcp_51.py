import addon_utils
import bpy
import os
import shutil
from datetime import datetime
from pathlib import Path


def log(message: str) -> None:
    print(f"[blender-mcp-setup] {message}")


def safe_disable(module_name: str) -> None:
    try:
        if module_name in bpy.context.preferences.addons:
            addon_utils.disable(module_name, default_set=True, handle_error=None)
            log(f"DISABLED={module_name}")
    except Exception as exc:
        log(f"DISABLE_FAILED={module_name}: {exc}")


def backup_path(src: Path, backup_root: Path) -> None:
    if not src.exists():
        return
    dst = backup_root / src.name
    if src.is_dir():
        shutil.copytree(src, dst, dirs_exist_ok=True)
    else:
        shutil.copy2(src, dst)
    log(f"BACKUP={src} -> {dst}")


def move_out_of_addons(src: Path, disabled_root: Path) -> None:
    if not src.exists():
        return
    dst = disabled_root / src.name
    if dst.exists():
        if dst.is_dir():
            shutil.rmtree(dst)
        else:
            dst.unlink()
    shutil.move(str(src), str(dst))
    log(f"DISABLED_ADDON_PATH={src} -> {dst}")


def main() -> None:
    addon_source = Path(os.environ["OFFICIAL_ADDON_SOURCE"]).resolve()
    target_module = os.environ.get("OFFICIAL_ADDON_MODULE", "blender_mcp")
    target_port = int(os.environ.get("BLENDER_MCP_PORT", "9876"))

    if not addon_source.exists():
        raise FileNotFoundError(f"Official addon source not found: {addon_source}")

    user_scripts_dir = Path(bpy.utils.user_resource("SCRIPTS", create=True))
    addons_dir = user_scripts_dir / "addons"
    addons_dir.mkdir(parents=True, exist_ok=True)

    config_dir = Path(bpy.utils.user_resource("CONFIG", create=True))
    timestamp = datetime.now().strftime("%Y%m%d_%H%M%S")
    backup_root = user_scripts_dir / "addons_backup" / f"blender_mcp_setup_{timestamp}"
    disabled_root = user_scripts_dir / "addons_disabled" / f"blender_mcp_setup_{timestamp}"
    backup_root.mkdir(parents=True, exist_ok=True)
    disabled_root.mkdir(parents=True, exist_ok=True)

    userpref_path = config_dir / "userpref.blend"
    backup_path(userpref_path, backup_root)

    known_modules = [
        "blender_mcp_addon",
        "addon",
        "blender_mcp",
        "blender_mcp_official",
        "blender_ai_mcp",
    ]
    for module_name in known_modules:
        safe_disable(module_name)

    removable_paths = [
        addons_dir / "blender_mcp_addon",
        addons_dir / "addon.py",
        addons_dir / "blender_mcp.py",
        addons_dir / "blender_mcp_official.py",
    ]
    for path in removable_paths:
        backup_path(path, backup_root)
        move_out_of_addons(path, disabled_root)

    target_path = addons_dir / f"{target_module}.py"
    shutil.copy2(addon_source, target_path)
    log(f"INSTALLED={addon_source} -> {target_path}")

    addon_utils.modules_refresh()
    enable_result = bpy.ops.preferences.addon_enable(module=target_module)
    log(f"ENABLE_RESULT={enable_result}")

    addon_prefs = bpy.context.preferences.addons[target_module].preferences
    addon_prefs.telemetry_consent = False

    scene = bpy.context.scene
    scene.blendermcp_port = target_port
    scene.blendermcp_auto_start_server = True
    scene.blendermcp_use_polyhaven = False
    scene.blendermcp_use_hyper3d = False
    scene.blendermcp_use_hunyuan3d = False
    scene.blendermcp_use_sketchfab = False

    bpy.ops.wm.save_userpref()

    log(f"TARGET_MODULE={target_module}")
    log(f"TELEMETRY={addon_prefs.telemetry_consent}")
    log(f"PORT={scene.blendermcp_port}")
    log(f"AUTOSTART={scene.blendermcp_auto_start_server}")
    log("USERPREF_SAVED=True")

    bpy.ops.wm.quit_blender()


if __name__ == "__main__":
    main()
