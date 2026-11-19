package com.jovx.api.partners.repository;

import com.jovx.api.partners.model.PartnerModels.LocationDefaults;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import javax.sql.DataSource;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** The single default city/neighborhood row (V7). */
@Repository
@Profile("local-postgres")
public final class ReferenceDefaultsRepository {
  private final JdbcTemplate jdbc;

  public ReferenceDefaultsRepository(DataSource source) {
    jdbc = new JdbcTemplate(source);
  }

  /** Empty until administration sets the defaults for the first time. */
  public Optional<LocationDefaults> current() {
    return jdbc
        .query(
            "SELECT d.city_id,c.name,d.address_id,a.name FROM reference_defaults d"
                + " JOIN cities c ON c.id=d.city_id JOIN addresses a ON a.id=d.address_id WHERE d.id=1",
            (r, i) ->
                new LocationDefaults(
                    UUID.fromString(r.getString(1).strip()),
                    r.getString(2),
                    UUID.fromString(r.getString(3).strip()),
                    r.getString(4)))
        .stream()
        .findFirst();
  }

  /** Inserts or replaces the row; the composite foreign key rejects a neighborhood of another city. */
  public void save(UUID city, UUID address, UUID actor, Instant now) {
    jdbc.update(
        "INSERT INTO reference_defaults(id,city_id,address_id,updated_by,updated_at) VALUES (1,?,?,?,?)"
            + " ON CONFLICT (id) DO UPDATE SET city_id=EXCLUDED.city_id,address_id=EXCLUDED.address_id,"
            + "updated_by=EXCLUDED.updated_by,updated_at=EXCLUDED.updated_at",
        city.toString(),
        address.toString(),
        actor.toString(),
        Timestamp.from(now));
  }

  /** Locks the location pair against concurrent reference changes while it is validated and saved. */
  public boolean lockAddressOfCity(UUID address, UUID city) {
    return !jdbc.queryForList(
            "SELECT id FROM addresses WHERE id=? AND city_id=? FOR KEY SHARE",
            address.toString(),
            city.toString())
        .isEmpty();
  }
}
