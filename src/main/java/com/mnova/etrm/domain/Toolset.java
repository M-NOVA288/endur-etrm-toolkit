package com.mnova.etrm.domain;

/**
 * Product families, mirroring how Endur groups instruments into toolsets.
 * The toolset drives which valuation model, settlement logic and ops rules apply.
 */
public enum Toolset {
    /** Physical natural gas (e.g. Henry Hub / pipeline delivery). Volumes in MMBtu. */
    PHYS_GAS(true, true),
    /** Physical power (e.g. ERCOT North Hub block). Volumes in MWh. */
    PHYS_POWER(true, true),
    /** Financially settled fixed-for-floating commodity swap. */
    COM_SWAP(false, true),
    /** Exchange-traded commodity future (NYMEX/ICE). Daily margined, not discounted. */
    COM_FUT(false, false);

    private final boolean physical;
    private final boolean discounted;

    Toolset(boolean physical, boolean discounted) {
        this.physical = physical;
        this.discounted = discounted;
    }

    public boolean isPhysical() {
        return physical;
    }

    /** Futures are margined daily, so their MtM is not discounted. */
    public boolean isDiscounted() {
        return discounted;
    }
}
