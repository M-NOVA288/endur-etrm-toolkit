package com.mnova.etrm.adapter;

import com.mnova.etrm.domain.Deal;
import com.mnova.etrm.domain.Party;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Anti-corruption layer between business logic and the ETRM platform.
 *
 * <p>All toolkit logic talks to Endur through this interface only. In production an
 * implementation backed by the vendor's Java plugin API (OpenComponents / JVS) maps these
 * calls onto transaction, static data and user-table operations; that implementation is
 * environment-specific and depends on licensed vendor jars, so it is not part of this repo.
 * {@link InMemoryEndurSession} provides a faithful stand-in for unit tests and local runs.</p>
 *
 * <p>Keeping the platform behind an interface means rules, valuation and interfaces can be
 * unit tested without an Endur environment and survive platform upgrades with minimal change.</p>
 */
public interface EndurSession {

    long nextDealTrackingNum();

    long nextTranNum();

    /** Persists a deal version. A new version supersedes the prior current version. */
    void save(Deal deal);

    Optional<Deal> current(long dealTrackingNum);

    /** All versions of a deal, oldest first. */
    List<Deal> history(long dealTrackingNum);

    List<Deal> query(Predicate<Deal> filter);

    Optional<Deal> findByExternalRef(String externalRef);

    // --- static data -----------------------------------------------------------------

    void registerParty(Party party);

    Optional<Party> party(String id);

    Map<String, Party> parties();

    void authorizeTrader(String trader, String portfolio);

    Set<String> authorizedPortfolios(String trader);

    // --- user tables (Endur USER_ tables for custom data) --------------------------------

    void insertUserTableRow(String table, Map<String, Object> row);

    List<Map<String, Object>> userTable(String table);

    /** Updates matching rows in place; returns the number of rows changed. */
    int updateUserTableRows(String table, Predicate<Map<String, Object>> where, Map<String, Object> set);

    // --- audit ---------------------------------------------------------------------------

    void audit(long dealTrackingNum, String message);

    List<String> auditTrail(long dealTrackingNum);
}
