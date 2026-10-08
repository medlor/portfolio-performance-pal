package pal;

import java.time.*;
import java.time.format.DateTimeFormatter;
import name.abuchen.portfolio.model.*;
import name.abuchen.portfolio.money.Money;

/** A security-associated cash credit, independent of current held quantity. */
public final class Dividend {
    public record Plan(Account account, AccountTransaction transaction, Amounts.Dividend amounts, boolean fallbackTime) {}

    public static Plan plan(Client client, Account cash, CommandLine line) {
        if (cash == null) throw new IllegalArgumentException("Select a cash account by stable ID or exact unique name");
        var security = Securities.existing(client, line);
        String currency = cash.getCurrencyCode();
        var fx = ForeignExchange.from(line, currency, security.getCurrencyCode());
        var amounts = Amounts.dividend(line, fx);
        final LocalDateTime date;
        try {
            if (line.value("date") == null) throw new IllegalArgumentException("Missing --date YYYY-MM-DD");
            date = LocalDateTime.of(LocalDate.parse(line.value("date")),
                    line.flag("time") ? LocalTime.parse(line.value("time")) : LocalTime.of(11, 0));
        } catch (DateTimeException e) {
            throw new IllegalArgumentException("Use an ISO --date YYYY-MM-DD and optional local --time HH:MM[:SS[.fraction]]", e);
        }
        var tx = new AccountTransaction(date, currency, amounts.booked(), security, AccountTransaction.Type.DIVIDENDS);
        if (amounts.forexGross() != null) tx.addUnit(amounts.forexGross());
        if (amounts.fees() != 0) tx.addUnit(new Transaction.Unit(Transaction.Unit.Type.FEE, Money.of(currency, amounts.fees())));
        if (amounts.taxes() != 0) tx.addUnit(new Transaction.Unit(Transaction.Unit.Type.TAX, Money.of(currency, amounts.taxes())));
        return new Plan(cash, tx, amounts, !line.flag("time"));
    }

    public static String preview(Plan plan, int inputModel) {
        var tx = plan.transaction(); var amounts = plan.amounts(); String currency = tx.getCurrencyCode();
        var out = new StringBuilder("PREVIEW DIVIDEND\n");
        String image = System.getenv("PP_PAL_ENGINE_IMAGE");
        if (image != null) out.append("ENGINE IMAGE ").append(image).append('\n');
        out.append(Core.outputModel(inputModel));
        out.append("SECURITY ").append(tx.getSecurity().getName()).append(" | ").append(tx.getSecurity().getUUID()).append(" | ").append(tx.getSecurity().getCurrencyCode()).append(" (existing)\n");
        out.append("CASH ACCOUNT ").append(plan.account().getName()).append(" | ").append(plan.account().getUUID()).append('\n');
        out.append("DATE ").append(tx.getDateTime().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME))
                .append(plan.fallbackTime() ? " (supplied date; fallback time 11:00:00)\n" : " (supplied date and time)\n");
        out.append("GROSS ").append(currency).append(' ').append(Amounts.moneyText(tx.getGrossValue().getAmount())).append(" (").append(amounts.grossOrigin()).append(")\n");
        out.append(ForeignExchange.preview(tx));
        if (amounts.forexGross() != null) out.append("FOREIGN GROSS ORIGIN ").append(amounts.foreignOrigin()).append('\n');
        out.append("FEES ").append(currency).append(' ').append(Amounts.moneyText(tx.getUnitSum(Transaction.Unit.Type.FEE).getAmount())).append(" (").append(amounts.feeOrigin()).append(")\n");
        out.append("TAXES ").append(currency).append(' ').append(Amounts.moneyText(tx.getUnitSum(Transaction.Unit.Type.TAX).getAmount())).append(" (").append(amounts.taxOrigin()).append(")\n");
        out.append("BOOKED CREDIT ").append(currency).append(' ').append(Amounts.moneyText(tx.getAmount())).append(" (").append(amounts.bookedOrigin()).append(")\n");
        out.append("PROPOSED RECORDS security-associated cash dividend; held quantity and holding last updated unchanged\n");
        return out.toString();
    }
}
