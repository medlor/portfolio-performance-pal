package pal;

import java.nio.file.*;
import name.abuchen.portfolio.datatransfer.pdf.PDFInputFile;

/** Paired invented text must equal real upstream PDF conversion, including pages. */
public class PdfTextOracle {
    public static void main(String[] args) throws Exception {
        int count = 0;
        try (var files = Files.list(Path.of(args[0]))) {
            for (var text : files.filter(p -> p.toString().endsWith(".txt")).sorted().toList()) {
                var pdf = new PDFInputFile(text.resolveSibling(text.getFileName().toString().replace(".txt",".pdf")).toFile());
                pdf.convertPDFtoText();
                if (!pdf.getText().equals(Files.readString(text))) throw new AssertionError("Actual PDF text differs from paired statement: " + text.getFileName());
                count++;
            }
        }
        System.out.println("PASS " + count + " literal paired statement texts match actual upstream PDF conversion");
    }
}
