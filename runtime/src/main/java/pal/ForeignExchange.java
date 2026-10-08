package pal;

import java.math.*;
import name.abuchen.portfolio.model.Transaction;
import name.abuchen.portfolio.money.Money;

/** Explicit security-to-cash conversion; no rate discovery or implicit inversion. */
public record ForeignExchange(String cashCurrency, String securityCurrency, BigDecimal rate) {
    public static ForeignExchange from(CommandLine line, String cash, String security) {
        if (cash.equals(security)) {
            if (line.flag("exchange-rate") || line.flag("forex-gross"))
                throw new IllegalArgumentException("FX options require different cash and security currencies");
            return null;
        }
        if (!line.flag("exchange-rate"))
            throw new IllegalArgumentException("Foreign-currency transaction requires --exchange-rate (cash currency per security currency)");
        // Upstream exchange calculations use a ten-significant-digit MathContext.
        final BigDecimal rate;
        try { rate = new BigDecimal(line.value("exchange-rate")).stripTrailingZeros(); }
        catch (NumberFormatException e) { throw new IllegalArgumentException("--exchange-rate must be a decimal", e); }
        if (rate.signum() <= 0 || rate.precision() > 10 || Math.abs((long) rate.scale()) > 10)
            throw new IllegalArgumentException("--exchange-rate must be positive and representable with 10 significant digits and at most 10 decimal places/range places");
        return new ForeignExchange(cash, security, rate);
    }

    public long toCash(long foreign) {
        return BigDecimal.valueOf(foreign).multiply(rate).setScale(0, RoundingMode.HALF_UP).longValueExact();
    }

    public Transaction.Unit grossUnit(long cash, Long suppliedForeign) {
        long foreign = suppliedForeign != null ? suppliedForeign : BigDecimal.valueOf(cash)
                .divide(rate, 0, RoundingMode.HALF_UP).longValueExact();
        if (toCash(foreign) != cash)
            throw new IllegalArgumentException("Foreign gross contradicts cash gross after exchange-rate HALF_UP cents rounding; supply --forex-gross");
        return new Transaction.Unit(Transaction.Unit.Type.GROSS_VALUE, Money.of(cashCurrency, cash),
                Money.of(securityCurrency, foreign), rate);
    }

    public static String preview(Transaction tx) {
        return tx.getUnit(Transaction.Unit.Type.GROSS_VALUE).map(unit ->
                "FOREIGN GROSS " + unit.getForex().getCurrencyCode() + " " + Amounts.moneyText(unit.getForex().getAmount())
                + "\nEXCHANGE RATE " + unit.getExchangeRate().toPlainString() + " " + unit.getAmount().getCurrencyCode()
                + " per " + unit.getForex().getCurrencyCode() + " (supplied; conversion HALF_UP cents)\n").orElse("");
    }
}
