import bpy


def enable_addon(module_name):
    try:
        bpy.ops.preferences.addon_enable(module=module_name)
        return True, ""
    except Exception as exc:
        return False, str(exc)


def quit_blender():
    try:
        bpy.ops.wm.save_userpref()
    except Exception:
        pass
    bpy.ops.wm.quit_blender()
    return None


ok_mcp, err_mcp = enable_addon("blender_mcp_addon")
ok_rigify, err_rigify = enable_addon("rigify")

prefs = None
if ok_mcp:
    prefs = bpy.context.preferences.addons["blender_mcp_addon"].preferences
    prefs.host = "localhost"
    prefs.port = 9876
    prefs.auto_start = True

try:
    bpy.ops.wm.save_userpref()
    saved = True
except Exception as exc:
    saved = False
    print(f"SAVE_USERPREF_ERROR={exc}")

print(f"MCP_ENABLED={ok_mcp}")
print(f"MCP_ENABLE_ERROR={err_mcp}")
if prefs is not None:
    print(f"MCP_HOST={prefs.host}")
    print(f"MCP_PORT={prefs.port}")
    print(f"MCP_AUTOSTART={prefs.auto_start}")
print(f"RIGIFY_ENABLED={ok_rigify}")
print(f"RIGIFY_ENABLE_ERROR={err_rigify}")
print(f"USERPREF_SAVED={saved}")

bpy.app.timers.register(quit_blender, first_interval=2.0)
