package com.jovx.api.partners.repository;

import com.jovx.api.partners.model.PartnerModels.*;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Time;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import javax.sql.DataSource;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.stereotype.Repository;

/** Partner-owned tables only: owners, stores, store hours, employees and their stores, events. */
@Repository
@Profile("local-postgres")
public final class PartnerRepository {
  private static final String REFERENCE_KEY =
      "lower(btrim(regexp_replace(name, '[[:space:]]+', ' ', 'g')))";
  private static final String STORE_DETAIL =
      "SELECT s.id,s.name,s.category_id,k.name,s.city_id,c.name,s.address_id,a.name,"
          + "s.address_details,s.contact_phone,s.latitude,s.longitude,s.description,s.logo_key,"
          + "s.status FROM partner_stores s JOIN categories k ON k.id=s.category_id"
          + " JOIN cities c ON c.id=s.city_id JOIN addresses a ON a.id=s.address_id ";
  private final JdbcTemplate jdbc;

  public PartnerRepository(DataSource source) {
    jdbc = new JdbcTemplate(source);
  }

  // ---- reference checks ----

  public boolean categoryExists(UUID category) {
    return count("SELECT COUNT(*) FROM categories WHERE id=?", category) == 1;
  }

  public boolean addressBelongsToCity(UUID address, UUID city) {
    return count("SELECT COUNT(*) FROM addresses WHERE id=? AND city_id=?", address, city) == 1;
  }

  // ---- owners ----

  public void insertOwner(UUID id, UUID account, BigDecimal minimum) {
    jdbc.update(
        "INSERT INTO partner_owners(id,account_id,minimum_discount_percent,status) VALUES"
            + " (?,?,?,'PENDING')",
        id.toString(),
        account.toString(),
        minimum);
  }

  private Optional<Owner> owner(String predicate, UUID id, boolean lock) {
    return jdbc
        .query(
            "SELECT id,account_id,status,minimum_discount_percent FROM partner_owners WHERE "
                + predicate
                + (lock ? " FOR UPDATE" : ""),
            (r, i) ->
                new Owner(
                    UUID.fromString(r.getString(1)),
                    UUID.fromString(r.getString(2)),
                    r.getString(3),
                    r.getBigDecimal(4)),
            id.toString())
        .stream()
        .findFirst();
  }

  public Optional<Owner> findOwner(UUID id) {
    return owner("id=?", id, false);
  }

  public Optional<Owner> lockOwner(UUID id) {
    return owner("id=?", id, true);
  }

  public Optional<Owner> activeOwner(UUID account, boolean lock) {
    return owner("account_id=? AND status='ACTIVE'", account, lock);
  }

  /** Activates the partner and every store still pending with it. */
  public void activateRegistration(UUID partner) {
    jdbc.update("UPDATE partner_owners SET status='ACTIVE' WHERE id=?", partner.toString());
    jdbc.update(
        "UPDATE partner_stores SET status='ACTIVE' WHERE partner_id=? AND status='PENDING'",
        partner.toString());
  }

  public void updateDiscount(UUID partner, BigDecimal minimum) {
    jdbc.update(
        "UPDATE partner_owners SET minimum_discount_percent=? WHERE id=?",
        minimum,
        partner.toString());
  }

  /**
   * One page of partners with store names and store count in a single query. With a search text,
   * matches a store name or one of the given owner accounts.
   */
  public List<OwnerListing> ownerPage(
      String search, Collection<UUID> matchingAccounts, int offset, int limit) {
    var sql =
        new StringBuilder(
            "SELECT p.id,p.account_id,p.status,p.minimum_discount_percent,"
                + " (SELECT array_agg(s.name ORDER BY s.name) FROM partner_stores s"
                + " WHERE s.partner_id=p.id),"
                + " (SELECT COUNT(*) FROM partner_stores s WHERE s.partner_id=p.id),"
                + " (SELECT min(s.name) FROM partner_stores s WHERE s.partner_id=p.id) AS"
                + " sort_name FROM partner_owners p");
    var args = new ArrayList<Object>();
    if (search != null) {
      sql.append(
          " WHERE (EXISTS (SELECT 1 FROM partner_stores s WHERE s.partner_id=p.id AND"
              + " lower(s.name) LIKE ? ESCAPE '\\')");
      args.add(
          "%"
              + search.toLowerCase(Locale.ROOT)
                  .replace("\\", "\\\\")
                  .replace("%", "\\%")
                  .replace("_", "\\_")
              + "%");
      if (!matchingAccounts.isEmpty()) {
        sql.append(" OR p.account_id IN (")
            .append(String.join(",", Collections.nCopies(matchingAccounts.size(), "?")))
            .append(")");
        matchingAccounts.forEach(a -> args.add(a.toString()));
      }
      sql.append(")");
    }
    sql.append(" ORDER BY sort_name NULLS LAST, p.id OFFSET ? LIMIT ?");
    args.add(Math.max(0, offset));
    args.add(Math.max(1, Math.min(limit, 100)));
    return jdbc.query(
        sql.toString(),
        (r, i) -> {
          var names = r.getArray(5);
          return new OwnerListing(
              UUID.fromString(r.getString(1)),
              UUID.fromString(r.getString(2)),
              r.getString(3),
              r.getBigDecimal(4),
              names == null ? List.of() : List.of((String[]) names.getArray()),
              r.getInt(6));
        },
        args.toArray());
  }

  // ---- stores ----

  public void insertStore(UUID id, UUID partner, StoreData d, String status, Instant now) {
    jdbc.update(
        "INSERT INTO partner_stores(id,partner_id,name,category_id,city_id,address_id,"
            + "address_details,contact_phone,latitude,longitude,description,status,created_at)"
            + " VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)",
        id.toString(),
        partner.toString(),
        d.name(),
        d.categoryId().toString(),
        d.cityId().toString(),
        d.addressId().toString(),
        d.addressDetails(),
        d.contactPhone(),
        d.latitude(),
        d.longitude(),
        d.description(),
        status,
        Timestamp.from(now));
  }

  public void updateStore(UUID id, StoreData d) {
    jdbc.update(
        "UPDATE partner_stores SET name=?,category_id=?,city_id=?,address_id=?,address_details=?,"
            + "contact_phone=?,latitude=?,longitude=?,description=? WHERE id=?",
        d.name(),
        d.categoryId().toString(),
        d.cityId().toString(),
        d.addressId().toString(),
        d.addressDetails(),
        d.contactPhone(),
        d.latitude(),
        d.longitude(),
        d.description(),
        id.toString());
  }

  public int storeCount(UUID partner) {
    return count("SELECT COUNT(*) FROM partner_stores WHERE partner_id=?", partner);
  }

  /** Locks the store only when it belongs to the partner; returns its status. */
  public Optional<String> lockStore(UUID store, UUID partner) {
    return jdbc
        .queryForList(
            "SELECT status FROM partner_stores WHERE id=? AND partner_id=? FOR UPDATE",
            String.class,
            store.toString(),
            partner.toString())
        .stream()
        .findFirst();
  }

  public void setStoreStatus(UUID id, String status) {
    jdbc.update("UPDATE partner_stores SET status=? WHERE id=?", status, id.toString());
  }

  public void replaceHours(UUID store, List<StoreHours> week) {
    jdbc.update("DELETE FROM partner_store_hours WHERE store_id=?", store.toString());
    for (var day : week)
      jdbc.update(
          "INSERT INTO partner_store_hours(store_id,weekday,closed,opens_at,closes_at)"
              + " VALUES (?,?,?,?,?)",
          store.toString(),
          day.weekday(),
          day.closed(),
          day.opensAt() == null ? null : Time.valueOf(day.opensAt()),
          day.closesAt() == null ? null : Time.valueOf(day.closesAt()));
  }

  /** Active stores of a partner, for employee assignment. */
  public List<StoreRef> activeStores(UUID partner) {
    return jdbc.query(
        "SELECT id,name FROM partner_stores WHERE partner_id=? AND status='ACTIVE'"
            + " ORDER BY name,id",
        (r, i) -> new StoreRef(UUID.fromString(r.getString(1)), r.getString(2)),
        partner.toString());
  }

  /** Every store of one partner, any status, with reference names and weekly hours. */
  public List<StoreDetail> storeDetails(UUID partner) {
    var hours = hoursOf("SELECT h.store_id,h.weekday,h.closed,h.opens_at,h.closes_at FROM"
        + " partner_store_hours h JOIN partner_stores s ON s.id=h.store_id WHERE s.partner_id=?",
        partner.toString());
    return jdbc.query(
        STORE_DETAIL + "WHERE s.partner_id=? ORDER BY s.name,s.id",
        (r, i) -> detail(r, hours),
        partner.toString());
  }

  /**
   * Guest directory: active stores of active partners. city null = every city; otherwise only
   * stores located in that city. Two queries in total (stores, then all their hours).
   */
  public List<PublicStore> publicDirectory(UUID city) {
    String filter = city == null ? "" : " AND s.city_id=?";
    Object[] args = city == null ? new Object[0] : new Object[] {city.toString()};
    var hours = hoursOf("SELECT h.store_id,h.weekday,h.closed,h.opens_at,h.closes_at FROM"
        + " partner_store_hours h JOIN partner_stores s ON s.id=h.store_id JOIN partner_owners p"
        + " ON p.id=s.partner_id WHERE s.status='ACTIVE' AND p.status='ACTIVE'" + filter, args);
    return jdbc.query(
        "SELECT s.id,s.name,k.name,p.minimum_discount_percent,s.contact_phone,s.city_id,c.name,"
            + "a.name,s.address_details,s.latitude,s.longitude,s.description FROM partner_stores s"
            + " JOIN partner_owners p ON p.id=s.partner_id JOIN categories k ON k.id=s.category_id"
            + " JOIN cities c ON c.id=s.city_id JOIN addresses a ON a.id=s.address_id"
            + " WHERE s.status='ACTIVE' AND p.status='ACTIVE'" + filter + " ORDER BY k.name,s.name,s.id",
        (r, i) -> {
          var id = UUID.fromString(r.getString(1));
          return new PublicStore(id, r.getString(2), r.getString(3), r.getBigDecimal(4),
              r.getString(5), UUID.fromString(r.getString(6)), r.getString(7), r.getString(8),
              r.getString(9), r.getBigDecimal(10), r.getBigDecimal(11), r.getString(12),
              hours.getOrDefault(id, List.of()));
        },
        args);
  }

  private Map<UUID, List<StoreHours>> hoursOf(String sql, Object... args) {
    var result = new HashMap<UUID, List<StoreHours>>();
    jdbc.query(sql + " ORDER BY h.weekday", (RowCallbackHandler) r -> {
      Time opens = r.getTime(4), closes = r.getTime(5);
      result.computeIfAbsent(UUID.fromString(r.getString(1)), k -> new ArrayList<>())
          .add(new StoreHours(r.getInt(2), r.getBoolean(3),
              opens == null ? null : opens.toLocalTime(), closes == null ? null : closes.toLocalTime()));
    }, args);
    return result;
  }

  private static StoreDetail detail(ResultSet r, Map<UUID, List<StoreHours>> hours)
      throws SQLException {
    var id = UUID.fromString(r.getString(1));
    return new StoreDetail(
        id,
        r.getString(2),
        UUID.fromString(r.getString(3)),
        r.getString(4),
        UUID.fromString(r.getString(5)),
        r.getString(6),
        UUID.fromString(r.getString(7)),
        r.getString(8),
        r.getString(9),
        r.getString(10),
        r.getBigDecimal(11),
        r.getBigDecimal(12),
        r.getString(13),
        r.getString(14),
        r.getString(15),
        hours.getOrDefault(id, List.of()));
  }

  // ---- reference lists ----

  public List<Reference> cities() {
    return jdbc.query(
        "SELECT id,name FROM cities ORDER BY name,id",
        (r, i) -> new Reference(UUID.fromString(r.getString(1)), r.getString(2)));
  }

  public List<Address> addresses() {
    return jdbc.query(
        "SELECT id,city_id,name FROM addresses ORDER BY name,id",
        (r, i) ->
            new Address(
                UUID.fromString(r.getString(1)), UUID.fromString(r.getString(2)), r.getString(3)));
  }

  public List<Reference> categories() {
    return jdbc.query(
        "SELECT id,name FROM categories ORDER BY name,id",
        (r, i) -> new Reference(UUID.fromString(r.getString(1)), r.getString(2)));
  }

  public Reference saveCity(String name) {
    return saveReference("cities", name);
  }

  public Reference saveCategory(String name) {
    return saveReference("categories", name);
  }

  private Reference saveReference(String table, String name) {
    // Table is supplied only by the two fixed methods above, never by a request.
    jdbc.update(
        "INSERT INTO " + table + "(id,name) VALUES (?,?) ON CONFLICT DO NOTHING",
        UUID.randomUUID().toString(),
        name);
    // A separate READ COMMITTED statement sees a concurrent winner after INSERT waited.
    return jdbc.queryForObject(
        "SELECT id,name FROM " + table + " WHERE " + REFERENCE_KEY + "=lower(?)",
        (r, i) -> new Reference(UUID.fromString(r.getString(1)), r.getString(2)),
        name);
  }

  public boolean lockCity(UUID city) {
    return !jdbc.queryForList("SELECT id FROM cities WHERE id=? FOR KEY SHARE", city.toString())
        .isEmpty();
  }

  public Address saveAddress(UUID city, String name) {
    jdbc.update(
        "INSERT INTO addresses(id,city_id,name) VALUES (?,?,?) ON CONFLICT DO NOTHING",
        UUID.randomUUID().toString(),
        city.toString(),
        name);
    return jdbc.queryForObject(
        "SELECT id,city_id,name FROM addresses WHERE city_id=? AND " + REFERENCE_KEY + "=lower(?)",
        (r, i) ->
            new Address(
                UUID.fromString(r.getString(1)), UUID.fromString(r.getString(2)), r.getString(3)),
        city.toString(),
        name);
  }

  // ---- employees ----

  /** Locks an ACTIVE store of the given partner (employee assignment target). */
  public boolean lockActiveStore(UUID store, UUID owner) {
    return !jdbc.queryForList(
            "SELECT id FROM partner_stores WHERE id=? AND partner_id=? AND status='ACTIVE'"
                + " FOR UPDATE",
            store.toString(),
            owner.toString())
        .isEmpty();
  }

  public void insertEmployee(UUID user, UUID owner) {
    jdbc.update(
        "INSERT INTO partner_employees(user_id,related_entity,related_id) VALUES (?,'PARTNER',?)",
        user.toString(),
        owner.toString());
  }

  public void assignStore(UUID employee, UUID store) {
    jdbc.update(
        "INSERT INTO partner_employee_stores(user_id,store_id) VALUES (?,?)",
        employee.toString(),
        store.toString());
  }

  public void clearStores(UUID employee) {
    jdbc.update("DELETE FROM partner_employee_stores WHERE user_id=?", employee.toString());
  }

  public boolean lockEmployee(UUID employee, UUID owner) {
    return !jdbc.queryForList(
            "SELECT user_id FROM partner_employees WHERE user_id=? AND related_id=? FOR UPDATE",
            employee.toString(),
            owner.toString())
        .isEmpty();
  }

  public List<EmployeeAssignment> employees(UUID owner) {
    var stores = new LinkedHashMap<UUID, Set<UUID>>();
    jdbc.query(
        "SELECT x.user_id,es.store_id FROM partner_employees x LEFT JOIN"
            + " partner_employee_stores es ON es.user_id=x.user_id WHERE x.related_id=? ORDER"
            + " BY x.user_id",
        (RowCallbackHandler)
            r -> {
              var set =
                  stores.computeIfAbsent(UUID.fromString(r.getString(1)), k -> new LinkedHashSet<>());
              if (r.getString(2) != null) set.add(UUID.fromString(r.getString(2)));
            },
        owner.toString());
    return stores.entrySet().stream()
        .map(e -> new EmployeeAssignment(e.getKey(), Set.copyOf(e.getValue())))
        .toList();
  }

  public void audit(UUID partner, UUID actor, String action, Instant now) {
    jdbc.update(
        "INSERT INTO partner_events(id,partner_id,actor_id,action,created_at) VALUES (?,?,?,?,?)",
        UUID.randomUUID().toString(),
        partner.toString(),
        actor.toString(),
        action,
        Timestamp.from(now));
  }

  // ---- login eligibility ----

  /** An employee works only while its partner is active and it has an active assigned store. */
  public boolean employeeCanLogin(UUID account) {
    return exists(
        "SELECT EXISTS(SELECT 1 FROM partner_employees x JOIN partner_owners p ON p.id=x.related_id"
            + " JOIN partner_employee_stores es ON es.user_id=x.user_id JOIN partner_stores s ON"
            + " s.id=es.store_id AND s.partner_id=p.id WHERE x.user_id=? AND p.status='ACTIVE' AND"
            + " s.status='ACTIVE')",
        account);
  }

  /**
   * An active partner may log in even when all its stores are suspended, so it can manage them
   * (user decision 2026-11-19); selling needs an active store.
   */
  public boolean ownerCanLogin(UUID account) {
    return exists(
        "SELECT EXISTS(SELECT 1 FROM partner_owners WHERE account_id=? AND status='ACTIVE')",
        account);
  }

  public boolean hasOwnerAccount(UUID account) {
    return exists("SELECT EXISTS(SELECT 1 FROM partner_owners WHERE account_id=?)", account);
  }

  public boolean hasEmployeeAccount(UUID account) {
    return exists("SELECT EXISTS(SELECT 1 FROM partner_employees WHERE user_id=?)", account);
  }

  private boolean exists(String sql, UUID id) {
    return Boolean.TRUE.equals(jdbc.queryForObject(sql, Boolean.class, id.toString()));
  }

  private int count(String sql, UUID... ids) {
    return jdbc.queryForObject(
        sql,
        Integer.class,
        (Object[]) Arrays.stream(ids).map(UUID::toString).toArray(String[]::new));
  }
}
