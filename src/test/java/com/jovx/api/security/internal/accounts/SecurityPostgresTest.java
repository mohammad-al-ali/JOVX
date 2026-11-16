package com.jovx.api.security.internal.accounts;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.crypto.password.PasswordEncoder;

/** Explicit local-only suite; creates and drops only its own random test database. */
@EnabledIfEnvironmentVariable(named = "JOVX_POSTGRES_TEST_ADMIN_PASSWORD_FILE", matches = ".+")
class SecurityPostgresTest {
    private static final String DATABASE = "jovx_identity_test_" + UUID.randomUUID().toString().replace("-", "");
    private static final String URL = "jdbc:postgresql://127.0.0.1:15432/";
    private static final String OPTIONS = "?connectTimeout=5&socketTimeout=10&options=-c%20TimeZone=UTC";
    private static DriverManagerDataSource source;
    private static JdbcTemplate admin;
    private static JdbcTemplate jdbc;
    private static boolean created;
    private static Flyway flyway;
    private static final String CITY=UUID.randomUUID().toString(),ADDRESS=UUID.randomUUID().toString(),CATEGORY=UUID.randomUUID().toString();
    private final SixDigitPinEncoder encoder = new SixDigitPinEncoder();

    @BeforeAll static void initialize() throws Exception {
        String password = Files.readString(Path.of(System.getenv("JOVX_POSTGRES_TEST_ADMIN_PASSWORD_FILE"))).trim();
        var server = new DriverManagerDataSource(URL + "postgres" + OPTIONS, "postgres", password);
        admin = new JdbcTemplate(server);
        String directory = admin.queryForObject("SHOW data_directory", String.class).replace('\\', '/').toLowerCase();
        assertTrue(directory.endsWith("/outputs/jovx/.local/postgres-native/data"), "Tests require the isolated native instance");
        assertTrue(DATABASE.matches("jovx_identity_test_[a-f0-9]{32}"));
        admin.execute("CREATE DATABASE " + DATABASE + " TEMPLATE template0 ENCODING 'UTF8' LC_COLLATE 'C' LC_CTYPE 'C'");
        created = true;
        source = new DriverManagerDataSource(URL + DATABASE + OPTIONS, "postgres", password);
        jdbc = new JdbcTemplate(source);
        flyway = Flyway.configure().dataSource(source).locations("classpath:db/postgresql")
            .cleanDisabled(true).validateMigrationNaming(true).load();
        flyway.migrate();
        jdbc.update("INSERT INTO cities(id,name) VALUES (?,'دمشق')",CITY);
        jdbc.update("INSERT INTO addresses(id,city_id,name) VALUES (?,?,'المزة')",ADDRESS,CITY);
        jdbc.update("INSERT INTO categories(id,name) VALUES (?,'مطاعم')",CATEGORY);
    }

    @AfterAll static void cleanup() {
        if (created && DATABASE.matches("jovx_identity_test_[a-f0-9]{32}")) admin.execute("DROP DATABASE " + DATABASE);
    }

    @Test void canonicalPhoneIsUniqueAcrossDifferentRoles() {
        var store = new JdbcAccountStore(source, encoder);
        UUID first = UUID.randomUUID();
        store.create(first, "Test person", "+1 (202) 555-0101", AccountRole.MEMBER, "048291");
        assertThrows(DuplicateKeyException.class, () -> store.create(UUID.randomUUID(), "Test person", "0012025550101", AccountRole.PARTNER, "938271"));
        var principal = (AccountPrincipal) store.loadUserByUsername("+١٢٠٢٥٥٥٠١٠١");
        assertEquals(first, principal.accountId());
        assertEquals(AccountRole.MEMBER, principal.role());
    }

    @Test void concurrentAccountCreationHasExactlyOneWinner() throws Exception {
        var ready = new CyclicBarrier(2);
        PasswordEncoder coordinated = new PasswordEncoder() {
            public String encode(CharSequence value) {
                String hash = encoder.encode(value);
                try { ready.await(10, TimeUnit.SECONDS); } catch (Exception ex) { throw new IllegalStateException(ex); }
                return hash;
            }
            public boolean matches(CharSequence value, String hash) { return encoder.matches(value, hash); }
        };
        var store = new JdbcAccountStore(source, coordinated);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> tryCreate(store, "+12025550102", AccountRole.PARTNER_EMPLOYEE));
            var second = executor.submit(() -> tryCreate(store, "+1 202 555 0102", AccountRole.MEMBER));
            assertEquals(1, first.get(20, TimeUnit.SECONDS) + second.get(20, TimeUnit.SECONDS));
        }
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM users WHERE phone_e164='+12025550102'", Integer.class));
    }

    private int tryCreate(JdbcAccountStore store, String phone, AccountRole role) {
        try { store.create(UUID.randomUUID(), "Test person", phone, role, "048291"); return 1; }
        catch (DuplicateKeyException ex) { return 0; }
    }

    @Test void providerLoadsServerRoleRejectsWrongPinAndSuspendedAccount() {
        var store = new JdbcAccountStore(source, encoder);
        UUID id = UUID.randomUUID();
        store.create(id, "Test person", "+12025550103", AccountRole.PARTNER_EMPLOYEE, "048291");
        var provider = new DaoAuthenticationProvider(store);
        provider.setPasswordEncoder(encoder);
        var result = provider.authenticate(UsernamePasswordAuthenticationToken.unauthenticated("+1 202 555 0103", "048291"));
        assertEquals(id, ((AccountPrincipal) result.getPrincipal()).accountId());
        assertTrue(result.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("ROLE_PARTNER_EMPLOYEE")));
        assertFalse(result.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("sale.confirm")));
        assertFalse(result.getPrincipal().toString().contains("048291"));
        assertThrows(BadCredentialsException.class, () -> provider.authenticate(UsernamePasswordAuthenticationToken.unauthenticated("+12025550103", "048292")));
        assertThrows(BadCredentialsException.class, () -> provider.authenticate(UsernamePasswordAuthenticationToken.unauthenticated("+12025550199", "048291")));
        jdbc.update("UPDATE users SET status='SUSPENDED' WHERE id=?", id.toString());
        assertThrows(DisabledException.class, () -> provider.authenticate(UsernamePasswordAuthenticationToken.unauthenticated("+12025550103", "048291")));
    }

    @Test void credentialFailureRollsBackAccountInsertion() {
        PasswordEncoder broken = new PasswordEncoder() {
            public String encode(CharSequence value) { return "bad"; }
            public boolean matches(CharSequence value, String hash) { return false; }
        };
        var store = new JdbcAccountStore(source, broken);
        UUID id = UUID.randomUUID();
        assertThrows(DataAccessException.class, () -> store.create(id, "Test person", "+12025550104", AccountRole.PARTNER_EMPLOYEE, "048291"));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM users WHERE id=?", Integer.class, id.toString()));
    }

    @Test void databaseRejectsMissingRolesMalformedPhonesAndBlankNames() {
        String sql="INSERT INTO users (id,name,phone_e164,role_id,status,pin_hash) VALUES (?,?,?,?,'ACTIVE',?)";
        String hash=encoder.encode("048291");
        assertThrows(DataAccessException.class, () -> jdbc.update(sql,UUID.randomUUID().toString(),"Person","+12025550105",999,hash));
        assertThrows(DataAccessException.class, () -> jdbc.update(sql,UUID.randomUUID().toString(),"Person","2025550105",4,hash));
        assertThrows(DataAccessException.class, () -> jdbc.update(sql,UUID.randomUUID().toString(),"   ","+12025550105",4,hash));
    }

    @Test void aPersonsNameAndPinHashAreStoredTogetherAndLeadingZeroStillAuthenticates() {
        var store=new JdbcAccountStore(source,encoder);var id=UUID.randomUUID();
        store.create(id,"أحمد محمد","+12025550106",AccountRole.ADMIN,"012345");
        var row=jdbc.queryForMap("SELECT name,pin_hash FROM users WHERE id=?",id.toString());
        assertEquals("أحمد محمد",row.get("name"));assertNotEquals("012345",row.get("pin_hash"));
        assertTrue(encoder.matches("012345",(String)row.get("pin_hash")));
        assertFalse(encoder.matches("12345",(String)row.get("pin_hash")));
        assertThrows(IllegalArgumentException.class,()->store.create(UUID.randomUUID()," ","+12025550107",AccountRole.ADMIN,"012345"));
    }

    @Test void migrationIsValidAndRepeatableWithoutReapplyingDdl() {
        assertDoesNotThrow(() -> flyway.validate());
        assertEquals(0, flyway.migrate().migrationsExecuted);
    }

    @Test void referenceTablesPreserveTheirIdsNamesAndCityRelationship() {
        assertEquals("دمشق",jdbc.queryForObject("SELECT name FROM cities WHERE id=?",String.class,CITY));
        assertEquals("المزة",jdbc.queryForObject("SELECT name FROM addresses WHERE id=? AND city_id=?",String.class,ADDRESS,CITY));
        assertEquals("مطاعم",jdbc.queryForObject("SELECT name FROM categories WHERE id=?",String.class,CATEGORY));
        assertThrows(DataAccessException.class,()->jdbc.update("INSERT INTO addresses(id,city_id,name) VALUES (?,?,'حي')",UUID.randomUUID().toString(),UUID.randomUUID().toString()));
    }

    @Test void baselineRejectsInvalidStatesAndRoleLevelsWithoutLosingExistingAccounts() {
        UUID id=UUID.randomUUID();
        new JdbcAccountStore(source,encoder).create(id,"Test person","+12025550108",AccountRole.ADMIN,"012345");
        assertThrows(DataAccessException.class,()->jdbc.update("UPDATE users SET status='UNKNOWN' WHERE id=?",id.toString()));
        assertEquals("ACTIVE",jdbc.queryForObject("SELECT status FROM users WHERE id=?",String.class,id.toString()));
        assertThrows(DataAccessException.class,()->jdbc.update("INSERT INTO role_permissions(role_id,permission_id,level) VALUES (2,11,'ADMIN')"));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM role_permissions WHERE role_id=2 AND permission_id=11",Integer.class));
    }
}
