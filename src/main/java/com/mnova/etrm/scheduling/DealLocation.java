package com.mnova.etrm.scheduling;

/**
 * Where a physical gas deal flows: a buy is supply at a receipt point; a sell is market
 * at a delivery point. Kept separate from {@code Deal} the way delivery/location detail
 * lives on a deal's physical leg rather than its financial header.
 */
public record DealLocation(long dealTrackingNum, String pointId) {
}
