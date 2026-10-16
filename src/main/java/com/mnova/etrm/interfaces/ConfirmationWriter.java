package com.mnova.etrm.interfaces;

import com.mnova.etrm.domain.Deal;
import com.mnova.etrm.domain.DeliveryPeriod;

/**
 * Outbound confirmation document (XML) for a deal version, the payload sent to a
 * confirmation-matching platform or emailed to the counterparty.
 */
public final class ConfirmationWriter {

    public String toXml(Deal d, String eventType) {
        StringBuilder sb = new StringBuilder();
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        sb.append("<TradeConfirmation event=\"").append(esc(eventType)).append("\">\n");
        tag(sb, "DealTrackingNum", String.valueOf(d.dealTrackingNum()));
        tag(sb, "TranNum", String.valueOf(d.tranNum()));
        tag(sb, "Version", String.valueOf(d.version()));
        tag(sb, "ExternalRef", d.externalRef());
        tag(sb, "TradeDate", String.valueOf(d.tradeDate()));
        tag(sb, "Product", d.toolset() + "/" + d.insType());
        tag(sb, "Seller", d.buySell().sign() < 0 ? d.internalBu() : d.externalBu());
        tag(sb, "Buyer", d.buySell().sign() > 0 ? d.internalBu() : d.externalBu());
        tag(sb, "Index", d.index());
        tag(sb, "FixedPrice", String.format("%.4f", d.fixedPrice()));
        tag(sb, "Currency", d.currency());
        tag(sb, "Quantity", String.format("%.2f", d.quantity()));
        tag(sb, "Unit", d.unit());
        tag(sb, "StartDate", String.valueOf(d.startDate()));
        tag(sb, "EndDate", String.valueOf(d.endDate()));
        sb.append("  <Periods>\n");
        for (DeliveryPeriod p : d.periods()) {
            sb.append("    <Period month=\"").append(p.month()).append("\" volume=\"")
                    .append(String.format("%.2f", p.volume())).append("\" paymentDate=\"")
                    .append(p.paymentDate()).append("\"/>\n");
        }
        sb.append("  </Periods>\n");
        sb.append("</TradeConfirmation>\n");
        return sb.toString();
    }

    private static void tag(StringBuilder sb, String name, String value) {
        sb.append("  <").append(name).append('>').append(esc(value)).append("</").append(name).append(">\n");
    }

    static String esc(String v) {
        if (v == null) {
            return "";
        }
        return v.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&apos;");
    }
}
