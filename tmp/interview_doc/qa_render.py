from pathlib import Path
import importlib.util
import json
import pdfplumber

here=Path(__file__).resolve().parent
renderer=Path(r'C:\Users\26680\.codex\plugins\cache\openai-primary-runtime\documents\26.905.11957\skills\documents\render_docx.py')
spec=importlib.util.spec_from_file_location('packaged_docx_renderer',renderer)
module=importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)
docx=here.parents[1]/'output/documents/程多多_Java与Agent面试问答_马素超.docx'
pdf=here/'render/word-preview.pdf'
assert pdf.stat().st_mtime > docx.stat().st_mtime, 'Word preview must be newer than final DOCX'
# Windows runtime has no bundled LibreOffice. Native Word provided the PDF.
# Reuse the packaged rasterizer after substituting only the conversion step.
module.convert_to_pdf=lambda *args,**kwargs:(str(pdf),'Converted with native Microsoft Word')
pages=module.rasterize(str(docx),str(here/'final_pages'),120,False,False)
report=[]
with pdfplumber.open(pdf) as d:
    for i,p in enumerate(d.pages,1):
        txt=p.extract_text() or ''
        report.append({'page':i,'chars':len(txt),'start':txt.splitlines()[0],'bottom':max((c['bottom'] for c in p.chars if c['text'].strip()),default=0)})
assert len(pages)==27, f'Expected 27 pages, got {len(pages)}'
assert min(x['chars'] for x in report)>500, 'Unexpected short spill page'
(here/'qa_report.json').write_text(json.dumps(report,ensure_ascii=False,indent=2),encoding='utf-8')
print(json.dumps(report,ensure_ascii=False))
