package pal;

import java.math.*;
import name.abuchen.portfolio.money.Values;

/** Exact CLI decimals, with rounding only at explicitly derived monetary values. */
public final class Amounts {
    public record Purchase(long shares, BigDecimal price, long gross, long fees, long taxes, long booked,
            String priceOrigin, String grossOrigin, String bookedOrigin, String feeOrigin, String taxOrigin) {}

    public static Purchase purchase(CommandLine line) { return trade(line, false); }
    public static Purchase sale(CommandLine line) { return trade(line, true); }

    public record Dividend(long gross, long fees, long taxes, long booked,
            String grossOrigin, String bookedOrigin, String feeOrigin, String taxOrigin) {}

    public static Dividend dividend(CommandLine line) {
        long fees = money(line.value("fees", "0"), "fees");
        long taxes = money(line.value("taxes", "0"), "taxes");
        long costs = Math.addExact(fees, taxes);
        Long suppliedGross = line.flag("gross") ? money(line.value("gross"), "gross") : null;
        Long suppliedBooked = line.flag("total") ? money(line.value("total"), "total") : null;
        if (suppliedGross == null && suppliedBooked == null)
            throw new IllegalArgumentException("Dividend requires --gross or --total to determine the cash credit");
        long gross = suppliedGross != null ? suppliedGross : Math.addExact(suppliedBooked, costs);
        long booked = Math.subtractExact(gross, costs);
        if (booked < 0) throw new IllegalArgumentException("Gross dividend value is less than fees and taxes");
        if (suppliedBooked != null && booked != suppliedBooked)
            throw new IllegalArgumentException("Booked total contradicts gross - fees - taxes");
        return new Dividend(gross, fees, taxes, booked,
                suppliedGross != null ? "supplied" : "derived from booked + fees + taxes",
                suppliedBooked != null ? "supplied" : "derived from gross - fees - taxes",
                line.flag("fees") ? "supplied" : "default zero", line.flag("taxes") ? "supplied" : "default zero");
    }

    private static Purchase trade(CommandLine line, boolean sale) {
        long shares = scaled(line.value("quantity"), Values.Share.precision(), "quantity", true);
        BigDecimal price = line.flag("price") ? BigDecimal.valueOf(scaled(line.value("price"), Values.Quote.precision(), "price", true), Values.Quote.precision()) : null;
        long fees = money(line.value("fees", "0"), "fees");
        long taxes = money(line.value("taxes", "0"), "taxes");
        long costs = Math.addExact(fees, taxes);
        Long suppliedGross = line.flag("gross") ? money(line.value("gross"), "gross") : null;
        Long suppliedBooked = line.flag("total") ? money(line.value("total"), "total") : null;
        Long pricedGross = price == null ? null : BigDecimal.valueOf(shares, Values.Share.precision()).multiply(price)
                .setScale(Values.Money.precision(), RoundingMode.HALF_UP).movePointRight(Values.Money.precision()).longValueExact();
        if (suppliedGross == null && suppliedBooked == null && pricedGross == null)
            throw new IllegalArgumentException("Quantity requires --price, --gross or --total to determine the trade amount");
        long gross = suppliedGross != null ? suppliedGross : pricedGross != null ? pricedGross : sale ? Math.addExact(suppliedBooked, costs) : Math.subtractExact(suppliedBooked, costs);
        if (gross < 0) throw new IllegalArgumentException("Booked total is less than fees and taxes");
        if (pricedGross != null && gross != pricedGross) throw new IllegalArgumentException("Gross value contradicts quantity x price after HALF_UP cents rounding");
        long booked = sale ? Math.subtractExact(gross, costs) : Math.addExact(gross, costs);
        if (booked < 0) throw new IllegalArgumentException("Gross sale value is less than fees and taxes");
        if (suppliedBooked != null && booked != suppliedBooked) throw new IllegalArgumentException(sale ? "Booked total contradicts gross - fees - taxes" : "Booked total contradicts gross + fees + taxes");
        String priceOrigin = price == null ? "derived from gross / quantity; display HALF_EVEN 8 places" : "supplied";
        if (price == null) price = BigDecimal.valueOf(gross, Values.Money.precision())
                .divide(BigDecimal.valueOf(shares, Values.Share.precision()), Values.Quote.precision(), RoundingMode.HALF_EVEN);
        try { price.movePointRight(Values.Quote.precision()).longValueExact(); }
        catch (ArithmeticException e) { throw new IllegalArgumentException("Derived price exceeds supported quote range", e); }
        return new Purchase(shares, price, gross, fees, taxes, booked, priceOrigin,
                suppliedGross != null ? "supplied" : pricedGross != null ? "derived from quantity x price; HALF_UP cents" : sale ? "derived from booked + fees + taxes" : "derived from booked - fees - taxes",
                suppliedBooked != null ? "supplied" : sale ? "derived from gross - fees - taxes" : "derived from gross + fees + taxes",
                line.flag("fees") ? "supplied" : "default zero", line.flag("taxes") ? "supplied" : "default zero");
    }

    public static long money(String text, String label) { return scaled(text, Values.Money.precision(), label, false); }
    public static long scaled(String text, int precision, String label, boolean positive) {
        if (text == null) throw new IllegalArgumentException("Missing --" + label);
        final BigDecimal value;
        try { value = new BigDecimal(text); }
        catch (NumberFormatException e) { throw new IllegalArgumentException("--" + label + " must be a decimal", e); }
        if (positive ? value.signum() <= 0 : value.signum() < 0)
            throw new IllegalArgumentException("--" + label + " must be " + (positive ? "positive" : "nonnegative"));
        try { return value.movePointRight(precision).longValueExact(); }
        catch (ArithmeticException e) { throw new IllegalArgumentException("--" + label + " exceeds supported precision (" + precision + " places) or range", e); }
    }
    public static String moneyText(long value) { return BigDecimal.valueOf(value, Values.Money.precision()).toPlainString(); }
    public static String quantityText(long shares) { return BigDecimal.valueOf(shares, Values.Share.precision()).stripTrailingZeros().toPlainString(); }
}
