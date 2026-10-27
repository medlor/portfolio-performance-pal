/** Render reviewed anonymized text and canonicalize paired PP conversion; no financial modelling. */
import java.nio.file.*;
import java.util.*;
import name.abuchen.portfolio.datatransfer.pdf.PDFInputFile;
import org.apache.pdfbox.pdmodel.*;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.*;
class RenderCurrentLayouts {
 public static void main(String[] args) throws Exception {
  for(Path text:Files.list(Path.of(args[0])).filter(p->p.toString().endsWith(".txt")).sorted().toList()) {
   var pdf=text.resolveSibling(text.getFileName().toString().replace(".txt",".pdf"));
   try(var doc=new PDDocument()) {
    doc.getDocumentInformation().setTitle("Anonymized current Baader layout: "+text.getFileName());
    for(String part:Files.readString(text).split("\n(?=Baader Bank AG\n)")) {
     var page=new PDPage(PDRectangle.A4);doc.addPage(page);
     try(var content=new PDPageContentStream(doc,page)) {
      content.beginText();content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA),9);content.setLeading(12);content.newLineAtOffset(36,800);
      for(String line:part.split("\n")) {content.showText(line);content.newLine();}content.endText();
     }
    }
    var id = new org.apache.pdfbox.cos.COSArray();
    id.add(new org.apache.pdfbox.cos.COSString(new byte[16]));
    id.add(new org.apache.pdfbox.cos.COSString(new byte[16]));
    doc.getDocument().setDocumentID(id);
    doc.save(pdf.toFile());
   }
   var input=new PDFInputFile(pdf.toFile());input.convertPDFtoText();Files.writeString(text,input.getText());
   System.out.println("Rendered "+text.getFileName());
  }
 }
}
