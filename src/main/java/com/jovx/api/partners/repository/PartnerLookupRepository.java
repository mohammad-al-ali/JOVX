package com.jovx.api.partners.repository;

import com.jovx.api.partners.api.PartnerLookup.PartnerRef;
import com.jovx.api.partners.api.PartnerLookup.StoreRef;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;
import javax.sql.DataSource;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Read-only partner facts behind partners::api. Module-owned tables only. */
@Repository
@Profile("local-postgres")
public final class PartnerLookupRepository {
  // Every partner is registered with at least one store; its name is the store names joined.
  private static final String SELECT =
      "SELECT p.id,p.status,COALESCE((SELECT string_agg(s.name,'، ' ORDER BY s.name)"
          + " FROM partner_stores s WHERE s.partner_id=p.id),''),p.minimum_discount_percent"
          + " FROM partner_owners p ";
  private static final String STORE = "SELECT id,partner_id,name,status FROM partner_stores ";
  private final JdbcTemplate jdbc;

  public PartnerLookupRepository(DataSource source) {
    jdbc = new JdbcTemplate(source);
  }

  public Optional<PartnerRef> ownedBy(UUID account) {
    return one(SELECT + "WHERE p.account_id=?", account);
  }

  public Optional<PartnerRef> employerOf(UUID employee) {
    return one(SELECT + "JOIN partner_employees x ON x.related_id=p.id WHERE x.user_id=?", employee);
  }

  public Optional<PartnerRef> byId(UUID partner) {
    return one(SELECT + "WHERE p.id=?", partner);
  }

  public List<PartnerRef> all() {
    return jdbc.query(SELECT + "ORDER BY 3", (r, i) -> ref(r));
  }

  public Optional<StoreRef> store(UUID store) {
    return jdbc.query(STORE + "WHERE id=?", (r, i) -> storeRef(r), store.toString()).stream().findFirst();
  }

  public List<StoreRef> stores(UUID partner) {
    return jdbc.query(STORE + "WHERE partner_id=? ORDER BY name,id", (r, i) -> storeRef(r), partner.toString());
  }

  public Set<UUID> employeeStores(UUID employee) {
    return new HashSet<>(jdbc.query("SELECT store_id FROM partner_employee_stores WHERE user_id=?",
        (r, i) -> UUID.fromString(r.getString(1).strip()), employee.toString()));
  }

  private Optional<PartnerRef> one(String sql, UUID id) {
    return jdbc.query(sql, (r, i) -> ref(r), id.toString()).stream().findFirst();
  }

  private static PartnerRef ref(ResultSet r) throws SQLException {
    return new PartnerRef(UUID.fromString(r.getString(1).strip()), r.getString(2), r.getString(3), r.getBigDecimal(4));
  }

  private static StoreRef storeRef(ResultSet r) throws SQLException {
    return new StoreRef(UUID.fromString(r.getString(1).strip()), UUID.fromString(r.getString(2).strip()),
        r.getString(3), r.getString(4));
  }
}
