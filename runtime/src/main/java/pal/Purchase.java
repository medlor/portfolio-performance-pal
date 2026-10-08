package pal;

import java.time.*;
import java.time.format.DateTimeFormatter;
import name.abuchen.portfolio.model.*;
import name.abuchen.portfolio.money.Money;

/** Preview and writer share one proposed upstream linked entry per invocation. */
public final class Purchase {
    public record Plan(BuySellEntry entry, Amounts.Purchase amounts, boolean fallbackTime, boolean createsSecurity) {
        public Plan(BuySellEntry entry, Amounts.Purchase amounts, boolean fallbackTime) { this(entry, amounts, fallbackTime, false); }
    }

    public static Plan plan(Client client, Portfolio securities, Account cash, CommandLine line) {
        return trade(client, securities, cash, line, false);
    }

    static Plan trade(Client client, Portfolio securities, Account cash, CommandLine line, boolean sale) {
        if (securities == null || cash == null) throw new IllegalArgumentException("Select required securities and cash accounts by stable ID or exact unique name");
        var choice = sale ? new Securities.Choice(Securities.existing(client, line), false) : Securities.purchase(client, line);
        var security = choice.security();
        String currency = cash.getCurrencyCode();
        if (!currency.equals(security.getCurrencyCode())) throw new IllegalArgumentException("Foreign-currency trade requires the separately validated FX path");
        var amounts = sale ? Amounts.sale(line) : Amounts.purchase(line);
        final LocalDate date;
        final LocalTime time;
        try {
            if (line.value("date") == null) throw new IllegalArgumentException("Missing --date YYYY-MM-DD");
            date = LocalDate.parse(line.value("date"));
            time = line.flag("time") ? LocalTime.parse(line.value("time")) : LocalTime.of(11, 0, sale ? 1 : 0);
        } catch (DateTimeException e) { throw new IllegalArgumentException("Use an ISO --date YYYY-MM-DD and optional local --time HH:MM[:SS[.fraction]]", e); }
        var entry = new BuySellEntry(securities, cash);
        entry.setType(sale ? PortfolioTransaction.Type.SELL : PortfolioTransaction.Type.BUY);
        entry.setDate(LocalDateTime.of(date, time));
        entry.setSecurity(security);
        entry.setShares(amounts.shares());
        entry.setCurrencyCode(currency);
        entry.setAmount(amounts.booked());
        var transaction = entry.getPortfolioTransaction();
        if (amounts.fees() != 0) transaction.addUnit(new Transaction.Unit(Transaction.Unit.Type.FEE, Money.of(currency, amounts.fees())));
        if (amounts.taxes() != 0) transaction.addUnit(new Transaction.Unit(Transaction.Unit.Type.TAX, Money.of(currency, amounts.taxes())));
        return new Plan(entry, amounts, !line.flag("time"), choice.created());
    }

    public static String preview(Plan plan, int inputModel) {
        var entry = plan.entry(); var tx = entry.getPortfolioTransaction(); var amounts = plan.amounts();
        String currency = tx.getCurrencyCode();
        boolean sale = tx.getType() == PortfolioTransaction.Type.SELL;
        var out = new StringBuilder(sale ? "PREVIEW SALE\n" : "PREVIEW PURCHASE\n");
        String image = System.getenv("PP_PAL_ENGINE_IMAGE");
        if (image != null) out.append("ENGINE IMAGE ").append(image).append('\n');
        out.append("OUTPUT MODEL ").append(Core.MODEL).append(" (input ").append(inputModel).append("; no migration)\n");
        out.append("SECURITY ").append(tx.getSecurity().getName()).append(" | ").append(tx.getSecurity().getUUID()).append(" | ").append(currency).append(plan.createsSecurity() ? " (create)\n" : " (existing)\n");
        out.append("SECURITIES ACCOUNT ").append(entry.getPortfolio().getName()).append(" | ").append(entry.getPortfolio().getUUID()).append('\n');
        out.append("CASH ACCOUNT ").append(entry.getAccount().getName()).append(" | ").append(entry.getAccount().getUUID()).append('\n');
        out.append("DATE ").append(tx.getDateTime().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)).append(plan.fallbackTime() ? (sale ? " (supplied date; fallback time 11:00:01)\n" : " (supplied date; fallback time 11:00:00)\n") : " (supplied date and time)\n");
        out.append("QUANTITY ").append(Amounts.quantityText(tx.getShares())).append(" (supplied)\n");
        out.append("PRICE ").append(currency).append(' ').append(amounts.price().stripTrailingZeros().toPlainString()).append(" (").append(amounts.priceOrigin()).append(")\n");
        out.append("GROSS ").append(currency).append(' ').append(Amounts.moneyText(tx.getGrossValueAmount())).append(" (").append(amounts.grossOrigin()).append(")\n");
        out.append("FEES ").append(currency).append(' ').append(Amounts.moneyText(amounts.fees())).append(" (").append(amounts.feeOrigin()).append(")\n");
        out.append("TAXES ").append(currency).append(' ').append(Amounts.moneyText(amounts.taxes())).append(" (").append(amounts.taxOrigin()).append(")\n");
        out.append(sale ? "BOOKED CREDIT " : "BOOKED DEBIT ").append(currency).append(' ').append(Amounts.moneyText(entry.getAccountTransaction().getAmount())).append(" (").append(amounts.bookedOrigin()).append(")\n");
        out.append(sale ? "PROPOSED RECORDS linked securities sale and cash credit; financial data unchanged\n" : "PROPOSED RECORDS linked securities purchase and cash debit; financial data unchanged\n");
        return out.toString();
    }
}
