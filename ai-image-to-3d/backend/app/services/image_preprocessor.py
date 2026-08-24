from __future__ import annotations

import io
from dataclasses import dataclass
from pathlib import Path

from PIL import Image, ImageChops, ImageOps, UnidentifiedImageError


ALLOWED_FORMATS = {"PNG": "image/png", "JPEG": "image/jpeg", "WEBP": "image/webp"}


class ImageValidationError(ValueError):
    pass


@dataclass(slots=True)
class ProcessedImage:
    path: Path
    mime_type: str
    width: int
    height: int


class ImagePreprocessor:
    def __init__(
        self,
        *,
        max_bytes: int,
        target_size: int,
        remove_background: bool,
        min_dimension: int = 64,
        max_dimension: int = 8192,
    ) -> None:
        self.max_bytes = max_bytes
        self.target_size = target_size
        self.remove_background = remove_background
        self.min_dimension = min_dimension
        self.max_dimension = max_dimension

    def process_bytes(self, data: bytes, destination: Path) -> ProcessedImage:
        if not data:
            raise ImageValidationError("The uploaded file is empty")
        if len(data) > self.max_bytes:
            raise ImageValidationError(
                f"Image exceeds the {self.max_bytes} byte upload limit"
            )
        try:
            with Image.open(io.BytesIO(data)) as probe:
                detected = probe.format
                probe.verify()
            if detected not in ALLOWED_FORMATS:
                raise ImageValidationError("Only PNG, JPEG and WebP are supported")
            image = Image.open(io.BytesIO(data))
            image = ImageOps.exif_transpose(image)
            if min(image.size) < self.min_dimension or max(image.size) > self.max_dimension:
                raise ImageValidationError(
                    f"Image dimensions must be between {self.min_dimension} and "
                    f"{self.max_dimension} pixels"
                )
            image = image.convert("RGBA")
        except (UnidentifiedImageError, OSError) as exc:
            raise ImageValidationError("The file is not a valid image") from exc

        if self.remove_background:
            image = self._remove_background(image)

        bbox = self._content_bbox(image)
        if bbox:
            image = image.crop(bbox)
        image.thumbnail((int(self.target_size * 0.9), int(self.target_size * 0.9)))
        canvas = Image.new("RGBA", (self.target_size, self.target_size), (0, 0, 0, 0))
        offset = (
            (self.target_size - image.width) // 2,
            (self.target_size - image.height) // 2,
        )
        canvas.alpha_composite(image, offset)
        destination.parent.mkdir(parents=True, exist_ok=True)
        canvas.save(destination, format="PNG", optimize=True)
        return ProcessedImage(
            path=destination,
            mime_type="image/png",
            width=canvas.width,
            height=canvas.height,
        )

    def _remove_background(self, image: Image.Image) -> Image.Image:
        try:
            from rembg import remove
        except ImportError as exc:
            raise RuntimeError(
                "Background removal is enabled but rembg is not installed"
            ) from exc
        output = remove(image)
        return output if isinstance(output, Image.Image) else Image.open(io.BytesIO(output))

    @staticmethod
    def _content_bbox(image: Image.Image):
        alpha_bbox = image.getchannel("A").getbbox()
        if alpha_bbox and alpha_bbox != (0, 0, image.width, image.height):
            return alpha_bbox
        background = Image.new("RGBA", image.size, image.getpixel((0, 0)))
        diff = ImageChops.difference(image, background).convert("L")
        return diff.point(lambda p: 255 if p > 12 else 0).getbbox()

