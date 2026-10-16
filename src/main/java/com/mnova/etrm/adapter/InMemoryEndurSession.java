package com.mnova.etrm.adapter;

import com.mnova.etrm.domain.Deal;
import com.mnova.etrm.domain.Party;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Predicate;

/** Thread-safe in-memory implementation of {@link EndurSession} for tests and local runs. */
public final class InMemoryEndurSession implements EndurSession {

    private final AtomicLong dealSeq = new AtomicLong(100_000);
    private final AtomicLong tranSeq = new AtomicLong(500_000);
    private final Map<Long, List<Deal>> versions = new LinkedHashMap<>();
    private final Map<String, Party> parties = new HashMap<>();
    private final Map<String, Set<String>> traderPortfolios = new HashMap<>();
    private final Map<String, List<Map<String, Object>>> userTables = new HashMap<>();
    private final Map<Long, List<String>> audit = new HashMap<>();

    @Override
    public long nextDealTrackingNum() {
        return dealSeq.incrementAndGet();
    }

    @Override
    public long nextTranNum() {
        return tranSeq.incrementAndGet();
    }

    @Override
    public synchronized void save(Deal deal) {
        List<Deal> list = versions.computeIfAbsent(deal.dealTrackingNum(), k -> new ArrayList<>());
        if (!list.isEmpty() && list.get(list.size() - 1).tranNum() == deal.tranNum()) {
            list.set(list.size() - 1, deal);
        } else {
            list.add(deal);
        }
    }

    @Override
    public synchronized Optional<Deal> current(long dealTrackingNum) {
        List<Deal> list = versions.get(dealTrackingNum);
        return list == null || list.isEmpty() ? Optional.empty() : Optional.of(list.get(list.size() - 1));
    }

    @Override
    public synchronized List<Deal> history(long dealTrackingNum) {
        return List.copyOf(versions.getOrDefault(dealTrackingNum, List.of()));
    }

    @Override
    public synchronized List<Deal> query(Predicate<Deal> filter) {
        List<Deal> out = new ArrayList<>();
        for (List<Deal> list : versions.values()) {
            Deal cur = list.get(list.size() - 1);
            if (filter.test(cur)) {
                out.add(cur);
            }
        }
        return out;
    }

    @Override
    public Optional<Deal> findByExternalRef(String externalRef) {
        if (externalRef == null) {
            return Optional.empty();
        }
        return query(d -> externalRef.equals(d.externalRef())).stream().findFirst();
    }

    @Override
    public synchronized void registerParty(Party party) {
        parties.put(party.id(), party);
    }

    @Override
    public synchronized Optional<Party> party(String id) {
        return Optional.ofNullable(id == null ? null : parties.get(id));
    }

    @Override
    public synchronized Map<String, Party> parties() {
        return Map.copyOf(parties);
    }

    @Override
    public synchronized void authorizeTrader(String trader, String portfolio) {
        traderPortfolios.computeIfAbsent(trader, k -> new HashSet<>()).add(portfolio);
    }

    @Override
    public synchronized Set<String> authorizedPortfolios(String trader) {
        return Set.copyOf(traderPortfolios.getOrDefault(trader, Set.of()));
    }

    @Override
    public synchronized void insertUserTableRow(String table, Map<String, Object> row) {
        userTables.computeIfAbsent(table, k -> new ArrayList<>()).add(new LinkedHashMap<>(row));
    }

    @Override
    public synchronized List<Map<String, Object>> userTable(String table) {
        List<Map<String, Object>> copy = new ArrayList<>();
        for (Map<String, Object> row : userTables.getOrDefault(table, List.of())) {
            copy.add(Collections.unmodifiableMap(new LinkedHashMap<>(row)));
        }
        return Collections.unmodifiableList(copy);
    }

    @Override
    public synchronized int updateUserTableRows(String table, Predicate<Map<String, Object>> where,
                                                Map<String, Object> set) {
        int n = 0;
        for (Map<String, Object> row : userTables.getOrDefault(table, List.of())) {
            if (where.test(Collections.unmodifiableMap(row))) {
                row.putAll(set);
                n++;
            }
        }
        return n;
    }

    @Override
    public synchronized void audit(long dealTrackingNum, String message) {
        audit.computeIfAbsent(dealTrackingNum, k -> new ArrayList<>()).add(LocalDateTime.now() + " " + message);
    }

    @Override
    public synchronized List<String> auditTrail(long dealTrackingNum) {
        return List.copyOf(audit.getOrDefault(dealTrackingNum, List.of()));
    }
}
