package com.jovx.api.partners.repository;

import com.jovx.api.partners.model.PartnerModels.*;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import javax.sql.DataSource;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
@Profile("local-postgres")
public final class PartnerRepository {
  private static final String REFERENCE_KEY =
      "lower(btrim(regexp_replace(name, '[[:space:]]+', ' ', 'g')))";
  private final JdbcTemplate jdbc;

  public PartnerRepository(DataSource source) {
    jdbc = new JdbcTemplate(source);
  }

  public boolean categoryExists(UUID category) {
    return count("SELECT COUNT(*) FROM categories WHERE id=?", category) == 1;
  }

  public boolean addressBelongsToCity(UUID address, UUID city) {
    return count("SELECT COUNT(*) FROM addresses WHERE id=? AND city_id=?", address, city) == 1;
  }

  public void insertOwner(UUID id, UUID account, BigDecimal minimum) {
    jdbc.update(
        "INSERT INTO partner_owners(id,account_id,minimum_discount_percent,status) VALUES"
            + " (?,?,?,'PENDING')",
        id.toString(),
        account.toString(),
        minimum);
  }

  public void insertEstablishment(
      UUID id, UUID partner, UUID category, String name, String contact) {
    jdbc.update(
        "INSERT INTO partner_establishments(id,partner_id,category_id,name,contact_phone,status)"
            + " VALUES (?,?,?,?,?,'PENDING')",
        id.toString(),
        partner.toString(),
        category.toString(),
        name,
        contact);
  }

  public void insertBranch(
      UUID id, UUID establishment, String name, UUID city, UUID address, String details) {
    jdbc.update(
        "INSERT INTO"
            + " partner_branches(id,establishment_id,name,city_id,address_id,address_details,status)"
            + " VALUES (?,?,?,?,?,?,'ACTIVE')",
        id.toString(),
        establishment.toString(),
        name,
        city.toString(),
        address.toString(),
        details);
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

  public Optional<Owner> lockOwner(UUID id) {
    return owner("id=?", id, true);
  }

  public Optional<Owner> activeOwner(UUID account, boolean lock) {
    return owner("account_id=? AND status='ACTIVE'", account, lock);
  }

  public int establishmentCount(UUID partner) {
    return count("SELECT COUNT(*) FROM partner_establishments WHERE partner_id=?", partner);
  }

  public void activateRegistration(UUID partner) {
    jdbc.update("UPDATE partner_owners SET status='ACTIVE' WHERE id=?", partner.toString());
    jdbc.update(
        "UPDATE partner_establishments SET status='ACTIVE' WHERE partner_id=? AND status='PENDING'",
        partner.toString());
  }

  public List<Establishment> establishments(UUID partner) {
    return jdbc.query(
        "SELECT id,name FROM partner_establishments WHERE partner_id=? AND status='ACTIVE' ORDER BY"
            + " id",
        (r, i) ->
            new Establishment(
                UUID.fromString(r.getString(1)),
                r.getString(2),
                branches(UUID.fromString(r.getString(1)))),
        partner.toString());
  }

  private List<Branch> branches(UUID establishment) {
    return jdbc.query(
        "SELECT id,name FROM partner_branches WHERE establishment_id=? AND status='ACTIVE' ORDER BY"
            + " name,id",
        (r, i) -> new Branch(UUID.fromString(r.getString(1)), r.getString(2)),
        establishment.toString());
  }

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

  public boolean lockActiveBranch(UUID branch, UUID owner) {
    return !jdbc.queryForList(
            "SELECT b.id FROM partner_branches b JOIN partner_establishments e ON"
                + " e.id=b.establishment_id WHERE b.id=? AND e.partner_id=? AND b.status='ACTIVE'"
                + " AND e.status='ACTIVE' FOR UPDATE",
            branch.toString(),
            owner.toString())
        .isEmpty();
  }

  public void insertEmployee(UUID user, UUID owner) {
    jdbc.update(
        "INSERT INTO partner_employees(user_id,related_entity,related_id) VALUES (?,'PARTNER',?)",
        user.toString(),
        owner.toString());
  }

  public void assignBranch(UUID employee, UUID branch) {
    jdbc.update(
        "INSERT INTO partner_employee_branches(user_id,branch_id) VALUES (?,?)",
        employee.toString(),
        branch.toString());
  }

  public boolean lockEmployee(UUID employee, UUID owner) {
    return !jdbc.queryForList(
            "SELECT user_id FROM partner_employees WHERE user_id=? AND related_id=? FOR UPDATE",
            employee.toString(),
            owner.toString())
        .isEmpty();
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

  public boolean employeeCanLogin(UUID account) {
    return exists(
        "SELECT EXISTS(SELECT 1 FROM partner_employees x JOIN partner_owners p ON p.id=x.related_id"
            + " JOIN partner_employee_branches eb ON eb.user_id=x.user_id JOIN partner_branches b"
            + " ON b.id=eb.branch_id JOIN partner_establishments e ON e.id=b.establishment_id AND"
            + " e.partner_id=p.id WHERE x.user_id=? AND p.status='ACTIVE' AND e.status='ACTIVE' AND"
            + " b.status='ACTIVE')",
        account);
  }

  public boolean ownerCanLogin(UUID account) {
    return exists(
        "SELECT EXISTS(SELECT 1 FROM partner_owners p JOIN partner_establishments e ON"
            + " e.partner_id=p.id WHERE p.account_id=? AND p.status='ACTIVE' AND"
            + " e.status='ACTIVE')",
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
