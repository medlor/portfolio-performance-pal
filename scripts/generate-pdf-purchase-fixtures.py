#!/usr/bin/env python3
"""Render independently authored invented statements; no financial modelling."""
from pathlib import Path
import sys
from reportlab.pdfgen import canvas
from reportlab.lib.pagesizes import A4

profile = sys.argv[1] if len(sys.argv) > 1 else 'pdf-purchase'
if profile not in ('pdf-purchase', 'pdf-sale'): raise SystemExit('Choose pdf-purchase or pdf-sale')
root = Path(__file__).resolve().parents[1] / 'fixtures/v1' / profile
for source in sorted(root.glob('*.txt')):
    pdf = canvas.Canvas(str(source.with_suffix('.pdf')), pagesize=A4, invariant=True)
    pdf.setTitle('Invented Baader ' + profile + ' grammar fixture: ' + source.stem)
    # These invented fixtures put an issuer header at each physical page start.
    # This is renderer metadata; production classification uses verified markers.
    pages = source.read_text().split('\nBaader Bank AG\n')
    if source.stem == 'page-marker-on-wrong-page':
        # The final extra paired-text newline comes from the blank physical page.
        # It is extraction formatting, not a text line drawn on the first page.
        paired = source.read_text()
        assert paired.endswith('\n\n'), 'Blank page requires its literal extraction newline'
        pages = [paired[:-1],''] # two logical markers on page 1; blank page 2
    for index, page in enumerate(pages):
        if index and page: page = 'Baader Bank AG\n' + page
        text = pdf.beginText(36, A4[1] - 40)
        text.setFont('Helvetica', 10)
        for line in page.splitlines():
            text.textLine(line)
        pdf.drawText(text)
        pdf.showPage()
    pdf.save()
