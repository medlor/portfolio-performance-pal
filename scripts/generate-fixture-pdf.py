#!/usr/bin/env python3
"""Create the invented PDF fixture from its reviewed text; no financial modelling."""
from pathlib import Path
from reportlab.pdfgen import canvas
from reportlab.lib.pagesizes import A4
root = Path(__file__).resolve().parents[1]
path = root / "fixtures/v1/baader-buy.pdf"
pdf = canvas.Canvas(str(path), pagesize=A4, invariant=True)
pdf.setTitle("Synthetic Baader layout fixture — no real financial data")
text = pdf.beginText(36, A4[1] - 40)
text.setFont("Helvetica", 11)
for line in (root / "fixtures/v1/baader-buy.txt").read_text().splitlines():
    text.textLine(line)
pdf.drawText(text)
pdf.save()
