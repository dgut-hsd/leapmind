import anyio
import json

from mcp.client.session import ClientSession
from mcp.client.stdio import StdioServerParameters, stdio_client


TARGETS = {
    "get_scene_info",
    "get_object_info",
    "get_viewport_screenshot",
    "execute_blender_code",
}


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
            await session.initialize()
            tools = await session.list_tools()
            for tool in tools.tools:
                if tool.name in TARGETS:
                    print(f"NAME={tool.name}")
                    print(json.dumps(tool.inputSchema, ensure_ascii=False))


anyio.run(main)
