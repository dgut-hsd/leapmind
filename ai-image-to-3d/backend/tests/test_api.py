from __future__ import annotations

import io

from PIL import Image


def png_bytes(size=(256, 512), color=(60, 120, 180, 255)) -> bytes:
    buffer = io.BytesIO()
    Image.new("RGBA", size, color).save(buffer, "PNG")
    return buffer.getvalue()


def test_health(client):
    response = client.get("/health")
    assert response.status_code == 200
    assert response.json() == {"status": "ok"}


def test_upload_generate_poll_and_download_mock_model(client):
    upload = client.post(
        "/api/images/upload?views=front",
        files={"files": ("subject.png", png_bytes(), "image/png")},
    )
    assert upload.status_code == 200, upload.text
    image = upload.json()[0]
    assert image["width"] == 1024
    assert image["height"] == 1024

    generated = client.post(
        "/api/models/generate",
        json={"image_ids": [image["id"]], "provider": "mock"},
    )
    assert generated.status_code == 202, generated.text
    model_id = generated.json()["id"]
    job_id = generated.json()["job_id"]

    job = client.get(f"/api/jobs/{job_id}")
    assert job.status_code == 200
    assert job.json()["status"] == "completed"
    assert job.json()["result"]["model_id"] == model_id

    model = client.get(f"/api/models/{model_id}")
    assert model.status_code == 200
    assert model.json()["status"] == "completed"
    assert model.json()["has_raw_model"] is True

    download = client.get(f"/api/models/{model_id}/download?variant=raw")
    assert download.status_code == 200
    assert download.content[:4] == b"glTF"


def test_upload_rejects_non_image(client):
    response = client.post(
        "/api/images/upload",
        files={"files": ("malware.png", b"not an image", "image/png")},
    )
    assert response.status_code == 400
    assert "valid image" in response.json()["detail"]


def test_generate_rejects_unknown_image(client):
    response = client.post(
        "/api/models/generate",
        json={"image_ids": ["missing"], "provider": "mock"},
    )
    assert response.status_code == 404

