package com.jovx.api.partners.repository;

import com.jovx.api.partners.api.PartnerLookup.PartnerRef;
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
          + " FROM partner_stores s WHERE s.partner_id=p.id),'')"
          + " FROM partner_owners p ";
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

  private Optional<PartnerRef> one(String sql, UUID id) {
    return jdbc.query(sql, (r, i) -> ref(r), id.toString()).stream().findFirst();
  }

  private static PartnerRef ref(ResultSet r) throws SQLException {
    return new PartnerRef(UUID.fromString(r.getString(1).strip()), r.getString(2), r.getString(3));
  }
}
