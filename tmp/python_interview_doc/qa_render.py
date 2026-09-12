from pathlib import Path
import importlib.util
import json

import pdfplumber


here = Path(__file__).resolve().parent
renderer = Path(
    r"C:\Users\26680\.codex\plugins\cache\openai-primary-runtime\documents\26.905.11957\skills\documents\render_docx.py"
)
spec = importlib.util.spec_from_file_location("packaged_docx_renderer", renderer)
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)

docx = here.parents[1] / "output/documents/夯智交互_Python后端实习生面试问答_马素超.docx"
pdf = here / "render/word-preview.pdf"
assert pdf.stat().st_mtime > docx.stat().st_mtime, "Word preview must be newer than final DOCX"

# The packaged runtime has no LibreOffice on this Windows host. Microsoft Word
# provides the PDF conversion, while the packaged renderer performs raster QA.
module.convert_to_pdf = lambda *args, **kwargs: (
    str(pdf),
    "Converted with native Microsoft Word",
)
pages = module.rasterize(str(docx), str(here / "final_pages"), 120, False, False)

report = []
with pdfplumber.open(pdf) as document:
    for index, page in enumerate(document.pages, 1):
        text = page.extract_text() or ""
        report.append(
            {
                "page": index,
                "chars": len(text),
                "start": text.splitlines()[0] if text.splitlines() else "",
                "bottom": max(
                    (char["bottom"] for char in page.chars if char["text"].strip()),
                    default=0,
                ),
            }
        )

assert len(pages) == 29, f"Expected 29 pages, got {len(pages)}"
assert min(item["chars"] for item in report) > 450, "Unexpected short spill page"
(here / "qa_report.json").write_text(
    json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8"
)
print(json.dumps(report, ensure_ascii=False))
