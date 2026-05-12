"""PDF analysis, page classification, Tesseract OCR, and Ollama VLM client."""

import base64
import io
import logging
import time
from dataclasses import dataclass, field
from typing import Callable

import fitz  # PyMuPDF
import httpx
import pytesseract
from PIL import Image

# Allow large images from high-DPI page rendering (300 DPI A4 ≈ 8.7M px, safe)
Image.MAX_IMAGE_PIXELS = 100_000_000  # 100M pixels (2x safety margin over _MAX_RENDER_PIXELS)

import config

logger = logging.getLogger(__name__)


def _ollama_headers() -> dict[str, str]:
    """Build HTTP headers for Ollama API requests (JWT auth if configured)."""
    headers: dict[str, str] = {}
    if config.OLLAMA_AUTH_TOKEN:
        headers["Authorization"] = f"Bearer {config.OLLAMA_AUTH_TOKEN}"
    return headers


# A4 page area in points (595 x 842)
_A4_AREA = 595 * 842
_EXPECTED_CHARS_PER_A4 = 2000


@dataclass
class PageAnalysis:
    """Result of analyzing a single PDF page before processing."""

    page_num: int
    scan_type: str  # "digital" / "scanned" / "partial_ocr"
    complexity: str  # "simple_text" / "table" / "mixed" / "formatted_text" / "image_only"
    char_count: int
    expected_chars: float
    text_ratio: float
    image_coverage: float
    has_tables: bool
    ocr_engine: str | None = None  # "tesseract" / "vlm" / None


@dataclass
class DocumentAnalysis:
    """Aggregated analysis of an entire PDF document."""

    file_path: str
    total_pages: int
    is_encrypted: bool
    needs_password: bool
    pages: list[PageAnalysis] = field(default_factory=list)

    @property
    def pages_needing_ocr(self) -> list[int]:
        return [p.page_num for p in self.pages if p.scan_type == "scanned"]

    @property
    def pages_with_tables(self) -> list[int]:
        return [p.page_num for p in self.pages if p.has_tables]

    @property
    def ocr_strategy(self) -> str:
        if not self.pages_needing_ocr:
            return "all_digital"
        if len(self.pages_needing_ocr) == self.total_pages:
            return "all_ocr"
        return "hybrid"


@dataclass
class OCRResult:
    """Result of OCR processing for a single page."""

    page_num: int
    text: str
    engine: str  # "tesseract" / "vlm" / "unstructured"
    success: bool
    error: str | None = None
    processing_time_ms: int = 0


@dataclass
class IngestResult:
    """Extended ingest result with OCR statistics."""

    filename: str
    total_chunks: int = 0
    new_chunks: int = 0
    skipped_chunks: int = 0
    orphaned_deleted: int = 0
    ocr_pages: int = 0
    vlm_pages: int = 0
    warnings: list[str] = field(default_factory=list)
    errors: list[str] = field(default_factory=list)
    success: bool = True


# --------------- PDF Analysis ---------------


def analyze_pdf(file_path: str) -> DocumentAnalysis:
    """Analyze a PDF document: check encryption, classify each page.

    Returns DocumentAnalysis with per-page scan type and complexity.
    Raises ValueError if PDF is encrypted and needs a password.
    """
    doc = fitz.open(file_path)
    analysis = DocumentAnalysis(
        file_path=file_path,
        total_pages=len(doc),
        is_encrypted=doc.is_encrypted,
        needs_password=doc.needs_pass,
    )

    if doc.needs_pass:
        doc.close()
        raise ValueError("PDF защищён паролем. Загрузите незашифрованный документ.")

    for page_num in range(len(doc)):
        page = doc[page_num]
        page_analysis = _analyze_page(page, page_num + 1)
        analysis.pages.append(page_analysis)

    doc.close()
    logger.info(
        "PDF analysis: %s — %d pages, strategy=%s, ocr_needed=%d, tables=%d",
        file_path, analysis.total_pages, analysis.ocr_strategy,
        len(analysis.pages_needing_ocr), len(analysis.pages_with_tables),
    )
    return analysis


def _analyze_page(page: fitz.Page, page_num: int) -> PageAnalysis:
    """Classify a single page: scan type and content complexity."""
    scan_type = classify_scan_type(page)
    complexity = classify_complexity(page)
    char_count = len(page.get_text("text").strip())
    page_area = page.rect.width * page.rect.height
    expected_chars = _EXPECTED_CHARS_PER_A4 * (page_area / _A4_AREA) if _A4_AREA > 0 else _EXPECTED_CHARS_PER_A4
    text_ratio = char_count / expected_chars if expected_chars > 0 else 0.0
    image_coverage = _compute_image_coverage(page)
    has_tables = complexity == "table"

    # Determine OCR engine
    ocr_engine: str | None = None
    if scan_type == "scanned":
        ocr_engine = "vlm" if complexity in ("table", "mixed", "formatted_text") else "tesseract"

    return PageAnalysis(
        page_num=page_num,
        scan_type=scan_type,
        complexity=complexity,
        char_count=char_count,
        expected_chars=expected_chars,
        text_ratio=text_ratio,
        image_coverage=image_coverage,
        has_tables=has_tables,
        ocr_engine=ocr_engine,
    )


def classify_scan_type(page: fitz.Page) -> str:
    """Classify page as digital or scanned based on text density.

    If extracted text covers less than SCAN_TEXT_THRESHOLD of expected chars,
    the page is considered scanned and needs OCR.
    """
    text = page.get_text("text").strip()
    char_count = len(text)
    page_area = page.rect.width * page.rect.height
    expected = _EXPECTED_CHARS_PER_A4 * (page_area / _A4_AREA) if _A4_AREA > 0 else _EXPECTED_CHARS_PER_A4

    if expected <= 0:
        return "digital"

    ratio = char_count / expected
    return "digital" if ratio >= config.SCAN_TEXT_THRESHOLD else "scanned"


def classify_complexity(page: fitz.Page) -> str:
    """Classify page content complexity using heuristics (no ML).

    Returns: "simple_text" / "table" / "mixed" / "formatted_text" / "image_only"
    """
    # Check for table grid lines
    has_grid = _detect_grid(page)
    if has_grid:
        return "table"

    image_coverage = _compute_image_coverage(page)
    char_count = len(page.get_text("text").strip())

    # Image-only page
    if image_coverage > 0.8 and char_count < 50:
        return "image_only"

    # Mixed: images + text
    if image_coverage > 0.3 and char_count > 50:
        return "mixed"

    # Check font diversity for formatted text
    font_count = _count_fonts(page)
    if font_count > 2:
        return "formatted_text"

    return "simple_text"


def _detect_grid(page: fitz.Page) -> bool:
    """Detect table grid lines: >=3 horizontal + >=3 vertical lines."""
    drawings = page.get_drawings()
    horiz = 0
    vert = 0
    for d in drawings:
        for item in d.get("items", []):
            if item[0] == "l":  # line
                p1, p2 = item[1], item[2]
                dx = abs(p2.x - p1.x)
                dy = abs(p2.y - p1.y)
                if dx > 20 and dy < 3:
                    horiz += 1
                elif dy > 20 and dx < 3:
                    vert += 1
    return horiz >= 3 and vert >= 3


def _compute_image_coverage(page: fitz.Page) -> float:
    """Compute fraction of page area covered by images."""
    page_area = page.rect.width * page.rect.height
    if page_area <= 0:
        return 0.0
    images = page.get_images(full=True)
    if not images:
        return 0.0
    image_area = 0.0
    for img in images:
        xref = img[0]
        try:
            img_rects = page.get_image_rects(xref)
            for rect in img_rects:
                image_area += rect.width * rect.height
        except Exception:
            pass
    return min(image_area / page_area, 1.0)


def _count_fonts(page: fitz.Page) -> int:
    """Count distinct font names used on the page."""
    fonts = set()
    blocks = page.get_text("dict", flags=fitz.TEXT_PRESERVE_WHITESPACE).get("blocks", [])
    for block in blocks:
        for line in block.get("lines", []):
            for span in line.get("spans", []):
                font_name = span.get("font", "")
                if font_name:
                    fonts.add(font_name)
    return len(fonts)


# --------------- OCR Engines ---------------


_MAX_RENDER_PIXELS = 50_000_000  # cap rendered image at 50M pixels (safe for 2Gi pod + Tesseract)


def _safe_zoom(page: fitz.Page) -> float:
    """Calculate zoom factor, capping to avoid oversized renders."""
    zoom = config.OCR_DPI / 72
    est_pixels = (page.rect.width * zoom) * (page.rect.height * zoom)
    if est_pixels > _MAX_RENDER_PIXELS:
        scale = (_MAX_RENDER_PIXELS / est_pixels) ** 0.5
        reduced_dpi = int(config.OCR_DPI * scale)
        logger.warning(
            "Page too large at %d DPI (%.0fM px), reducing to %d DPI",
            config.OCR_DPI, est_pixels / 1e6, reduced_dpi,
        )
        zoom = reduced_dpi / 72
    return zoom


def _render_page_to_image(page: fitz.Page) -> Image.Image:
    """Render a PDF page to PIL Image at configured DPI (with safety cap)."""
    zoom = _safe_zoom(page)
    mat = fitz.Matrix(zoom, zoom)
    pix = page.get_pixmap(matrix=mat)
    return Image.open(io.BytesIO(pix.tobytes("png")))


def _render_page_to_png_bytes(page: fitz.Page) -> bytes:
    """Render a PDF page to PNG bytes at configured DPI (with safety cap)."""
    zoom = _safe_zoom(page)
    mat = fitz.Matrix(zoom, zoom)
    pix = page.get_pixmap(matrix=mat)
    return pix.tobytes("png")


def tesseract_ocr(page: fitz.Page, page_num: int) -> OCRResult:
    """Run Tesseract OCR on a rendered PDF page."""
    start = time.monotonic()
    try:
        img = _render_page_to_image(page)
        text = pytesseract.image_to_string(img, lang="rus+eng")
        elapsed = int((time.monotonic() - start) * 1000)
        return OCRResult(
            page_num=page_num, text=text.strip(), engine="tesseract",
            success=True, processing_time_ms=elapsed,
        )
    except Exception as exc:
        elapsed = int((time.monotonic() - start) * 1000)
        logger.warning("Tesseract OCR failed for page %d: %s", page_num, exc)
        return OCRResult(
            page_num=page_num, text="", engine="tesseract",
            success=False, error=str(exc), processing_time_ms=elapsed,
        )


async def vlm_ocr(page: fitz.Page, page_num: int) -> OCRResult:
    """Run VLM OCR via Ollama REST API. Falls back to Tesseract on failure."""
    start = time.monotonic()
    try:
        # Render page to PNG bytes then base64 (with DPI safety cap)
        png_bytes = _render_page_to_png_bytes(page)
        img_b64 = base64.b64encode(png_bytes).decode("ascii")

        payload = {
            "model": config.VLM_MODEL,
            "messages": [
                {
                    "role": "user",
                    "content": (
                        "Извлеки весь текст с этого изображения страницы документа. "
                        "Сохрани структуру: заголовки обозначь через #, таблицы — "
                        "в markdown pipe-формате (|col1|col2|). "
                        "Выведи ТОЛЬКО извлечённый текст в формате markdown, "
                        "без комментариев и пояснений."
                    ),
                    "images": [img_b64],
                }
            ],
            "stream": False,
            "options": {"num_predict": 4096},
            "keep_alive": "30m",
        }

        async with httpx.AsyncClient(timeout=config.VLM_TIMEOUT) as client:
            resp = await client.post(
                f"{config.OLLAMA_URL}/api/chat",
                json=payload,
                headers=_ollama_headers(),
            )
            resp.raise_for_status()

        data = resp.json()
        text = data.get("message", {}).get("content", "")
        elapsed = int((time.monotonic() - start) * 1000)
        return OCRResult(
            page_num=page_num, text=text.strip(), engine="vlm",
            success=True, processing_time_ms=elapsed,
        )

    except (httpx.TimeoutException, httpx.ConnectError, httpx.HTTPStatusError) as exc:
        elapsed = int((time.monotonic() - start) * 1000)
        logger.warning(
            "VLM OCR failed for page %d (%s), falling back to Tesseract: %s",
            page_num, type(exc).__name__, exc,
        )
        fallback = tesseract_ocr(page, page_num)
        fallback.engine = "tesseract"  # mark as fallback
        return fallback

    except Exception as exc:
        elapsed = int((time.monotonic() - start) * 1000)
        logger.warning("VLM OCR unexpected error for page %d: %s", page_num, exc)
        fallback = tesseract_ocr(page, page_num)
        return fallback


# --------------- Orchestrator ---------------


async def process_pdf(
    file_path: str,
    analysis: DocumentAnalysis,
    progress_cb: Callable[[str], None] | None = None,
    force_vlm: bool = False,
) -> tuple[list[dict], IngestResult]:
    """Process a PDF with hybrid OCR based on page analysis.

    Digital pages are skipped (handled by existing unstructured pipeline).
    Scanned pages are routed to Tesseract or VLM based on complexity.
    If force_vlm=True, all scanned pages are processed via VLM.

    Returns (segments, ingest_result) where segments is [{text, page, ocr_engine, page_type}].
    """
    result = IngestResult(filename=analysis.file_path)
    segments: list[dict] = []

    doc = fitz.open(file_path)

    def _progress(msg: str) -> None:
        if progress_cb:
            progress_cb(msg)
        logger.info(msg)

    total_pages = len(analysis.pages)
    for pa in analysis.pages:
        if pa.scan_type == "digital":
            continue  # Will be handled by unstructured pipeline

        use_vlm = force_vlm or pa.ocr_engine == "vlm"
        engine_label = "vlm" if use_vlm else pa.ocr_engine

        result.ocr_pages += 1
        page = doc[pa.page_num - 1]  # 0-based index
        _progress(f"OCR страница {pa.page_num}/{total_pages} ({engine_label})...")

        try:
            if use_vlm:
                result.vlm_pages += 1
                ocr_result = await vlm_ocr(page, pa.page_num)
            else:
                ocr_result = tesseract_ocr(page, pa.page_num)

            if ocr_result.success and ocr_result.text:
                segments.append({
                    "text": ocr_result.text,
                    "page": pa.page_num,
                    "ocr_engine": ocr_result.engine,
                    "page_type": "scanned",
                })
            elif not ocr_result.success:
                result.warnings.append(
                    f"Страница {pa.page_num}: OCR не удался ({ocr_result.error})"
                )
            else:
                result.warnings.append(
                    f"Страница {pa.page_num}: OCR вернул пустой результат"
                )

        except Exception as exc:
            logger.error("Page %d processing failed: %s", pa.page_num, exc)
            result.warnings.append(f"Страница {pa.page_num}: пропущена ({exc})")

    doc.close()

    logger.info(
        "OCR complete: %d pages processed (%d via VLM), %d warnings",
        result.ocr_pages, result.vlm_pages, len(result.warnings),
    )
    return segments, result
