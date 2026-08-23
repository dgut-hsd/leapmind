from __future__ import annotations

import struct

from app.providers.three_d.base import ProviderStatus, ThreeDProvider
from app.providers.three_d.mock_provider import MockProvider


def test_mock_provider_implements_contract(tmp_path):
    provider = MockProvider()
    assert isinstance(provider, ThreeDProvider)
    image = tmp_path / "input.png"
    image.write_bytes(b"placeholder")

    task = provider.create_task([image], options={"target_polycount": 5000})
    assert task.status is ProviderStatus.succeeded
    assert task.progress == 100

    result = provider.download_result(task, tmp_path / "result")
    payload = result.model_path.read_bytes()
    magic, version, length = struct.unpack("<4sII", payload[:12])
    assert magic == b"glTF"
    assert version == 2
    assert length == len(payload)


def test_mock_provider_cancel():
    provider = MockProvider()
    provider.cancel_task("task-1")
    assert provider.get_task_status("task-1").status is ProviderStatus.cancelled

