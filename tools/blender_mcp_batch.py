import anyio
import base64
import json
import os
import sys
from pathlib import Path

from mcp.client.session import ClientSession
from mcp.client.stdio import StdioServerParameters, stdio_client


def ensure_parent(path: Path) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)


def decode_image_data(data):
    if isinstance(data, bytes):
        return data
    if isinstance(data, str):
        try:
            return base64.b64decode(data)
        except Exception:
            return data.encode("utf-8")
    raise TypeError(f"Unsupported image data type: {type(data)!r}")


def summarize_item(item):
    entry = {"type": getattr(item, "type", type(item).__name__)}
    if hasattr(item, "text"):
        entry["text"] = item.text
    if hasattr(item, "mimeType"):
        entry["mimeType"] = item.mimeType
    if hasattr(item, "data"):
        entry["data_length"] = len(item.data)
    return entry


def get_server_meta(init_result):
    info = getattr(init_result, "server_info", None)
    if info is None:
        info = getattr(init_result, "serverInfo", None)
    return {
        "name": getattr(info, "name", None),
        "version": getattr(info, "version", None),
    }


async def main() -> None:
    if len(sys.argv) != 3:
        raise SystemExit("Usage: blender_mcp_batch.py <request.json> <result.json>")

    request_path = Path(sys.argv[1]).resolve()
    result_path = Path(sys.argv[2]).resolve()
    request = json.loads(request_path.read_text(encoding="utf-8"))

    params = StdioServerParameters(
        command=request.get("command", r"C:\Users\abcd1\.local\bin\uvx.exe"),
        args=request.get("args", ["--python", "3.11", "blender-mcp"]),
        env={
            "UV_PYTHON_PREFERENCE": "only-managed",
            "BLENDER_HOST": request.get("host", "localhost"),
            "BLENDER_PORT": str(request.get("port", 9876)),
            "DISABLE_TELEMETRY": "true",
        },
    )

    output = {"actions": []}

    async with stdio_client(params) as (read_stream, write_stream):
        async with ClientSession(read_stream, write_stream) as session:
            init = await session.initialize()
            output["server"] = get_server_meta(init)

            for action in request["actions"]:
                tool_name = action["tool"]
                args = action.get("args", {})
                result = await session.call_tool(tool_name, args)
                entry = {
                    "label": action.get("label", tool_name),
                    "tool": tool_name,
                    "args": args,
                    "content": [],
                }

                for item in result.content:
                    item_summary = summarize_item(item)
                    if hasattr(item, "data") and action.get("save_image_to"):
                        image_path = Path(action["save_image_to"]).resolve()
                        ensure_parent(image_path)
                        image_path.write_bytes(decode_image_data(item.data))
                        item_summary["saved_to"] = str(image_path)
                    if hasattr(item, "text") and action.get("save_text_to"):
                        text_path = Path(action["save_text_to"]).resolve()
                        ensure_parent(text_path)
                        text_path.write_text(item.text, encoding="utf-8")
                        item_summary["saved_to"] = str(text_path)
                    entry["content"].append(item_summary)

                output["actions"].append(entry)

    ensure_parent(result_path)
    result_path.write_text(json.dumps(output, ensure_ascii=False, indent=2), encoding="utf-8")
    print(str(result_path))


if __name__ == "__main__":
    anyio.run(main)
