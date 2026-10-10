package name.abuchen.portfolio.datatransfer.pdf;

import java.math.BigDecimal;
import java.text.*;
import java.time.LocalDateTime;
import java.util.*;
import name.abuchen.portfolio.datatransfer.pdf.BaaderBankPDFExtractor;
import name.abuchen.portfolio.datatransfer.pdf.PDFParser.DocumentType;
import name.abuchen.portfolio.model.*;
import name.abuchen.portfolio.money.Values;

/** Observe PP assignments and conversions; the bank grammar remains entirely upstream. */
public final class ObservedBaaderExtractor extends BaaderBankPDFExtractor {
    private final Map<Security, Map<String,String>> identities = new IdentityHashMap<>();
    private boolean explicitTime;
    private boolean feesPresent;
    private boolean taxesPresent;

    public ObservedBaaderExtractor() { super(new Client()); }
    public Map<String,String> identity(Security security) { return identities.get(security); }
    public boolean explicitTime() { return explicitTime; }
    public boolean feesPresent() { return feesPresent; }
    public boolean taxesPresent() { return taxesPresent; }

    @Override protected void processFeeEntries(Object target, Map<String,String> values, DocumentType type) {
        feesPresent = true;
        super.processFeeEntries(target, values, type);
    }

    @Override protected void processTaxEntries(Object target, Map<String,String> values, DocumentType type) {
        taxesPresent = true;
        super.processTaxEntries(target, values, type);
    }

    @Override protected Security getOrCreateSecurity(Map<String,String> values) {
        var identity = Map.copyOf(values);
        var security = super.getOrCreateSecurity(values);
        identities.put(security, identity);
        return security;
    }

    @Override protected LocalDateTime asDate(String date, String time) {
        var result = super.asDate(date, time);
        explicitTime = true;
        return result;
    }

    @Override protected long asShares(String value) {
        long result = super.asShares(value);
        exact(value, Locale.GERMANY, Values.Share.precision(), result);
        return result;
    }

    @Override protected long asShares(String value, String language, String country) {
        long result = super.asShares(value, language, country);
        exact(value, Locale.of(language, country), Values.Share.precision(), result);
        return result;
    }

    @Override protected long asAmount(String value) {
        long result = super.asAmount(value);
        exact(value, Locale.GERMANY, Values.Money.precision(), result);
        return result;
    }

    @Override protected long asAmount(String value, String language, String country) {
        long result = super.asAmount(value, language, country);
        exact(value, Locale.of(language, country), Values.Money.precision(), result);
        return result;
    }

    // Validate representability of an already matched PP number, without parsing statement lines.
    private static void exact(String text, Locale locale, int precision, long result) {
        var format = (DecimalFormat)NumberFormat.getNumberInstance(locale);
        format.setParseBigDecimal(true);
        var position = new ParsePosition(0);
        var parsed = format.parse(text, position);
        if (!(parsed instanceof BigDecimal number) || position.getIndex() != text.length()
                || number.signum() < 0 || number.movePointRight(precision).longValueExact() != result)
            throw new IllegalArgumentException("PP-extracted number exceeds exact supported precision/range");
    }
}
