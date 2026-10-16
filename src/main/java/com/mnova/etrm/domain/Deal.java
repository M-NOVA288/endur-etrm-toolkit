package com.mnova.etrm.domain;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * A trade, modeled on an Endur transaction.
 *
 * <ul>
 *   <li>{@code dealTrackingNum} is stable across amendments (Endur deal_tracking_num).</li>
 *   <li>{@code tranNum} identifies a specific version (Endur tran_num); each amendment gets a new one.</li>
 * </ul>
 *
 * Quantity semantics: for physical deals and swaps {@code quantity} is a daily quantity
 * (MMBtu/d, MWh/d) and each monthly period's volume is quantity x days. For futures
 * {@code quantity} is the total contract volume per month (lots x lot size).
 */
public final class Deal {

    private final long dealTrackingNum;
    private final long tranNum;
    private final int version;
    private final Toolset toolset;
    private final String insType;
    private final BuySell buySell;
    private final String internalBu;
    private final String externalBu;
    private final String portfolio;
    private final String trader;
    private final String index;
    private final double fixedPrice;
    private final double quantity;
    private final String unit;
    private final String currency;
    private final LocalDate tradeDate;
    private final LocalDate startDate;
    private final LocalDate endDate;
    private final String externalRef;
    private TranStatus status;

    private Deal(Builder b) {
        this.dealTrackingNum = b.dealTrackingNum;
        this.tranNum = b.tranNum;
        this.version = b.version;
        this.toolset = Objects.requireNonNull(b.toolset, "toolset");
        this.insType = b.insType;
        this.buySell = Objects.requireNonNull(b.buySell, "buySell");
        this.internalBu = b.internalBu;
        this.externalBu = b.externalBu;
        this.portfolio = b.portfolio;
        this.trader = b.trader;
        this.index = b.index;
        this.fixedPrice = b.fixedPrice;
        this.quantity = b.quantity;
        this.unit = b.unit;
        this.currency = b.currency == null ? "USD" : b.currency;
        this.tradeDate = b.tradeDate;
        this.startDate = Objects.requireNonNull(b.startDate, "startDate");
        this.endDate = Objects.requireNonNull(b.endDate, "endDate");
        this.externalRef = b.externalRef;
        this.status = b.status == null ? TranStatus.PENDING : b.status;
        if (endDate.isBefore(startDate)) {
            throw new IllegalArgumentException("endDate before startDate on deal " + dealTrackingNum);
        }
    }

    public static Builder builder() {
        return new Builder();
    }

    /** Builder pre-populated with this deal's fields, used for amendments and re-numbering. */
    public Builder toBuilder() {
        return new Builder()
                .dealTrackingNum(dealTrackingNum).tranNum(tranNum).version(version)
                .toolset(toolset).insType(insType).buySell(buySell)
                .internalBu(internalBu).externalBu(externalBu).portfolio(portfolio).trader(trader)
                .index(index).fixedPrice(fixedPrice).quantity(quantity).unit(unit).currency(currency)
                .tradeDate(tradeDate).startDate(startDate).endDate(endDate)
                .externalRef(externalRef).status(status);
    }

    /** Splits the deal into calendar-month periods clipped to the deal's start/end. */
    public List<DeliveryPeriod> periods() {
        List<DeliveryPeriod> out = new ArrayList<>();
        YearMonth m = YearMonth.from(startDate);
        YearMonth last = YearMonth.from(endDate);
        while (!m.isAfter(last)) {
            LocalDate ps = m.atDay(1).isBefore(startDate) ? startDate : m.atDay(1);
            LocalDate pe = m.atEndOfMonth().isAfter(endDate) ? endDate : m.atEndOfMonth();
            double vol = toolset == Toolset.COM_FUT
                    ? quantity
                    : quantity * (ChronoUnit.DAYS.between(ps, pe) + 1);
            out.add(new DeliveryPeriod(ps, pe, vol));
            m = m.plusMonths(1);
        }
        return Collections.unmodifiableList(out);
    }

    public double totalVolume() {
        return periods().stream().mapToDouble(DeliveryPeriod::volume).sum();
    }

    public long dealTrackingNum() { return dealTrackingNum; }
    public long tranNum() { return tranNum; }
    public int version() { return version; }
    public Toolset toolset() { return toolset; }
    public String insType() { return insType; }
    public BuySell buySell() { return buySell; }
    public String internalBu() { return internalBu; }
    public String externalBu() { return externalBu; }
    public String portfolio() { return portfolio; }
    public String trader() { return trader; }
    public String index() { return index; }
    public double fixedPrice() { return fixedPrice; }
    public double quantity() { return quantity; }
    public String unit() { return unit; }
    public String currency() { return currency; }
    public LocalDate tradeDate() { return tradeDate; }
    public LocalDate startDate() { return startDate; }
    public LocalDate endDate() { return endDate; }
    public String externalRef() { return externalRef; }
    public TranStatus status() { return status; }

    /** Status changes are only made through the lifecycle service, which enforces transitions. */
    public void setStatus(TranStatus status) {
        this.status = Objects.requireNonNull(status);
    }

    @Override
    public String toString() {
        return "Deal[" + dealTrackingNum + " v" + version + " tran=" + tranNum + " " + toolset + " "
                + buySell + " " + quantity + " " + unit + " " + index + " @" + fixedPrice + " "
                + startDate + ".." + endDate + " " + status + "]";
    }

    public static final class Builder {
        private long dealTrackingNum;
        private long tranNum;
        private int version = 1;
        private Toolset toolset;
        private String insType;
        private BuySell buySell;
        private String internalBu;
        private String externalBu;
        private String portfolio;
        private String trader;
        private String index;
        private double fixedPrice;
        private double quantity;
        private String unit;
        private String currency;
        private LocalDate tradeDate;
        private LocalDate startDate;
        private LocalDate endDate;
        private String externalRef;
        private TranStatus status;

        public Builder dealTrackingNum(long v) { this.dealTrackingNum = v; return this; }
        public Builder tranNum(long v) { this.tranNum = v; return this; }
        public Builder version(int v) { this.version = v; return this; }
        public Builder toolset(Toolset v) { this.toolset = v; return this; }
        public Builder insType(String v) { this.insType = v; return this; }
        public Builder buySell(BuySell v) { this.buySell = v; return this; }
        public Builder internalBu(String v) { this.internalBu = v; return this; }
        public Builder externalBu(String v) { this.externalBu = v; return this; }
        public Builder portfolio(String v) { this.portfolio = v; return this; }
        public Builder trader(String v) { this.trader = v; return this; }
        public Builder index(String v) { this.index = v; return this; }
        public Builder fixedPrice(double v) { this.fixedPrice = v; return this; }
        public Builder quantity(double v) { this.quantity = v; return this; }
        public Builder unit(String v) { this.unit = v; return this; }
        public Builder currency(String v) { this.currency = v; return this; }
        public Builder tradeDate(LocalDate v) { this.tradeDate = v; return this; }
        public Builder startDate(LocalDate v) { this.startDate = v; return this; }
        public Builder endDate(LocalDate v) { this.endDate = v; return this; }
        public Builder externalRef(String v) { this.externalRef = v; return this; }
        public Builder status(TranStatus v) { this.status = v; return this; }

        public Deal build() {
            return new Deal(this);
        }
    }
}
