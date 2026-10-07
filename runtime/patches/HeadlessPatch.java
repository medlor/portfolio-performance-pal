import java.nio.file.*;
import java.security.MessageDigest;
import java.util.HexFormat;

/** Reviewed fixed adapter patch; reject every unreviewed source change. */
class HeadlessPatch {
    public static void main(String[] args) throws Exception {
        Path file = Path.of(args[0], "name.abuchen.portfolio.pdfbox3/src/name/abuchen/portfolio/pdfbox3/PDFBox3Adapter.java");
        byte[] bytes = Files.readAllBytes(file);
        String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        if (!hash.equals("b7601f20c3ba6e47fbb9f62b08e2adc83f8d1ea01b65cdbd52a09718e676e85c"))
            throw new IllegalStateException("Headless adapter source changed; manual review required");
        String source = new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
        Files.writeString(file, source.replace("return FrameworkUtil.getBundle(PDDocument.class).getVersion().toString();", "var bundle = FrameworkUtil.getBundle(PDDocument.class);\n        return bundle != null ? bundle.getVersion().toString()\n                        : org.apache.pdfbox.util.Version.getVersion();"));
    }
}
