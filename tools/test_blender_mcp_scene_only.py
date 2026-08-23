import anyio
import json

from mcp.client.session import ClientSession
from mcp.client.stdio import StdioServerParameters, stdio_client


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
            result = await session.call_tool("get_scene_info", {"user_prompt": "Read the current scene and its objects."})
            for item in result.content:
                if hasattr(item, "text"):
                    print(item.text[:4000])
                else:
                    print(repr(item))


anyio.run(main)
