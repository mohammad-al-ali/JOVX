package com.jovx.api.acceptance;

import static org.junit.jupiter.api.Assertions.*;
import com.jovx.api.security.internal.accounts.*;
import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.security.*;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.*;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.*;
import org.springframework.test.annotation.DirtiesContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Given/When/Then acceptance examples against real HTTP, Spring Security and isolated PostgreSQL.
 * No mocked repository, JWT validator, transaction, or password encoder. */
@EnabledIfEnvironmentVariable(named="JOVX_POSTGRES_TEST_ADMIN_PASSWORD_FILE",matches=".+")
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("local-postgres")
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
class PartnerJourneyTest {
    static final String DATABASE="jovx_partner_test_"+UUID.randomUUID().toString().replace("-","");
    static final String OPTIONS="?connectTimeout=5&socketTimeout=15&options=-c%20TimeZone=UTC";
    static final AtomicInteger PHONE=new AtomicInteger(120);
    static final JsonMapper JSON=JsonMapper.builder().build();
    static final Instant START=Instant.now().truncatedTo(ChronoUnit.SECONDS);
    static final TestClock TIME=new TestClock();
    static DriverManagerDataSource source;
    static JdbcTemplate server;
    static boolean created;
    static Path publicKey,privateKey;
    static final Path IMAGES=tempImages();
    static Path tempImages(){try{var d=Files.createTempDirectory("jovx-images-");d.toFile().deleteOnExit();return d;}catch(Exception e){throw new IllegalStateException(e);}}
    static final UUID CITY=UUID.randomUUID(),OTHER_CITY=UUID.randomUUID(),ADDRESS=UUID.randomUUID(),CATEGORY=UUID.randomUUID();
    @LocalServerPort int port;
    @Autowired JdbcAccountStore accounts;
    @Autowired com.jovx.api.notifications.service.NotificationDispatcher dispatcher;
    @Autowired com.jovx.api.wallets.service.LowBalanceAlerts lowBalance;
    @org.springframework.test.context.bean.override.convention.TestBean(name="identityDataSource",methodName="testDataSource") DataSource dataSource;
    @org.springframework.test.context.bean.override.convention.TestBean(name="identityClock",methodName="testClock") Clock clock;
    @org.springframework.test.context.bean.override.convention.TestBean(name="manifestClient",methodName="testManifests") com.jovx.api.releases.service.ManifestClient manifestClient;
    JdbcTemplate jdbc;
    final HttpClient client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    static synchronized void setup() {
        if(source!=null)return;
        try {
            String password=Files.readString(Path.of(System.getenv("JOVX_POSTGRES_TEST_ADMIN_PASSWORD_FILE"))).strip();
            server=new JdbcTemplate(new DriverManagerDataSource("jdbc:postgresql://127.0.0.1:15432/postgres"+OPTIONS,"postgres",password));
            String directory=server.queryForObject("SHOW data_directory",String.class).replace('\\','/').toLowerCase(Locale.ROOT);
            if(!directory.endsWith("/outputs/jovx/.local/postgres-native/data"))throw new IllegalStateException("Wrong PostgreSQL instance");
            if(!DATABASE.matches("jovx_partner_test_[a-f0-9]{32}"))throw new IllegalStateException("Unsafe test schema");
            server.execute("CREATE DATABASE "+DATABASE+" TEMPLATE template0 ENCODING 'UTF8' LC_COLLATE 'C' LC_CTYPE 'C'"); created=true;
            source=new DriverManagerDataSource("jdbc:postgresql://127.0.0.1:15432/"+DATABASE+OPTIONS,"postgres",password);
            Flyway.configure().dataSource(source).cleanDisabled(true).locations("classpath:db/postgresql").load().migrate();
            var jdbc=new JdbcTemplate(source);
            jdbc.update("INSERT INTO cities(id,name) VALUES (?,?),(?,?)",CITY.toString(),"مدينة اختبار",OTHER_CITY.toString(),"مدينة ثانية");
            jdbc.update("INSERT INTO addresses(id,city_id,name) VALUES (?,?,?)",ADDRESS.toString(),CITY.toString(),"حي اختبار");
            jdbc.update("INSERT INTO categories(id,name) VALUES (?,?)",CATEGORY.toString(),"مطاعم اختبار");
            var generator=KeyPairGenerator.getInstance("RSA"); generator.initialize(2048); var keys=generator.generateKeyPair();
            publicKey=pem("PUBLIC KEY",keys.getPublic().getEncoded()); privateKey=pem("PRIVATE KEY",keys.getPrivate().getEncoded());
        } catch(Exception ex) {cleanup();throw new IllegalStateException("Acceptance infrastructure failed",ex);}
    }
    static Path pem(String type,byte[] bytes) throws Exception {
        var file=Files.createTempFile("jovx-acceptance-key-",".pem"); file.toFile().deleteOnExit();
        Files.writeString(file,"-----BEGIN "+type+"-----\n"+Base64.getMimeEncoder(64,new byte[]{'\n'}).encodeToString(bytes)+"\n-----END "+type+"-----\n"); return file;
    }
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r){
        setup();
        r.add("jovx.security.jwt.public-key",()->publicKey.toUri().toString());
        r.add("jovx.security.jwt.private-key",()->privateKey.toUri().toString());
        r.add("jovx.security.jwt.issuer",()->"https://identity.jovx.test");
        r.add("jovx.security.jwt.audience",()->"jovx-api");
        r.add("jovx.storage.local-dir",()->IMAGES.toString());
        r.add("jovx.notifications.dispatch-enabled",()->"false");r.add("jovx.wallets.low-balance-alerts-enabled",()->"false");
    }
    static DataSource testDataSource(){setup();return source;}
    static Clock testClock(){return TIME;}
    static class TestClock extends Clock {
        volatile Instant instant=START;
        public ZoneId getZone(){return ZoneOffset.UTC;}
        public Clock withZone(ZoneId zone){return this;}
        public Instant instant(){return instant;}
        void advance(Duration value){instant=instant.plus(value);}
    }
    @BeforeEach void resetLimiterAndTime(){jdbc=new JdbcTemplate(dataSource);TIME.instant=START;jdbc.update("DELETE FROM identity_auth_rate_limits");
        jdbc.update("DELETE FROM role_permissions WHERE role_id IN (2,3,5,6)");
        jdbc.update("INSERT INTO role_permissions SELECT 3,id,level FROM permissions WHERE level='PARTNER'");
        jdbc.update("INSERT INTO role_permissions SELECT 6,id,level FROM permissions WHERE level='PARTNER' AND delegable_to_employee=TRUE");
    }
    @AfterAll static void cleanup(){
        if(created && server!=null && DATABASE.matches("jovx_partner_test_[a-f0-9]{32}")){server.execute("DROP DATABASE "+DATABASE);created=false;}
        for(Path path:new Path[]{publicKey,privateKey})if(path!=null)try{Files.deleteIfExists(path);}catch(Exception ignored){}
    }
    record Person(UUID id,String phone){}
    Person account(AccountRole role){
        var person=new Person(UUID.randomUUID(),"+12025550"+PHONE.getAndIncrement());
        accounts.create(person.id(),"Test person",person.phone(),role,"048291");return person;
    }
    record Response(int status,JsonNode json,HttpHeaders headers) {
        String text(String field){return json.path(field).asString();}
        String access(){return json.path("session").path("access_token").asString();}
        String refresh(){return json.path("session").path("refresh_token").asString();}
    }
    Response call(String method,String path,Object body,String access) throws Exception {
        var builder=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path)).timeout(Duration.ofSeconds(20));
        if(access!=null)builder.header("Authorization","Bearer "+access);
        if(body!=null)builder.header("Content-Type","application/json");
        builder.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(body instanceof String s?s:JSON.writeValueAsString(body)));
        var r=client.send(builder.build(),HttpResponse.BodyHandlers.ofString());
        return new Response(r.statusCode(),r.body().isBlank()?JSON.nullNode():JSON.readTree(r.body()),r.headers());
    }
    Response login(Person person) throws Exception {return login(person.phone(),"048291");}
    Response login(String phone,String pin) throws Exception {return call("POST","/api/v1/auth/login",Map.of("username",phone,"password",pin),null);}
    Response refresh(String refresh) throws Exception {return call("POST","/api/v1/auth/refresh",Map.of("refresh_token",refresh),null);}
    Response session(String access) throws Exception {return call("GET","/api/v1/session",null,access);}
    String superAccess() throws Exception {var r=login(account(AccountRole.SUPER_ADMIN));assertEquals(200,r.status());return r.access();}
    Map<String,Object> registration(String phone){
        var body=new LinkedHashMap<String,Object>();
        body.put("userName","أحمد");body.put("storeName","مطعم أحمد");body.put("categoryId",CATEGORY);body.put("phone",phone);body.put("pin","048291");
        body.put("discountPercent",10);body.put("cityId",CITY);body.put("addressId",ADDRESS);
        return body;
    }
    String nextPhone(){return "+12025550"+PHONE.getAndIncrement();}
    Response create(String access,String phone) throws Exception {return call("POST","/api/v1/admin/partners",registration(phone),access);}
    void activate(String access,Response created) throws Exception {
        assertEquals(204,call("POST","/api/v1/admin/partners/"+created.text("partnerId")+"/activate",null,access).status());
    }
    Response reference(String kind, String name, String access) throws Exception {
        return call("POST","/api/v1/reference-data/"+kind,Map.of("name",name),access);
    }
    @Test void newReferencesAreImmediatelyUsableForPendingPartnerThenExplicitActivation() throws Exception {
        String root=superAccess(), suffix=UUID.randomUUID().toString();
        var city=reference("cities","  مدينة   "+suffix+"  ",root);
        var category=reference("categories","تصنيف "+suffix,root);
        assertEquals(200,city.status());assertEquals("مدينة "+suffix,city.text("name"));
        assertEquals(200,category.status());
        var address=call("POST","/api/v1/reference-data/addresses",Map.of("cityId",city.text("id"),"name","حي جديد"),root);
        assertEquals(200,address.status());assertEquals(city.text("id"),address.text("cityId"));
        var lists=call("GET","/api/v1/reference-data",null,root).json().toString();
        assertTrue(lists.contains(city.text("id")));assertTrue(lists.contains(address.text("id")));assertTrue(lists.contains(category.text("id")));
        String phone=nextPhone();var body=registration(phone);
        body.put("cityId",city.text("id"));body.put("addressId",address.text("id"));body.put("categoryId",category.text("id"));
        var registration=call("POST","/api/v1/admin/partners",body,root);
        assertEquals(201,registration.status());assertEquals("PENDING",registration.text("status"));
        assertEquals(401,login(phone,"048291").status());
        activate(root,registration);assertEquals(200,login(phone,"048291").status());
    }
    @Test void referenceWritesRequireAdministrationCreateGrantAndRevocationIsImmediate() throws Exception {
        String root=superAccess();var admin=login(account(AccountRole.ADMIN));var owner=partner();
        String employeePhone=nextPhone();assertEquals(201,employee(owner,employeePhone,Set.of()).status());
        String employeeToken=login(employeePhone,"012345").access();
        var bodies=Map.of("cities",Map.of("name","ممنوع"),"categories",Map.of("name","ممنوع"),"addresses",Map.of("name","ممنوع","cityId",CITY.toString()));
        for(var entry:bodies.entrySet()){
            String path="/api/v1/reference-data/"+entry.getKey();
            assertEquals(401,call("POST",path,entry.getValue(),null).status());
            for(String token:List.of(admin.access(),owner.access(),employeeToken))
                assertEquals(403,call("POST",path,entry.getValue(),token).status());
        }
        assertEquals(204,roleGrants(root,"ADMIN",Set.of("partner.create")).status());
        assertEquals(200,reference("cities","إدارة "+UUID.randomUUID(),admin.access()).status());
        assertEquals(204,roleGrants(root,"ADMIN",Set.of()).status());
        assertEquals(403,reference("categories","إدارة "+UUID.randomUUID(),admin.access()).status());
    }
    @Test void duplicateReferenceRetriesReuseIdsAndNeighborhoodNamesAreScopedToCity() throws Exception {
        String root=superAccess(), name="Test "+UUID.randomUUID();
        for(String kind:List.of("cities","categories")){
            var first=reference(kind,name,root);var again=reference(kind,"  "+name.toUpperCase(Locale.ROOT).replace(" ","   ")+"  ",root);
            assertEquals(200,again.status());assertEquals(first.text("id"),again.text("id"));
            assertEquals(first.text("name"),again.text("name"));
        }
        var a=call("POST","/api/v1/reference-data/addresses",Map.of("cityId",CITY,"name",name),root);
        var duplicate=call("POST","/api/v1/reference-data/addresses",Map.of("cityId",CITY,"name","  "+name+"  "),root);
        var other=call("POST","/api/v1/reference-data/addresses",Map.of("cityId",OTHER_CITY,"name",name),root);
        assertEquals(a.text("id"),duplicate.text("id"));assertNotEquals(a.text("id"),other.text("id"));
        var body=registration(nextPhone());body.put("cityId",CITY);body.put("addressId",other.text("id"));
        assertEquals(400,call("POST","/api/v1/admin/partners",body,root).status());
        assertThrows(org.springframework.dao.DuplicateKeyException.class,()->jdbc.update("INSERT INTO addresses(id,city_id,name) VALUES (?,?,?)",UUID.randomUUID().toString(),CITY.toString(),"  "+name+"  "));
    }
    @Test void invalidReferenceInputDoesNotCreatePartialData() throws Exception {
        String root=superAccess();
        for(String kind:List.of("cities","categories")){
            int before=jdbc.queryForObject("SELECT COUNT(*) FROM "+kind,Integer.class);
            for(String name:List.of("","   ","x".repeat(201),"\u00a0"))assertEquals(400,reference(kind,name,root).status());
            assertEquals(before,jdbc.queryForObject("SELECT COUNT(*) FROM "+kind,Integer.class));
        }
        int before=jdbc.queryForObject("SELECT COUNT(*) FROM addresses",Integer.class);
        assertEquals(400,call("POST","/api/v1/reference-data/addresses",Map.of("name","حي"),root).status());
        var missing=call("POST","/api/v1/reference-data/addresses",Map.of("name","حي","cityId",UUID.randomUUID()),root);
        assertEquals(404,missing.status());assertEquals("CITY_NOT_FOUND",missing.text("code"));
        assertEquals(before,jdbc.queryForObject("SELECT COUNT(*) FROM addresses",Integer.class));
    }
    @Test void simultaneousCreatorsConvergeOnOneReferenceWithoutLosingItsId() throws Exception {
        String first=superAccess(),second=superAccess(),name="متزامن "+UUID.randomUUID();
        var gate=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(2)){
            var a=pool.submit(()->{gate.await();return reference("cities",name,first);});
            var b=pool.submit(()->{gate.await();return reference("cities",name,second);});gate.countDown();
            var ra=a.get(15,TimeUnit.SECONDS);var rb=b.get(15,TimeUnit.SECONDS);
            assertEquals(200,ra.status());assertEquals(200,rb.status());assertEquals(ra.text("id"),rb.text("id"));
            assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM cities WHERE name=?",Integer.class,name));
        }
    }

    @Test void invalidNumberingPlanExplainsRegistrationFailureWithoutCreatingPartialPartnerOrLeakingCredentials() throws Exception {
        String admin=superAccess();
        int users=jdbc.queryForObject("SELECT COUNT(*) FROM users",Integer.class);
        int partners=jdbc.queryForObject("SELECT COUNT(*) FROM partner_owners",Integer.class);
        var body=registration("+963912312312");
        var rejected=call("POST","/api/v1/admin/partners",body,admin);
        assertEquals(400,rejected.status());
        assertEquals("INVALID_PHONE_NUMBER",rejected.text("code"));
        assertFalse(rejected.json().toString().contains("912312312"));
        assertFalse(rejected.json().toString().contains("048291"));
        assertEquals(users,jdbc.queryForObject("SELECT COUNT(*) FROM users",Integer.class));
        assertEquals(partners,jdbc.queryForObject("SELECT COUNT(*) FROM partner_owners",Integer.class));
        var invalidLogin=login("+963912312312","048291");
        assertEquals(401,invalidLogin.status());
        assertEquals("INVALID_CREDENTIALS",invalidLogin.text("code"));
        body.put("phone",nextPhone());
        assertEquals(201,call("POST","/api/v1/admin/partners",body,admin).status());
    }
    @Test void adminCreatesPendingPartnerWhoCanOnlyLoginAfterExplicitActivation() throws Exception {
        String admin=superAccess(),phone=nextPhone();
        var created=create(admin,phone); assertEquals(201,created.status());assertEquals("PENDING",created.text("status"));
        assertFalse(created.json().toString().contains("048291"));
        assertEquals(401,login(phone,"048291").status());
        activate(admin,created);activate(admin,created);
        var signedIn=login(phone,"048291");assertEquals(200,signedIn.status());
        assertEquals("PARTNER",signedIn.json().path("account").path("role").asString());
        var context=call("GET","/api/v1/partner/context",null,signedIn.access());
        assertEquals(200,context.status());assertEquals(created.text("partnerId"),context.text("partnerId"));
        assertEquals(1,context.json().path("stores").size());
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM partner_events WHERE partner_id=? AND action='PARTNER_ACTIVATED'",Integer.class,created.text("partnerId")));
    }
    @Test void adminNeedsCreateGrantAndTheSameGrantIncludesActivation() throws Exception {
        var admin=account(AccountRole.ADMIN);var auth=login(admin);String phone=nextPhone();
        assertEquals(403,create(auth.access(),phone).status());
        String superAdmin=superAccess();
        assertEquals(204,roleGrants(superAdmin,"ADMIN",Set.of("partner.pin.reset")).status());
        assertEquals(403,create(auth.access(),phone).status());
        assertEquals(204,roleGrants(superAdmin,"ADMIN",Set.of("partner.create")).status());
        var created=create(auth.access(),phone);assertEquals(201,created.status());activate(auth.access(),created);
        assertEquals(204,roleGrants(superAdmin,"ADMIN",Set.of()).status());
        assertEquals(403,create(auth.access(),nextPhone()).status());
    }
    @Test void allEightRequiredFieldsAndCityOwnershipAreEnforcedWithoutPartialAccounts() throws Exception {
        String admin=superAccess(),phone=nextPhone();
        for(String field:List.of("userName","storeName","categoryId","phone","pin","discountPercent","cityId","addressId")){
            var body=registration(phone);body.remove(field);
            assertEquals(400,call("POST","/api/v1/admin/partners",body,admin).status(),field);
        }
        var body=registration(phone);body.put("cityId",OTHER_CITY);
        assertEquals(400,call("POST","/api/v1/admin/partners",body,admin).status());
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM users WHERE phone_e164=?",Integer.class,phone));
    }
    @Test void duplicatePhoneAcrossRolesCannotLeaveAnOrphanPartner() throws Exception {
        String admin=superAccess();var member=account(AccountRole.MEMBER);
        int before=jdbc.queryForObject("SELECT COUNT(*) FROM partner_owners",Integer.class);
        assertEquals(409,create(admin,member.phone()).status());
        assertEquals(before,jdbc.queryForObject("SELECT COUNT(*) FROM partner_owners",Integer.class));
    }
    String insertStore(String partner,String name,String status){
        String id=UUID.randomUUID().toString();
        jdbc.update("INSERT INTO partner_stores(id,partner_id,name,category_id,city_id,address_id,status,created_at) VALUES (?,?,?,?,?,?,?,now())",
            id,partner,name,CATEGORY.toString(),CITY.toString(),ADDRESS.toString(),status);
        return id;
    }
    @Test void failedStorePersistenceRollsBackAccountAndOwner() throws Exception {
        String admin=superAccess(),phone=nextPhone();int before=jdbc.queryForObject("SELECT COUNT(*) FROM partner_owners",Integer.class);
        jdbc.execute("CREATE FUNCTION test_reject_store() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'Injected test storage failure'; END $$");
        jdbc.execute("CREATE TRIGGER test_reject_store BEFORE INSERT ON partner_stores FOR EACH ROW EXECUTE FUNCTION test_reject_store()");
        try{assertEquals(503,create(admin,phone).status());}finally{jdbc.execute("DROP TRIGGER test_reject_store ON partner_stores");jdbc.execute("DROP FUNCTION test_reject_store()");}
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM users WHERE phone_e164=?",Integer.class,phone));
        assertEquals(before,jdbc.queryForObject("SELECT COUNT(*) FROM partner_owners",Integer.class));
    }
    @Test void ownerSeesOwnActiveStoresButNeverAnotherPartnersStore() throws Exception {
        String admin=superAccess(),phone=nextPhone();var created=create(admin,phone);activate(admin,created);
        String second=insertStore(created.text("partnerId"),"صيدلية","ACTIVE");
        insertStore(created.text("partnerId"),"موقوف","SUSPENDED");
        var other=create(admin,nextPhone());activate(admin,other);
        var context=call("GET","/api/v1/partner/context",null,login(phone,"048291").access());
        assertEquals(200,context.status());assertEquals(2,context.json().path("stores").size(),"active stores only");
        assertTrue(context.json().toString().contains(second));assertFalse(context.json().toString().contains(other.text("storeId")));
    }
    @Test void newLoginRevokesOldAccessAndRefreshButNotAnotherAccount() throws Exception {
        var person=account(AccountRole.ADMIN);var other=login(account(AccountRole.ADMIN));var first=login(person);var second=login(person);
        assertEquals(401,session(first.access()).status());assertEquals(401,refresh(first.refresh()).status());
        assertEquals(200,session(second.access()).status());assertEquals(200,session(other.access()).status());
    }
    @Test void refreshRotatesAndReplayRevokesTheFamilyWithoutAffectingOtherAccounts() throws Exception {
        var auth=login(account(AccountRole.ADMIN));var other=login(account(AccountRole.ADMIN));
        var rotated=refresh(auth.refresh());assertEquals(200,rotated.status());assertNotEquals(auth.refresh(),rotated.refresh());
        assertEquals(401,refresh(auth.refresh()).status());assertEquals(401,session(rotated.access()).status());
        assertEquals(401,refresh(rotated.refresh()).status());assertEquals(200,session(other.access()).status());
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM identity_refresh_tokens WHERE token_digest=?",Integer.class,auth.refresh()));
    }
    @Test void accessLastsTwentyFourHoursAndRefreshNeverExtendsTheSevenDayDeadline() throws Exception {
        var auth=login(account(AccountRole.ADMIN));assertEquals(86400,auth.json().path("session").path("expires_in").asLong());
        TIME.advance(Duration.ofHours(24));assertEquals(401,session(auth.access()).status());
        var next=refresh(auth.refresh());assertEquals(200,next.status());
        assertEquals(auth.json().path("session").path("refresh_expires_at"),next.json().path("session").path("refresh_expires_at"));
        TIME.advance(Duration.ofDays(6));assertEquals(401,refresh(next.refresh()).status());assertEquals(401,session(next.access()).status());
    }
    @Test void accessIssuedNearRefreshDeadlineIsClampedToThatDeadline() throws Exception {
        var auth=login(account(AccountRole.ADMIN));TIME.advance(Duration.ofDays(7).minusSeconds(60));
        var renewed=refresh(auth.refresh());assertEquals(200,renewed.status());assertEquals(60,renewed.json().path("session").path("expires_in").asLong());
        TIME.advance(Duration.ofSeconds(60));assertEquals(401,session(renewed.access()).status());
    }
    @Test void wrongPinsLockAttemptsWithoutTerminatingAValidSession() throws Exception {
        var person=account(AccountRole.ADMIN);var auth=login(person);
        for(int i=0;i<4;i++)assertEquals(401,login(person.phone(),"000000").status());
        var locked=login(person.phone(),"000000");assertEquals(423,locked.status());assertTrue(locked.headers().firstValue("Retry-After").isPresent());
        assertEquals(423,login(person).status());assertEquals(200,session(auth.access()).status());
        TIME.advance(Duration.ofMinutes(15));assertEquals(200,login(person).status());
    }
    @Test void logoutIsIdempotentAndRevokesBothKindsOfToken() throws Exception {
        var auth=login(account(AccountRole.ADMIN));
        for(int i=0;i<2;i++)assertEquals(204,call("POST","/api/v1/auth/logout",Map.of("refresh_token",auth.refresh()),null).status());
        assertEquals(401,session(auth.access()).status());assertEquals(401,refresh(auth.refresh()).status());
    }
    @Test void simultaneousLoginsLeaveOnlyOneUsableSession() throws Exception {
        var person=account(AccountRole.ADMIN);var barrier=new CyclicBarrier(2);
        try(var executor=Executors.newFixedThreadPool(2)){
            Callable<Response> task=()->{barrier.await(5,TimeUnit.SECONDS);return login(person);};
            var a=executor.submit(task);var b=executor.submit(task);var first=a.get(20,TimeUnit.SECONDS);var second=b.get(20,TimeUnit.SECONDS);
            assertEquals(200,first.status());assertEquals(200,second.status());
            assertEquals(1,(session(first.access()).status()==200?1:0)+(session(second.access()).status()==200?1:0));
        }
    }
    @Test void simultaneousRefreshDetectsReplayAndDoesNotLeaveReusableTokens() throws Exception {
        var auth=login(account(AccountRole.ADMIN));var barrier=new CyclicBarrier(2);
        try(var executor=Executors.newFixedThreadPool(2)){
            Callable<Response> task=()->{barrier.await(5,TimeUnit.SECONDS);return refresh(auth.refresh());};
            var a=executor.submit(task);var b=executor.submit(task);var first=a.get(20,TimeUnit.SECONDS);var second=b.get(20,TimeUnit.SECONDS);
            assertEquals(Set.of(200,401),Set.of(first.status(),second.status()));
            var winner=first.status()==200?first:second;assertEquals(401,session(winner.access()).status());
        }
    }
    @Test void roleInjectionUnsupportedRolesAndOversizedRequestsCannotAuthenticate() throws Exception {
        var person=account(AccountRole.ADMIN);
        assertEquals(400,call("POST","/api/v1/auth/login",Map.of("username",person.phone(),"password","048291","role","SUPER_ADMIN"),null).status());
        assertEquals(413,call("POST","/api/v1/auth/login"," ".repeat(4097),null).status());
        assertEquals(401,login(account(AccountRole.MEMBER)).status());
        assertEquals(401,login(account(AccountRole.PARTNER_EMPLOYEE)).status()); // Employee affiliation policy is intentionally not guessed.
        var auth=login(person);assertEquals(200,auth.status());assertTrue(auth.headers().firstValue("Cache-Control").orElse("").contains("no-store"));
        assertTrue(auth.headers().allValues("set-cookie").isEmpty());
    }
    @Test void requestRateLimitIsEnforcedAndRecoversAfterConfiguredWindow() throws Exception {
        for(int i=0;i<25;i++)assertEquals(401,login("not-a-phone","048291").status());
        var limited=login("not-a-phone","048291");assertEquals(429,limited.status());
        assertTrue(limited.headers().firstValue("Retry-After").isPresent());
        TIME.advance(Duration.ofMinutes(15));assertEquals(401,login("not-a-phone","048291").status());
    }
    @Test void databaseOutageIsNotReportedAsWrongPinAndDoesNotLeakSql() throws Exception {
        var person=account(AccountRole.ADMIN);
        jdbc.execute("ALTER TABLE identity_auth_rate_limits RENAME TO test_unavailable_rate_limits");
        try{
            var result=login(person);assertEquals(503,result.status());
            assertFalse(result.json().toString().contains("SELECT"));assertFalse(result.json().toString().contains("048291"));
        }finally{jdbc.execute("ALTER TABLE test_unavailable_rate_limits RENAME TO identity_auth_rate_limits");}
    }
    @Test void protectedRequestDatabaseOutageIsNotMistakenForAnExpiredLogin() throws Exception {
        var auth=login(account(AccountRole.ADMIN));
        jdbc.execute("ALTER TABLE identity_sessions RENAME TO test_unavailable_sessions");
        try{assertEquals(503,session(auth.access()).status());}
        finally{jdbc.execute("ALTER TABLE test_unavailable_sessions RENAME TO identity_sessions");}
        assertEquals(200,session(auth.access()).status());
    }
    @Test void signedTokensCannotBorrowAnotherSessionOrEscalateTheStoredRole() throws Exception {
        var auth=login(account(AccountRole.ADMIN));var other=login(account(AccountRole.ADMIN));
        var original=com.nimbusds.jwt.SignedJWT.parse(auth.access()).getJWTClaimsSet();
        var otherClaims=com.nimbusds.jwt.SignedJWT.parse(other.access()).getJWTClaimsSet();
        var alterations=List.of(
            new com.nimbusds.jwt.JWTClaimsSet.Builder(original).claim("sid",otherClaims.getStringClaim("sid")).build(),
            new com.nimbusds.jwt.JWTClaimsSet.Builder(original).claim("role","SUPER_ADMIN").build(),
            new com.nimbusds.jwt.JWTClaimsSet.Builder(original).issuer("https://wrong.test").build(),
            new com.nimbusds.jwt.JWTClaimsSet.Builder(original).audience("wrong-api").build(),
            new com.nimbusds.jwt.JWTClaimsSet.Builder(original).notBeforeTime(Date.from(TIME.instant().plusSeconds(1))).build(),
            new com.nimbusds.jwt.JWTClaimsSet.Builder(original).claim("token_use","refresh").build());
        for(var claims:alterations)assertEquals(401,session(sign(claims)).status());
        var inflated=new com.nimbusds.jwt.JWTClaimsSet.Builder(original).claim("scope","partner.create ROLE_SUPER_ADMIN").build();
        assertEquals(403,create(sign(inflated),nextPhone()).status());
        assertEquals(401,session(auth.access().substring(0,auth.access().length()-12)+"bad-signature").status());
    }
    static String sign(com.nimbusds.jwt.JWTClaimsSet claims) throws Exception {
        java.security.interfaces.RSAPrivateKey key;
        try(var stream=Files.newInputStream(privateKey)){key=org.springframework.security.converter.RsaKeyConverters.pkcs8().convert(stream);}
        var jwt=new com.nimbusds.jwt.SignedJWT(new com.nimbusds.jose.JWSHeader(com.nimbusds.jose.JWSAlgorithm.RS256),claims);
        jwt.sign(new com.nimbusds.jose.crypto.RSASSASigner(key));return jwt.serialize();
    }
    @Test void replayFromAReplacedSessionCannotTerminateTheNewLogin() throws Exception {
        var person=account(AccountRole.ADMIN);var first=login(person);assertEquals(200,refresh(first.refresh()).status());
        var current=login(person);assertEquals(401,refresh(first.refresh()).status());assertEquals(200,session(current.access()).status());
    }
    @Test void refreshRacingWithNewLoginCannotResurrectThePreviousSession() throws Exception {
        var person=account(AccountRole.ADMIN);var old=login(person);var barrier=new CyclicBarrier(2);
        try(var executor=Executors.newFixedThreadPool(2)){
            var login=executor.submit(()->{barrier.await(5,TimeUnit.SECONDS);return login(person);});
            var refresh=executor.submit(()->{barrier.await(5,TimeUnit.SECONDS);return refresh(old.refresh());});
            var current=login.get(20,TimeUnit.SECONDS);var raced=refresh.get(20,TimeUnit.SECONDS);
            assertEquals(200,current.status());assertTrue(Set.of(200,401).contains(raced.status()));
            assertEquals(200,session(current.access()).status());assertEquals(401,session(old.access()).status());
            if(raced.status()==200)assertEquals(401,session(raced.access()).status());
        }
    }
    @Test void logoutRacingWithRefreshLeavesTheSessionRevoked() throws Exception {
        var old=login(account(AccountRole.ADMIN));var barrier=new CyclicBarrier(2);
        try(var executor=Executors.newFixedThreadPool(2)){
            var logout=executor.submit(()->{barrier.await(5,TimeUnit.SECONDS);return call("POST","/api/v1/auth/logout",Map.of("refresh_token",old.refresh()),null);});
            var refresh=executor.submit(()->{barrier.await(5,TimeUnit.SECONDS);return refresh(old.refresh());});
            assertEquals(204,logout.get(20,TimeUnit.SECONDS).status());var raced=refresh.get(20,TimeUnit.SECONDS);
            assertTrue(Set.of(200,401).contains(raced.status()));assertEquals(401,session(old.access()).status());
            if(raced.status()==200)assertEquals(401,session(raced.access()).status());
        }
    }
    Response roleGrants(String access,String role,Set<String> permissions) throws Exception {
        return call("PUT","/api/v1/admin/roles/"+role+"/permissions",Map.of("permissions",permissions),access);
    }
    record Partner(String access,Response created){}
    Partner partner() throws Exception {
        String admin=superAccess(),phone=nextPhone();var created=create(admin,phone);assertEquals(201,created.status());activate(admin,created);
        var auth=login(phone,"048291");assertEquals(200,auth.status());return new Partner(auth.access(),created);
    }
    Map<String,Object> employeeBody(String phone,Set<String> branches,Set<String> permissions){
        var body=new LinkedHashMap<String,Object>();body.put("name","موظف اختبار");body.put("phone",phone);body.put("pin","012345");
        body.put("storeIds",branches);body.put("permissions",permissions);return body;
    }
    Response employee(Partner owner,String phone,Set<String> permissions) throws Exception {
        return call("POST","/api/v1/partner/employees",employeeBody(phone,Set.of(owner.created().text("storeId")),permissions),owner.access());
    }
    Response employeeGrants(String access,String user,Set<String> permissions) throws Exception {
        return call("PUT","/api/v1/partner/employees/"+user+"/permissions",Map.of("permissions",permissions),access);
    }
    Set<String> authorities(String access) throws Exception {
        var result=session(access);assertEquals(200,result.status());var values=new HashSet<String>();
        result.json().path("authorities").forEach(n->values.add(n.asString()));return values;
    }
    @Test void onlySuperAdminManagesRolesEvenWhenAdminCanCreatePartners() throws Exception {
        String root=superAccess(),admin=login(account(AccountRole.ADMIN)).access();var partner=partner();
        assertEquals(204,roleGrants(root,"ADMIN",Set.of("partner.create")).status());
        for(String access:List.of(admin,partner.access())){
            assertEquals(403,roleGrants(access,"ADMIN",Set.of("partner.pin.reset")).status());
            assertEquals(403,call("GET","/api/v1/admin/access/catalog",null,access).status());
        }
        assertEquals(401,roleGrants(null,"ADMIN",Set.of()).status());
        var catalog=call("GET","/api/v1/admin/access/catalog",null,root);assertEquals(200,catalog.status());
        assertEquals(6,catalog.json().path("roles").size());
        for(var p:catalog.json().path("permissions")){
            assertFalse(p.path("name_ar").asString().isBlank());assertFalse(p.path("description_ar").asString().isBlank());
            assertTrue(Set.of("ADMIN","PARTNER","MEMBER").contains(p.path("level").asString()));
        }
    }
    @Test void rolePermissionsApplyToEveryAdminAndRevocationAffectsExistingJwts() throws Exception {
        String root=superAccess();var a=login(account(AccountRole.ADMIN));var b=login(account(AccountRole.ADMIN));
        assertEquals(403,create(a.access(),nextPhone()).status());
        assertEquals(204,roleGrants(root,"ADMIN",Set.of("partner.create")).status());
        assertEquals(201,create(a.access(),nextPhone()).status());assertEquals(201,create(b.access(),nextPhone()).status());
        assertEquals(204,roleGrants(root,"ADMIN",Set.of()).status());
        assertEquals(403,create(a.access(),nextPhone()).status());assertEquals(403,create(b.access(),nextPhone()).status());
    }
    @Test void crossLevelUnknownAndNonDelegableRoleGrantsFailAtomically() throws Exception {
        String root=superAccess();var admin=login(account(AccountRole.ADMIN));
        assertEquals(204,roleGrants(root,"ADMIN",Set.of("partner.create")).status());
        assertEquals(400,roleGrants(root,"ADMIN",Set.of("sale.confirm")).status());
        assertEquals(400,roleGrants(root,"ADMIN",Set.of("imaginary.permission")).status());
        assertEquals(201,create(admin.access(),nextPhone()).status());
        assertEquals(400,roleGrants(root,"PARTNER_EMPLOYEE",Set.of("employee.create")).status());
        assertEquals(409,roleGrants(root,"SUPER_ADMIN",Set.of()).status());
        assertEquals(400,roleGrants(root,"CUSTOM_ROLE",Set.of()).status());
    }
    @Test void onlySuperAdminAssignsPredefinedRolesAndOldTokensCannotSurviveRoleChange() throws Exception {
        String root=superAccess();var target=account(AccountRole.MEMBER);var admin=login(account(AccountRole.ADMIN));
        String path="/api/v1/admin/users/"+target.id()+"/role";
        assertEquals(403,call("PUT",path,Map.of("roleKey","ADMIN"),admin.access()).status());
        assertEquals(204,call("PUT",path,Map.of("roleKey","ADMIN"),root).status());
        var auth=login(target);assertEquals(200,auth.status());
        assertEquals("ADMIN",auth.json().path("account").path("role").asString());
        var member=call("PUT",path,Map.of("roleKey","MEMBER"),root);
        assertEquals(409,member.status(),"a MEMBER needs a membership record: sold, not assigned");
        assertEquals("ROLE_REQUIRES_DOMAIN_PROVISIONING",member.text("code"));
        assertEquals(204,call("PUT",path,Map.of("roleKey","SUPER_ADMIN"),root).status());
        assertEquals(401,session(auth.access()).status());assertEquals(401,refresh(auth.refresh()).status());
        assertEquals(400,call("PUT",path,Map.of("roleKey","NOT_DEFINED"),root).status());
    }
    @Test void roleAssignmentCannotDetachAPartnerOrCreateAnIndependentEmployee() throws Exception {
        String root=superAccess();var owner=partner();var admin=account(AccountRole.ADMIN);
        assertEquals(409,call("PUT","/api/v1/admin/users/"+owner.created().text("accountId")+"/role",Map.of("roleKey","ADMIN"),root).status());
        assertEquals(409,call("PUT","/api/v1/admin/users/"+admin.id()+"/role",Map.of("roleKey","PARTNER_EMPLOYEE"),root).status());
        assertEquals(200,session(owner.access()).status());
    }
    @Test void partnerCreatesActiveNamedEmployeeWithNoAutomaticPermissions() throws Exception {
        var owner=partner();String phone=nextPhone();var created=employee(owner,phone,Set.of());assertEquals(201,created.status());
        assertEquals("PARTNER_EMPLOYEE",created.text("role"));assertEquals("ACTIVE",created.text("status"));
        var auth=login(phone,"012345");assertEquals(200,auth.status());
        assertEquals(Set.of("ROLE_PARTNER_EMPLOYEE"),authorities(auth.access()));
        assertFalse(created.json().toString().contains("012345"));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM user_permissions WHERE user_id=?",Integer.class,created.text("userId")));
        assertEquals("موظف اختبار",jdbc.queryForObject("SELECT name FROM users WHERE id=?",String.class,created.text("userId")));
        assertEquals(403,create(auth.access(),nextPhone()).status());
        assertEquals(403,call("GET","/api/v1/partner/context",null,auth.access()).status());
    }
    @Test void ownerExplicitlyGrantsAndRevokesEmployeeAccessWithoutChangingGlobalRole() throws Exception {
        var owner=partner();String phone=nextPhone();var created=employee(owner,phone,Set.of());
        var auth=login(phone,"012345");int before=jdbc.queryForObject("SELECT COUNT(*) FROM role_permissions",Integer.class);
        assertEquals(204,employeeGrants(owner.access(),created.text("userId"),Set.of("sale.confirm","catalog.read")).status());
        assertTrue(authorities(auth.access()).containsAll(Set.of("sale.confirm","catalog.read")));
        assertEquals(204,employeeGrants(owner.access(),created.text("userId"),Set.of()).status());
        assertEquals(Set.of("ROLE_PARTNER_EMPLOYEE"),authorities(auth.access()));
        assertEquals(before,jdbc.queryForObject("SELECT COUNT(*) FROM role_permissions",Integer.class));
        assertEquals(403,roleGrants(owner.access(),"PARTNER_EMPLOYEE",Set.of()).status());
        assertEquals(403,employeeGrants(auth.access(),created.text("userId"),Set.of("sale.confirm")).status());
    }
    @Test void employeeNeverInheritsAnotherEmployeesGrantsOrANewRoleCeiling() throws Exception {
        var owner=partner();String a=nextPhone(),b=nextPhone();
        var first=employee(owner,a,Set.of("sale.confirm"));assertEquals(201,first.status());
        assertEquals(201,employee(owner,b,Set.of()).status());
        var authA=login(a,"012345");var authB=login(b,"012345");String root=superAccess();
        assertTrue(authorities(authA.access()).contains("sale.confirm"));assertFalse(authorities(authB.access()).contains("sale.confirm"));
        assertEquals(204,roleGrants(root,"PARTNER_EMPLOYEE",Set.of()).status());
        assertFalse(authorities(authA.access()).contains("sale.confirm"));
        assertEquals(204,roleGrants(root,"PARTNER_EMPLOYEE",Set.of("sale.confirm")).status());
        assertFalse(authorities(authA.access()).contains("sale.confirm"));assertFalse(authorities(authB.access()).contains("sale.confirm"));
    }
    @Test void anotherPartnerCannotChangeEmployeePermissionsOrAssignForeignStores() throws Exception {
        var owner=partner();var other=partner();var created=employee(owner,nextPhone(),Set.of("sale.confirm"));
        assertEquals(404,employeeGrants(other.access(),created.text("userId"),Set.of()).status());
        String phone=nextPhone();var body=employeeBody(phone,Set.of(owner.created().text("storeId"),other.created().text("storeId")),Set.of());
        assertEquals(400,call("POST","/api/v1/partner/employees",body,owner.access()).status());
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM users WHERE phone_e164=?",Integer.class,phone));
    }
    @Test void employeeCanWorkAcrossStoresOfTheSamePartner() throws Exception {
        var owner=partner();String store=insertStore(owner.created().text("partnerId"),"Second","ACTIVE");
        var body=employeeBody(nextPhone(),Set.of(owner.created().text("storeId"),store),Set.of("catalog.read"));
        var created=call("POST","/api/v1/partner/employees",body,owner.access());assertEquals(201,created.status());
        assertEquals(2,created.json().path("storeIds").size());
        var context=call("GET","/api/v1/partner/context",null,owner.access());
        assertTrue(context.json().toString().contains(store));
        assertTrue(context.json().toString().contains(owner.created().text("storeId")));
    }
    @Test void referenceListsSupportRegistrationButDoNotExposeUnauthenticatedOrUnprivilegedReads() throws Exception {
        assertEquals(401,call("GET","/api/v1/reference-data",null,null).status());
        var admin=login(account(AccountRole.ADMIN));
        assertEquals(403,call("GET","/api/v1/reference-data",null,admin.access()).status());
        var root=superAccess();var data=call("GET","/api/v1/reference-data",null,root);
        assertEquals(200,data.status());assertTrue(data.json().path("cities").toString().contains(CITY.toString()));
        assertTrue(data.json().path("addresses").toString().contains(ADDRESS.toString()));
        assertTrue(data.json().path("addresses").toString().contains(CITY.toString()));
        assertEquals(204,roleGrants(root,"ADMIN",Set.of("partner.create")).status());
        assertEquals(200,call("GET","/api/v1/reference-data",null,admin.access()).status());
        assertEquals(405,call("POST","/api/v1/reference-data",Map.of("name","x"),root).status());
    }
    @Test void partnerCannotGrantAdminExclusiveUnknownOrUnavailablePermissions() throws Exception {
        var owner=partner();String root=superAccess();
        for(String key:List.of("partner.create","employee.pin.change","imaginary.permission")){
            String phone=nextPhone();assertEquals(403,employee(owner,phone,Set.of(key)).status());
            assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM users WHERE phone_e164=?",Integer.class,phone));
        }
        assertEquals(204,roleGrants(root,"PARTNER_EMPLOYEE",Set.of("catalog.read")).status());
        assertEquals(403,employee(owner,nextPhone(),Set.of("sale.confirm")).status());
        assertEquals(204,roleGrants(root,"PARTNER",Set.of("employee.create","employee.permissions.manage")).status());
        assertEquals(403,employee(owner,nextPhone(),Set.of("catalog.read")).status());
    }
    @Test void removingDelegatorsPermissionRemovesDelegatedAccessImmediately() throws Exception {
        var owner=partner();String phone=nextPhone();assertEquals(201,employee(owner,phone,Set.of("sale.confirm")).status());
        var auth=login(phone,"012345");assertTrue(authorities(auth.access()).contains("sale.confirm"));
        String root=superAccess();
        assertEquals(204,roleGrants(root,"PARTNER",Set.of("employee.create","employee.permissions.manage")).status());
        assertFalse(authorities(auth.access()).contains("sale.confirm"));
        assertEquals(204,roleGrants(root,"PARTNER",Set.of("employee.create","employee.permissions.manage","sale.confirm")).status());
        assertFalse(authorities(auth.access()).contains("sale.confirm"));
    }
    @Test void employeeRequestsRequireNameStoresAndExplicitPermissionSelection() throws Exception {
        var owner=partner();String phone=nextPhone();
        for(String field:List.of("name","phone","pin","storeIds","permissions")){
            var body=employeeBody(phone,Set.of(owner.created().text("storeId")),Set.of());body.remove(field);
            assertEquals(400,call("POST","/api/v1/partner/employees",body,owner.access()).status());
        }
        var body=employeeBody(phone,Set.of(),Set.of());assertEquals(400,call("POST","/api/v1/partner/employees",body,owner.access()).status());
        body=employeeBody(phone,Set.of(owner.created().text("storeId")),Set.of());body.put("roleKey","SUPER_ADMIN");
        assertEquals(400,call("POST","/api/v1/partner/employees",body,owner.access()).status());
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM users WHERE phone_e164=?",Integer.class,phone));
    }
    @Test void failedEmployeeStoreWriteRollsBackUserAffiliationAndPermissions() throws Exception {
        var owner=partner();String phone=nextPhone();
        jdbc.execute("CREATE FUNCTION reject_employee_branch() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'Injected test storage failure'; END $$");
        jdbc.execute("CREATE TRIGGER reject_employee_branch BEFORE INSERT ON partner_employee_stores FOR EACH ROW EXECUTE FUNCTION reject_employee_branch()");
        try{assertEquals(503,employee(owner,phone,Set.of("sale.confirm")).status());}finally{jdbc.execute("DROP TRIGGER reject_employee_branch ON partner_employee_stores");jdbc.execute("DROP FUNCTION reject_employee_branch()");}
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM users WHERE phone_e164=?",Integer.class,phone));
    }

    // ---- Partner stores after registration (decision 2026-11-19) ----
    Map<String,Object> storeBody(String name){
        var body=new LinkedHashMap<String,Object>();body.put("name",name);body.put("categoryId",CATEGORY);
        body.put("cityId",CITY);body.put("addressId",ADDRESS);body.put("addressDetails","شارع 1");body.put("contactPhone",null);
        return body;
    }
    String partnerPath(Partner p){return "/api/v1/admin/partners/"+p.created().text("partnerId");}
    List<Map<String,Object>> week(){
        var days=new ArrayList<Map<String,Object>>();
        for(int d=1;d<=7;d++){
            var day=new LinkedHashMap<String,Object>();day.put("weekday",d);
            if(d==5){day.put("closed",true);}else{day.put("closed",false);day.put("opensAt","18:00");day.put("closesAt","02:00");}
            days.add(day);
        }
        return days;
    }

    @Test void administrationListsAndReadsPartnersOnlyWithCreateOrStoreGrant() throws Exception {
        String root=superAccess();var owner=partner();var admin=login(account(AccountRole.ADMIN));
        assertEquals(403,call("GET","/api/v1/admin/partners",null,admin.access()).status());
        assertEquals(403,call("GET",partnerPath(owner),null,owner.access()).status());
        String ownerPhone=jdbc.queryForObject("SELECT phone_e164 FROM users WHERE id=?",String.class,owner.created().text("accountId"));
        var list=call("GET","/api/v1/admin/partners?q="+ownerPhone.substring(1),null,root);
        assertEquals(200,list.status());assertTrue(list.json().toString().contains(owner.created().text("partnerId")));
        assertFalse(list.json().toString().toLowerCase(Locale.ROOT).contains("pin"));
        assertEquals(204,roleGrants(root,"ADMIN",Set.of("partner.store.manage")).status());
        var detail=call("GET",partnerPath(owner),null,admin.access());
        assertEquals(200,detail.status());assertEquals("ACTIVE",detail.text("status"));assertEquals("أحمد",detail.text("ownerName"));
        var store=detail.json().path("stores").get(0);
        assertEquals("مطاعم اختبار",store.path("categoryName").asString());
        assertEquals("حي اختبار",store.path("addressName").asString());
        assertEquals(404,call("GET","/api/v1/admin/partners/"+UUID.randomUUID(),null,root).status());
    }
    @Test void minimumDiscountIsAdministrationCreateOnlyAndValidated() throws Exception {
        String root=superAccess();var owner=partner();var admin=login(account(AccountRole.ADMIN));String path=partnerPath(owner)+"/discount";
        assertEquals(403,call("PUT",path,Map.of("discountPercent",12),owner.access()).status());
        assertEquals(204,roleGrants(root,"ADMIN",Set.of("partner.store.manage")).status());
        assertEquals(403,call("PUT",path,Map.of("discountPercent",12),admin.access()).status());
        assertEquals(204,roleGrants(root,"ADMIN",Set.of("partner.create")).status());
        assertEquals(204,call("PUT",path,Map.of("discountPercent",12.5),admin.access()).status());
        for(String bad:List.of("-1","101","1.234"))assertEquals(400,call("PUT",path,"{\"discountPercent\":"+bad+"}",root).status());
        assertEquals(0,new java.math.BigDecimal("12.50").compareTo(new java.math.BigDecimal(
            call("GET","/api/v1/partner/context",null,owner.access()).json().path("minimumDiscountPercent").asString())));
    }
    @Test void storesAreManagedWithTheStoreGrantOrByTheOwnerWithoutTouchingLogin() throws Exception {
        String root=superAccess();var owner=partner();var other=partner();var admin=login(account(AccountRole.ADMIN));
        assertEquals(204,roleGrants(root,"ADMIN",Set.of("partner.create")).status());
        assertEquals(403,call("POST",partnerPath(owner)+"/stores",storeBody("متجر الإدارة"),admin.access()).status(),"partner.create alone does not manage stores");
        assertEquals(204,roleGrants(root,"ADMIN",Set.of("partner.store.manage")).status());
        var byAdmin=call("POST",partnerPath(owner)+"/stores",storeBody("صيدلية الإدارة"),admin.access());
        assertEquals(201,byAdmin.status());assertEquals("ACTIVE",byAdmin.text("status"));
        var own=call("POST","/api/v1/partner/stores",storeBody("مقهى الشريك"),owner.access());
        assertEquals(201,own.status());
        var context=call("GET","/api/v1/partner/context",null,owner.access()).json().toString();
        assertTrue(context.contains(byAdmin.text("storeId")));assertTrue(context.contains(own.text("storeId")));
        String phone=jdbc.queryForObject("SELECT phone_e164 FROM users WHERE id=?",String.class,owner.created().text("accountId"));
        var edit=storeBody("مقهى جديد");edit.put("contactPhone",phone);edit.put("latitude",36.5);edit.put("longitude",40.75);
        edit.put("description","قهوة مختصة");
        String ownPath="/api/v1/partner/stores/"+own.text("storeId");
        assertEquals(204,call("PUT",ownPath,edit,owner.access()).status());
        var saved=jdbc.queryForMap("SELECT name,contact_phone,latitude,description FROM partner_stores WHERE id=?",own.text("storeId"));
        assertEquals("مقهى جديد",saved.get("name"));assertEquals(phone,saved.get("contact_phone"));
        assertEquals(0,new java.math.BigDecimal("36.5").compareTo((java.math.BigDecimal)saved.get("latitude")));
        edit.put("contactPhone",null);
        assertEquals(204,call("PUT",partnerPath(owner)+"/stores/"+own.text("storeId"),edit,root).status());
        assertNull(jdbc.queryForObject("SELECT contact_phone FROM partner_stores WHERE id=?",String.class,own.text("storeId")));
        assertEquals(phone,jdbc.queryForObject("SELECT phone_e164 FROM users WHERE id=?",String.class,owner.created().text("accountId")));
        var half=storeBody("x");half.put("latitude",36.5);
        var invalidPoint=call("PUT",ownPath,half,owner.access());
        assertEquals(400,invalidPoint.status());assertEquals("INVALID_MAP_POINT",invalidPoint.text("code"));
        var wrongCity=storeBody("x");wrongCity.put("cityId",OTHER_CITY);
        assertEquals(400,call("PUT",ownPath,wrongCity,owner.access()).status());
        var wrongCategory=storeBody("x");wrongCategory.put("categoryId",UUID.randomUUID());
        assertEquals(400,call("PUT",ownPath,wrongCategory,owner.access()).status());
        assertEquals(404,call("PUT",ownPath,edit,other.access()).status());
        assertEquals(404,call("PUT",partnerPath(other)+"/stores/"+own.text("storeId"),edit,root).status());
        assertEquals(404,call("PUT",partnerPath(other)+"/stores/"+own.text("storeId")+"/status",Map.of("status","SUSPENDED"),root).status());
        assertEquals(400,call("PUT",ownPath+"/status",Map.of("status","DELETED"),owner.access()).status());
    }
    @Test void storeAddedWhilePendingIsActivatedWithThePartner() throws Exception {
        String root=superAccess(),phone=nextPhone();var created=create(root,phone);
        var extra=call("POST","/api/v1/admin/partners/"+created.text("partnerId")+"/stores",storeBody("متجر ثان"),root);
        assertEquals(201,extra.status());assertEquals("PENDING",extra.text("status"));
        var early=call("PUT","/api/v1/admin/partners/"+created.text("partnerId")+"/stores/"+extra.text("storeId")+"/status",Map.of("status","SUSPENDED"),root);
        assertEquals(409,early.status());assertEquals("STORE_PENDING_ACTIVATION",early.text("code"));
        activate(root,created);
        var context=call("GET","/api/v1/partner/context",null,login(phone,"048291").access());
        assertEquals(2,context.json().path("stores").size());
    }
    @Test void suspendingEveryStoreBlocksItsEmployeesButTheOwnerStillLogsIn() throws Exception {
        var owner=partner();String phone=nextPhone();assertEquals(201,employee(owner,phone,Set.of()).status());
        var worker=login(phone,"012345");assertEquals(200,worker.status());
        String status="/api/v1/partner/stores/"+owner.created().text("storeId")+"/status";
        assertEquals(204,call("PUT",status,Map.of("status","SUSPENDED"),owner.access()).status());
        assertEquals(204,call("PUT",status,Map.of("status","SUSPENDED"),owner.access()).status());
        assertEquals(401,session(worker.access()).status());assertEquals(401,login(phone,"012345").status());
        assertEquals(200,session(owner.access()).status());
        String ownerPhone=jdbc.queryForObject("SELECT phone_e164 FROM users WHERE id=?",String.class,owner.created().text("accountId"));
        var again=login(ownerPhone,"048291");assertEquals(200,again.status(),"an active partner may log in with every store suspended");
        assertEquals(0,call("GET","/api/v1/partner/context",null,again.access()).json().path("stores").size());
        assertEquals(204,call("PUT",status,Map.of("status","ACTIVE"),again.access()).status());
        assertEquals(200,login(phone,"012345").status());
    }
    @Test void weeklyHoursAllowOvernightAndRejectAnIncompleteWeek() throws Exception {
        String root=superAccess();var owner=partner();var other=partner();
        String path="/api/v1/partner/stores/"+owner.created().text("storeId")+"/hours";
        assertEquals(204,call("PUT",path,Map.of("days",week()),owner.access()).status());
        var hours=call("GET","/api/v1/partner/profile",null,owner.access()).json().path("stores").get(0).path("hours");
        assertEquals(7,hours.size());assertTrue(hours.get(4).path("closed").asBoolean());
        assertEquals("02:00",hours.get(0).path("closesAt").asString().substring(0,5),"overnight hours are kept");
        var six=week();six.remove(6);
        assertEquals(400,call("PUT",path,Map.of("days",six),owner.access()).status());
        var duplicate=week();duplicate.get(6).put("weekday",1);
        assertEquals("INVALID_STORE_HOURS",call("PUT",path,Map.of("days",duplicate),owner.access()).text("code"));
        var openWithoutTimes=week();openWithoutTimes.get(0).remove("closesAt");
        assertEquals(400,call("PUT",path,Map.of("days",openWithoutTimes),owner.access()).status());
        var closedWithTimes=week();closedWithTimes.get(4).put("opensAt","09:00");
        assertEquals(400,call("PUT",path,Map.of("days",closedWithTimes),owner.access()).status());
        var badTime=week();badTime.get(0).put("opensAt","25:00");
        assertEquals(400,call("PUT",path,Map.of("days",badTime),owner.access()).status());
        assertEquals(404,call("PUT",path,Map.of("days",week()),other.access()).status());
        assertEquals(204,call("PUT",partnerPath(owner)+"/stores/"+owner.created().text("storeId")+"/hours",Map.of("days",week()),root).status());
        var listed=call("GET","/api/v1/public/partners",null,null).json();
        boolean found=false;
        for(var store:listed)if(store.path("id").asString().equals(owner.created().text("storeId"))){found=true;assertEquals(7,store.path("hours").size());}
        assertTrue(found);
    }
    @Test void partnerChangesOwnPinWithCurrentPinAndEveryOldTokenStops() throws Exception {
        String root=superAccess(),phone=nextPhone();var created=create(root,phone);activate(root,created);
        var auth=login(phone,"048291");String path="/api/v1/account/pin";
        var wrong=call("PUT",path,Map.of("currentPin","111111","newPin","222222"),auth.access());
        assertEquals(400,wrong.status());assertEquals("CURRENT_PIN_INCORRECT",wrong.text("code"));
        assertEquals(200,session(auth.access()).status());
        var same=call("PUT",path,Map.of("currentPin","048291","newPin","048291"),auth.access());
        assertEquals(400,same.status());assertEquals("PIN_UNCHANGED",same.text("code"));
        assertEquals(400,call("PUT",path,Map.of("currentPin","048291","newPin","12345"),auth.access()).status());
        assertEquals(204,call("PUT",path,Map.of("currentPin","048291","newPin","739104"),auth.access()).status());
        assertEquals(401,session(auth.access()).status());assertEquals(401,refresh(auth.refresh()).status());
        assertEquals(401,login(phone,"048291").status());assertEquals(200,login(phone,"739104").status());
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM identity_auth_events WHERE action='PIN_CHANGED' AND account_id=?",Integer.class,created.text("accountId")));
    }
    @Test void administrationResetsAForgottenPartnerPinOnlyWithTheResetGrant() throws Exception {
        String root=superAccess();var owner=partner();var admin=login(account(AccountRole.ADMIN));
        String path=partnerPath(owner)+"/pin",account=owner.created().text("accountId");
        String phone=jdbc.queryForObject("SELECT phone_e164 FROM users WHERE id=?",String.class,account);
        assertEquals(403,call("PUT",path,Map.of("pin","553311"),owner.access()).status());
        assertEquals(204,roleGrants(root,"ADMIN",Set.of("partner.create","partner.store.manage")).status());
        assertEquals(403,call("PUT",path,Map.of("pin","553311"),admin.access()).status(),"partner.create does not imply reset");
        assertEquals(204,roleGrants(root,"ADMIN",Set.of("partner.pin.reset")).status());
        assertEquals(400,call("PUT",path,Map.of("pin","12345"),admin.access()).status());
        assertEquals(404,call("PUT","/api/v1/admin/partners/"+UUID.randomUUID()+"/pin",Map.of("pin","553311"),root).status());
        var done=call("PUT",path,Map.of("pin","553311"),admin.access());
        assertEquals(204,done.status());assertFalse(String.valueOf(done.json()).contains("553311"));
        assertEquals(401,session(owner.access()).status(),"reset ends every session");
        assertEquals(401,login(phone,"048291").status());assertEquals(200,login(phone,"553311").status());
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM partner_events WHERE action='PIN_RESET' AND partner_id=?",Integer.class,owner.created().text("partnerId")));
    }
    @Test void onlyPartnerAccountsUseOwnPinChange() throws Exception {
        String root=superAccess();var owner=partner();String phone=nextPhone();employee(owner,phone,Set.of());
        var body=Map.of("currentPin","012345","newPin","222333");
        assertEquals(403,call("PUT","/api/v1/account/pin",body,login(phone,"012345").access()).status());
        assertEquals(403,call("PUT","/api/v1/account/pin",Map.of("currentPin","048291","newPin","222333"),root).status());
        assertEquals(401,call("PUT","/api/v1/account/pin",body,null).status());
    }
    @Test void ownerSuspendsReactivatesAndChangesEmployeePin() throws Exception {
        var owner=partner();var other=partner();String phone=nextPhone();
        var created=employee(owner,phone,Set.of("sale.confirm"));String user=created.text("userId");
        var list=call("GET","/api/v1/partner/employees",null,owner.access());
        assertEquals(200,list.status());assertEquals(1,list.json().size());
        var view=list.json().get(0);assertEquals("ACTIVE",view.path("status").asString());assertEquals("موظف اختبار",view.path("name").asString());
        assertTrue(view.path("permissions").toString().contains("sale.confirm"));assertFalse(list.json().toString().contains("012345"));
        assertEquals(0,call("GET","/api/v1/partner/employees",null,other.access()).json().size());
        var worker=login(phone,"012345");String base="/api/v1/partner/employees/"+user;
        assertEquals(403,call("PUT",base+"/status",Map.of("status","SUSPENDED"),worker.access()).status());
        assertEquals(404,call("PUT",base+"/status",Map.of("status","SUSPENDED"),other.access()).status());
        assertEquals(204,call("PUT",base+"/status",Map.of("status","SUSPENDED"),owner.access()).status());
        assertEquals(401,session(worker.access()).status());assertEquals(401,login(phone,"012345").status());
        assertEquals("SUSPENDED",call("GET","/api/v1/partner/employees",null,owner.access()).json().get(0).path("status").asString());
        assertEquals(204,call("PUT",base+"/status",Map.of("status","ACTIVE"),owner.access()).status());
        assertEquals(401,session(worker.access()).status());
        worker=login(phone,"012345");assertEquals(200,worker.status());
        assertEquals(404,call("PUT",base+"/pin",Map.of("pin","555666"),other.access()).status());
        assertEquals(400,call("PUT",base+"/pin",Map.of("pin","55566"),owner.access()).status());
        assertEquals(204,call("PUT",base+"/pin",Map.of("pin","555666"),owner.access()).status());
        assertEquals(401,session(worker.access()).status());assertEquals(401,login(phone,"012345").status());
        assertEquals(200,login(phone,"555666").status());
    }
    @Test void ownerReassignsEmployeeStoresAcrossOwnStoresOnly() throws Exception {
        var owner=partner();var other=partner();String phone=nextPhone();String user=employee(owner,phone,Set.of()).text("userId");
        var second=call("POST","/api/v1/partner/stores",storeBody("متجر آخر"),owner.access());
        String base="/api/v1/partner/employees/"+user+"/stores";
        assertEquals(204,call("PUT",base,Map.of("storeIds",Set.of(second.text("storeId"))),owner.access()).status());
        assertEquals(List.of(second.text("storeId")),jdbc.queryForList("SELECT store_id FROM partner_employee_stores WHERE user_id=?",String.class,user));
        assertEquals(400,call("PUT",base,Map.of("storeIds",Set.of(other.created().text("storeId"))),owner.access()).status());
        assertEquals(400,call("PUT",base,Map.of("storeIds",Set.of()),owner.access()).status());
        assertEquals(List.of(second.text("storeId")),jdbc.queryForList("SELECT store_id FROM partner_employee_stores WHERE user_id=?",String.class,user));
        assertEquals(200,login(phone,"012345").status());
    }

    // ---- App releases and in-app update policy (approved 2026-11-17) ----
    static volatile Optional<com.jovx.api.releases.model.ReleaseModels.ReleaseManifest> MANIFEST=Optional.empty();
    static com.jovx.api.releases.service.ManifestClient testManifests(){return (app,platform)->MANIFEST;}
    static void manifest(int build){
        MANIFEST=Optional.of(new com.jovx.api.releases.model.ReleaseModels.ReleaseManifest(
            com.jovx.api.releases.model.ReleaseModels.AppKind.MEMBER,com.jovx.api.releases.model.ReleaseModels.Platform.ANDROID,
            "0.18."+build,build,"https://github.com/o/jovx-releases/releases/download/member-"+build+"/app.apk",
            "c".repeat(64),4_000_000L,"ملاحظات "+build,"abc1234"));
    }
    void resetReleases(){
        jdbc.update("DELETE FROM app_release_events");jdbc.update("DELETE FROM app_releases");
        jdbc.update("UPDATE app_update_settings SET min_supported_build=0");MANIFEST=Optional.empty();
    }
    Response check(int build) throws Exception {return call("GET","/api/v1/app-updates?app=member&platform=android&build="+build,null,null);}
    String importBuild(String access,int build) throws Exception {
        manifest(build);var r=call("POST","/api/v1/admin/app-releases/member/import",null,access);
        assertEquals(200,r.status());assertEquals("DRAFT",r.text("status"));return r.text("id");
    }
    Response releaseAction(String access,String id,String action) throws Exception {
        return call("POST","/api/v1/admin/app-releases/releases/"+id+"/"+action,null,access);
    }
    Response releasePolicy(String access,String id,String mode,String requiredAfter) throws Exception {
        var body=new LinkedHashMap<String,Object>();body.put("mode",mode);body.put("requiredAfter",requiredAfter);
        return call("PUT","/api/v1/admin/app-releases/releases/"+id+"/policy",body,access);
    }

    @Test void publicUpdateCheckFollowsTheAdministrationPolicy() throws Exception {
        resetReleases();String root=superAccess();
        assertEquals("NONE",check(100).text("action"));
        String id=importBuild(root,101);
        assertEquals("NONE",check(100).text("action"),"drafts are never offered");
        assertEquals(204,releaseAction(root,id,"publish").status());
        var optional=check(100);
        assertEquals("OPTIONAL",optional.text("action"));assertEquals(101,optional.json().path("latestBuildNumber").asInt());
        assertEquals("c".repeat(64),optional.text("sha256"));
        assertEquals("NONE",check(101).text("action"));

        String deadline=START.plus(Duration.ofDays(2)).toString();
        assertEquals(204,releasePolicy(root,id,"OPTIONAL",deadline).status());
        assertEquals("OPTIONAL",check(100).text("action"));assertFalse(check(100).text("requiredAfter").isBlank());
        TIME.advance(Duration.ofDays(3));
        assertEquals("REQUIRED",check(100).text("action"),"deadline passed");
        TIME.instant=START;

        assertEquals(204,releasePolicy(root,id,"REQUIRED",null).status());
        assertEquals("REQUIRED",check(100).text("action"));
        assertEquals(400,releasePolicy(root,id,"REQUIRED",deadline).status());
    }
    @Test void minimumSupportedBuildForcesOlderAppsAndCannotOutrunPublishedBuilds() throws Exception {
        resetReleases();String root=superAccess();
        String id=importBuild(root,201);releaseAction(root,id,"publish");
        var tooHigh=call("PUT","/api/v1/admin/app-releases/member/min-supported",Map.of("minSupportedBuild",202),root);
        assertEquals(409,tooHigh.status());assertEquals("MIN_BUILD_NOT_PUBLISHED",tooHigh.text("code"));
        assertEquals(204,call("PUT","/api/v1/admin/app-releases/member/min-supported",Map.of("minSupportedBuild",201),root).status());
        assertEquals("REQUIRED",check(200).text("action"));
        var blocked=releaseAction(root,id,"withdraw");
        assertEquals(409,blocked.status());assertEquals("LOWER_MIN_SUPPORTED_FIRST",blocked.text("code"));
        call("PUT","/api/v1/admin/app-releases/member/min-supported",Map.of("minSupportedBuild",0),root);
        assertEquals(204,releaseAction(root,id,"withdraw").status());
        assertEquals("NONE",check(200).text("action"),"withdrawn builds are no longer offered");
        assertEquals(409,releaseAction(root,id,"publish").status());
    }
    @Test void importIsIdempotentAndManifestStateIsVisible() throws Exception {
        resetReleases();String root=superAccess();
        var missing=call("GET","/api/v1/admin/app-releases/member/manifest",null,root);
        assertEquals(503,missing.status());assertEquals("MANIFEST_NOT_CONFIGURED",missing.text("code"));
        manifest(301);
        var view=call("GET","/api/v1/admin/app-releases/member/manifest",null,root);
        assertEquals(301,view.json().path("buildNumber").asInt());assertFalse(view.json().path("imported").asBoolean());
        String first=importBuild(root,301),second=importBuild(root,301);
        assertEquals(first,second);
        assertTrue(call("GET","/api/v1/admin/app-releases/member/manifest",null,root).json().path("imported").asBoolean());
        var overview=call("GET","/api/v1/admin/app-releases/member",null,root);
        assertEquals(1,overview.json().path("releases").size());
    }
    @Test void onlySuperAdminOrGrantedAdminManagesReleases() throws Exception {
        resetReleases();String root=superAccess();var admin=login(account(AccountRole.ADMIN));var owner=partner();
        manifest(401);
        for(String token:List.of(admin.access(),owner.access()))
            assertEquals(403,call("POST","/api/v1/admin/app-releases/member/import",null,token).status());
        assertEquals(401,call("POST","/api/v1/admin/app-releases/member/import",null,null).status());
        assertEquals(204,roleGrants(root,"ADMIN",Set.of("partner.create")).status());
        assertEquals(403,call("GET","/api/v1/admin/app-releases/member",null,admin.access()).status());
        assertEquals(204,roleGrants(root,"ADMIN",Set.of("app.release.manage")).status());
        String id=importBuild(admin.access(),401);
        assertEquals(204,releaseAction(admin.access(),id,"publish").status());
        assertEquals(400,call("GET","/api/v1/app-updates?app=shop&platform=android&build=1",null,null).status());
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM app_release_events WHERE action='RELEASE_PUBLISHED'",Integer.class));
    }

    // ---- membership foundation (plan decisions 43-57) ----
    String plan(String access,String name,int months,boolean active) throws Exception {
        var r=call("POST","/api/v1/admin/membership-plans",Map.of("name",name,"priceAmount",1900,"currency","SYP",
            "durationMonths",months,"familySlots",5,"active",active),access);
        assertEquals(201,r.status(),r.json().toString());return r.text("id");
    }
    String stockCard(String access) throws Exception {
        String code="JVXC-"+UUID.randomUUID().toString().replace("-","").substring(0,16).toUpperCase(Locale.ROOT);
        var r=call("POST","/api/v1/admin/member-cards/batches",Map.of("batchLabel","دفعة اختبار","codes",List.of(code)),access);
        assertEquals(200,r.status());assertEquals(1,r.json().path("added").asInt());return code;
    }
    Response sell(String access,UUID request,String phone,String code,String planId) throws Exception {
        return call("POST","/api/v1/admin/members",Map.of("requestId",request,"name","عضو اختبار","phone",phone,"cardCode",code,
            "planId",planId,"payment",Map.of("method","CASH","reference","إيصال 7")),access);
    }
    Response claim(String code,String phone,String pin) throws Exception {
        return call("POST","/api/v1/member/claim",Map.of("cardCode",code,"phone",phone,"pin",pin),null);
    }
    record SoldMember(String id,String phone,String code){}
    SoldMember claimedMember(String root,String planId) throws Exception {
        String phone=nextPhone(),code=stockCard(root);
        var sold=sell(root,UUID.randomUUID(),phone,code,planId);assertEquals(200,sold.status(),sold.json().toString());
        assertEquals(204,claim(code,phone,"048291").status());
        return new SoldMember(sold.text("memberId"),phone,code);
    }
    Instant instantOf(Response r,String field){return Instant.parse(r.text(field));}

    @Test void soldMemberClaimsPinWithTheCardThenLogsInAndGetsAShortLivedQr() throws Exception {
        String root=superAccess(),planId=plan(root,"بلاتينية "+UUID.randomUUID(),6,true),phone=nextPhone(),code=stockCard(root);
        UUID request=UUID.randomUUID();
        var sold=sell(root,request,phone,code,planId);
        assertEquals(200,sold.status(),sold.json().toString());
        assertTrue(sold.text("memberNumber").matches("JVX-[0-9]{6,}"));
        assertEquals(com.jovx.api.membership.model.MembershipModels.Terms.end(START,6),instantOf(sold,"expiresAt"));
        assertEquals(1900,sold.json().path("payment").path("amount").asLong());
        assertEquals("SYP",sold.json().path("payment").path("currency").asString());
        var replay=sell(root,request,phone,code,planId);
        assertEquals(200,replay.status());assertEquals(sold.text("memberId"),replay.text("memberId"),"a retried sale returns the first result");
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM membership_payments WHERE member_account_id=?",Integer.class,sold.text("memberId")));

        assertEquals(401,login(phone,"048291").status(),"no PIN before the card claim");
        assertEquals("CARD_CLAIM_REJECTED",claim(code,nextPhone(),"048291").text("code"),"phone must match the seller's record");
        assertEquals("CARD_CLAIM_REJECTED",claim("UNKNOWN-CARD-1",phone,"048291").text("code"));
        assertEquals(204,claim(code,phone,"048291").status());
        var again=claim(code,phone,"112233");
        assertEquals(409,again.status());assertEquals("ACCOUNT_ALREADY_CLAIMED",again.text("code"));

        var member=login(phone,"048291");
        assertEquals(200,member.status());assertEquals("MEMBER",member.json().path("account").path("role").asString());
        var me=call("GET","/api/v1/member/me",null,member.access());
        assertEquals(200,me.status());assertEquals("ACTIVE",me.text("state"));assertTrue(me.json().path("canShowQr").asBoolean());
        String masked=me.json().path("card").path("maskedCode").asString();
        assertTrue(masked.endsWith(code.substring(code.length()-4)));assertFalse(masked.contains(code),"full card code never leaves the server");
        var token=call("POST","/api/v1/member/scan-token",null,member.access());
        assertEquals(200,token.status());assertTrue(token.text("token").startsWith("JX2."));
        assertEquals(TIME.instant().plusSeconds(60),instantOf(token,"expiresAt"));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM member_scan_tokens WHERE token_digest=?",Integer.class,token.text("token")),"only the digest is stored");
        call("POST","/api/v1/member/scan-token",null,member.access());
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM member_scan_tokens WHERE member_account_id=?",Integer.class,sold.text("memberId")),"a new QR replaces the old one");
        var points=call("GET","/api/v1/member/points",null,member.access());
        assertEquals(0,points.json().path("balance").asLong());assertFalse(points.json().path("frozen").asBoolean());
        assertEquals(0,call("GET","/api/v1/member/points/history",null,member.access()).json().size());
    }

    @Test void renewalAddsToTheRemainingTermOrStartsTodayAndAnExpiredMemberSeesAFrozenState() throws Exception {
        String root=superAccess(),planId=plan(root,"تجديد "+UUID.randomUUID(),6,true);
        var m=claimedMember(root,planId);
        Instant first=com.jovx.api.membership.model.MembershipModels.Terms.end(START,6);
        TIME.advance(Duration.ofDays(10));
        var renewed=call("POST","/api/v1/admin/members/"+m.id()+"/renewals",Map.of("requestId",UUID.randomUUID(),"planId",planId,
            "payment",Map.of("method","TRANSFER")),superAccess());
        assertEquals(200,renewed.status(),renewed.json().toString());
        Instant second=com.jovx.api.membership.model.MembershipModels.Terms.end(first,6);
        assertEquals(second,instantOf(renewed,"expiresAt"),"remaining days are kept");

        TIME.instant=second.plus(Duration.ofDays(3));
        var member=login(m.phone(),"048291");
        assertEquals(200,member.status(),"an expired member still logs in");
        assertEquals("EXPIRED",call("GET","/api/v1/member/me",null,member.access()).text("state"));
        var qr=call("POST","/api/v1/member/scan-token",null,member.access());
        assertEquals(409,qr.status());assertEquals("MEMBERSHIP_EXPIRED",qr.text("code"));
        assertTrue(call("GET","/api/v1/member/points",null,member.access()).json().path("frozen").asBoolean());

        var late=call("POST","/api/v1/admin/members/"+m.id()+"/renewals",Map.of("requestId",UUID.randomUUID(),"planId",planId,
            "payment",Map.of("method","CASH")),superAccess());
        assertEquals(com.jovx.api.membership.model.MembershipModels.Terms.end(TIME.instant(),6),instantOf(late,"expiresAt"),"an expired term restarts today");
        var detail=call("GET","/api/v1/admin/members/"+m.id(),null,superAccess());
        assertEquals(3,detail.json().path("payments").size());assertTrue(detail.json().path("claimed").asBoolean());
    }

    @Test void cardStockRejectsDuplicatesAndAFailedSaleLeavesNothingBehind() throws Exception {
        String root=superAccess(),planId=plan(root,"مخزون "+UUID.randomUUID(),6,true),code=stockCard(root);
        var batch=call("POST","/api/v1/admin/member-cards/batches",Map.of("batchLabel","ثانية","codes",List.of(code," NEW-"+UUID.randomUUID().toString().substring(0,8)+" ","bad code!")),root);
        assertEquals(1,batch.json().path("added").asInt());
        assertEquals(code,batch.json().path("alreadyRegistered").get(0).asString());
        assertEquals("bad code!",batch.json().path("invalid").get(0).asString());

        var taken=claimedMember(root,planId);
        var duplicatePhone=sell(root,UUID.randomUUID(),taken.phone(),code,planId);
        assertEquals(409,duplicatePhone.status());assertEquals("PHONE_ALREADY_REGISTERED",duplicatePhone.text("code"));
        assertEquals("IN_STOCK",jdbc.queryForObject("SELECT status FROM member_cards WHERE code=?",String.class,code),"rolled back");
        var soldCard=sell(root,UUID.randomUUID(),nextPhone(),taken.code(),planId);
        assertEquals(409,soldCard.status());assertEquals("CARD_NOT_AVAILABLE",soldCard.text("code"));
        String inactive=plan(root,"موقوفة "+UUID.randomUUID(),6,false);
        assertEquals("PLAN_INACTIVE",sell(root,UUID.randomUUID(),nextPhone(),code,inactive).text("code"));
        var publicPlans=call("GET","/api/v1/public/membership-plans",null,null);
        assertEquals(200,publicPlans.status());
        assertTrue(publicPlans.json().toString().contains(planId));assertFalse(publicPlans.json().toString().contains(inactive));
    }

    @Test void suspendedCardStopsTheQrButNotTheLoginAndAdministrationResetsThePin() throws Exception {
        String root=superAccess(),planId=plan(root,"إيقاف "+UUID.randomUUID(),6,true);
        var m=claimedMember(root,planId);
        var member=login(m.phone(),"048291");
        assertEquals(200,call("POST","/api/v1/member/scan-token",null,member.access()).status());
        assertEquals(204,call("POST","/api/v1/admin/members/"+m.id()+"/card/suspension",null,root).status());
        assertEquals(409,call("POST","/api/v1/admin/members/"+m.id()+"/card/suspension",null,root).status());
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM member_scan_tokens WHERE member_account_id=? AND consumed_at IS NULL",Integer.class,m.id()));
        assertEquals("CARD_NOT_ACTIVE",call("POST","/api/v1/member/scan-token",null,member.access()).text("code"));
        assertEquals("SUSPENDED",call("GET","/api/v1/member/me",null,member.access()).json().path("card").path("status").asString());

        assertEquals(204,call("PUT","/api/v1/admin/members/"+m.id()+"/pin",Map.of("pin","553311"),root).status());
        assertEquals(401,call("GET","/api/v1/member/me",null,member.access()).status(),"reset ends every session");
        assertEquals(401,login(m.phone(),"048291").status());
        assertEquals(200,login(m.phone(),"553311").status());

        String phone=nextPhone(),code=stockCard(root);
        String unclaimed=sell(root,UUID.randomUUID(),phone,code,planId).text("memberId");
        var refused=call("PUT","/api/v1/admin/members/"+unclaimed+"/pin",Map.of("pin","553311"),root);
        assertEquals(409,refused.status());assertEquals("MEMBER_NOT_CLAIMED",refused.text("code"));
        assertEquals(204,call("POST","/api/v1/admin/members/"+unclaimed+"/card/suspension",null,root).status());
        assertEquals("CARD_SUSPENDED",claim(code,phone,"048291").text("code"));
    }

    @Test void onlySuperAdminOrAdminWithMemberManageSellsAndSearchesMembers() throws Exception {
        String root=superAccess(),planId=plan(root,"صلاحيات "+UUID.randomUUID(),6,true);
        var m=claimedMember(root,planId);
        var admin=login(account(AccountRole.ADMIN));var owner=partner();var member=login(m.phone(),"048291");
        for(String token:List.of(admin.access(),owner.access(),member.access()))
            assertEquals(403,call("GET","/api/v1/admin/members",null,token).status());
        assertEquals(401,call("GET","/api/v1/admin/members",null,null).status());
        assertEquals(401,call("GET","/api/v1/member/me",null,null).status());
        assertEquals(403,call("GET","/api/v1/member/me",null,root).status());
        assertEquals(204,roleGrants(root,"ADMIN",Set.of("member.manage")).status());
        var found=call("GET","/api/v1/admin/members?q="+m.phone().substring(m.phone().length()-6),null,admin.access());
        assertEquals(200,found.status());assertEquals(m.id(),found.json().get(0).path("id").asString());
        var byNumber=call("GET","/api/v1/admin/members/"+m.id(),null,admin.access()).text("memberNumber");
        assertEquals(m.id(),call("GET","/api/v1/admin/members?q="+byNumber,null,admin.access()).json().get(0).path("id").asString());
        var sold=sell(admin.access(),UUID.randomUUID(),nextPhone(),stockCard(admin.access()),planId);
        assertEquals(200,sold.status());
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM membership_events WHERE member_account_id=? AND action='MEMBERSHIP_SOLD'",
            Integer.class,sold.text("memberId")));
    }

    @Test void guestsSeeOnlyActivePartnersWithTheirDiscount() throws Exception {
        partner();
        String admin=superAccess(),pendingName="معلّق "+UUID.randomUUID();
        var body=registration(nextPhone());body.put("storeName",pendingName);
        assertEquals(201,call("POST","/api/v1/admin/partners",body,admin).status());
        var directory=call("GET","/api/v1/public/partners",null,null);
        assertEquals(200,directory.status());
        String text=directory.json().toString();
        assertTrue(text.contains("مطعم أحمد"));assertFalse(text.contains(pendingName),"pending partners are hidden");
        assertFalse(text.contains("account"),"no account data in the public list");
        assertEquals(10,directory.json().get(0).path("discountPercent").asInt());
    }

    // ---- review fixes (2026-11-19) ----
    @Test void reusedSaleRequestIdWithDifferentDataIsRejectedAndConcurrentRetriesReplay() throws Exception {
        String root=superAccess(),planId=plan(root,"خطة طلب "+UUID.randomUUID(),6,true),phone=nextPhone(),code=stockCard(root);
        UUID request=UUID.randomUUID();
        var pool=Executors.newFixedThreadPool(3);
        try {
            var tries=new ArrayList<Future<Response>>();
            for(int i=0;i<3;i++)tries.add(pool.submit(()->sell(root,request,phone,code,planId)));
            var ids=new HashSet<String>();
            for(var t:tries){var r=t.get();assertEquals(200,r.status(),"concurrent retries replay instead of failing: "+r.json());ids.add(r.text("memberId"));}
            assertEquals(1,ids.size());
        } finally {pool.shutdownNow();}
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM membership_payments WHERE request_id=?",Integer.class,request.toString()));
        var otherPlan=plan(root,"خطة أخرى "+UUID.randomUUID(),3,true);
        var reused=sell(root,request,phone,code,otherPlan);
        assertEquals(409,reused.status());assertEquals("REQUEST_ID_REUSED",reused.text("code"));
        assertEquals("REQUEST_ID_REUSED",sell(root,request,nextPhone(),code,planId).text("code"));
        String member=jdbc.queryForObject("SELECT member_account_id FROM membership_payments WHERE request_id=?",String.class,request.toString()).strip();
        UUID renewal=UUID.randomUUID();
        var body=Map.of("requestId",renewal,"planId",planId,"payment",Map.of("method","CASH"));
        assertEquals(200,call("POST","/api/v1/admin/members/"+member+"/renewals",body,root).status());
        assertEquals(200,call("POST","/api/v1/admin/members/"+member+"/renewals",body,root).status());
        var changed=call("POST","/api/v1/admin/members/"+member+"/renewals",Map.of("requestId",renewal,"planId",otherPlan,"payment",Map.of("method","CASH")),root);
        assertEquals("REQUEST_ID_REUSED",changed.text("code"));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM membership_payments WHERE request_id=?",Integer.class,renewal.toString()));
    }
    @Test void partnerListIsPagedAndSearchedOnTheServer() throws Exception {
        String root=superAccess(),tag="بحث"+UUID.randomUUID().toString().substring(0,8);
        var phones=new ArrayList<String>();
        for(int i=0;i<3;i++){
            String phone=nextPhone();phones.add(phone);var body=registration(phone);body.put("storeName",tag+" متجر "+i);
            assertEquals(201,call("POST","/api/v1/admin/partners",body,root).status());
        }
        var byStore=call("GET","/api/v1/admin/partners?q="+java.net.URLEncoder.encode(tag,java.nio.charset.StandardCharsets.UTF_8),null,root);
        assertEquals(200,byStore.status());assertEquals(3,byStore.json().size());
        var first=call("GET","/api/v1/admin/partners?q="+java.net.URLEncoder.encode(tag,java.nio.charset.StandardCharsets.UTF_8)+"&limit=2",null,root);
        var second=call("GET","/api/v1/admin/partners?q="+java.net.URLEncoder.encode(tag,java.nio.charset.StandardCharsets.UTF_8)+"&limit=2&offset=2",null,root);
        assertEquals(2,first.json().size());assertEquals(1,second.json().size());
        assertFalse(first.json().toString().contains(second.json().get(0).path("partnerId").asString()));
        String digits=phones.get(1).substring(1);
        var byPhone=call("GET","/api/v1/admin/partners?q=00"+digits,null,root);
        assertEquals(1,byPhone.json().size(),"00-prefixed international number finds the owner");
        assertEquals(1,byPhone.json().get(0).path("storeCount").asInt());
        assertEquals(400,call("GET","/api/v1/admin/partners?limit=101",null,root).status());
        assertEquals(0,call("GET","/api/v1/admin/partners?q=%25%25%25"+UUID.randomUUID(),null,root).json().size(),"LIKE wildcards are literal");
    }
    @Test void unknownApiRoutesNeedLoginAndPublicRoutesStayOpen() throws Exception {
        assertEquals(401,call("GET","/api/v1/does-not-exist",null,null).status());
        assertEquals(200,call("GET","/api/v1/public/partners",null,null).status());
        assertEquals(200,call("GET","/api/v1/public/membership-plans",null,null).status());
        assertEquals(401,call("GET","/api/v1/admin/partners",null,null).status());
        assertEquals(403,call("GET","/api/v1/admin/partners",null,login(account(AccountRole.ADMIN)).access()).status());
        assertEquals(401,call("GET","/not-api",null,null).status());
    }

    // ---- partner wallet (plan decisions 18-23, 36, 58-61) ----
    Response walletPost(String path,String access,long amount,UUID request,String method) throws Exception {
        return call("POST",path,Map.of("requestId",request,"amount",amount,"method",method,"reference","REF-1"),access);
    }
    long walletBalance(String partnerId){
        var rows=jdbc.queryForList("SELECT balance FROM partner_wallets WHERE partner_id=?",Long.class,partnerId);
        return rows.isEmpty()?0:rows.getFirst();
    }
    @Test void topupRequestIsApprovedOnceWithinLimitsAndCreditsTheWallet() throws Exception {
        String root=superAccess();var owner=partner();String partnerId=owner.created().text("partnerId");
        assertEquals(200,call("PUT","/api/v1/admin/wallets/settings",Map.of("minTopup",1000,"maxTopup",100000,"lowBalanceThreshold",200),root).status());
        var low=walletPost("/api/v1/partner/wallet/topups",owner.access(),500,UUID.randomUUID(),"CASH");
        assertEquals(400,low.status());assertEquals("TOPUP_OUT_OF_LIMITS",low.text("code"));
        UUID request=UUID.randomUUID();
        var created=walletPost("/api/v1/partner/wallet/topups",owner.access(),50000,request,"SHAM_CASH");
        assertEquals(201,created.status(),created.json().toString());assertEquals("PENDING",created.text("status"));
        assertEquals(created.text("id"),walletPost("/api/v1/partner/wallet/topups",owner.access(),50000,request,"SHAM_CASH").text("id"),"retry returns the first request");
        assertEquals(0,walletBalance(partnerId),"nothing is credited before approval");
        var wallet=call("GET","/api/v1/partner/wallet",null,owner.access());
        assertTrue(wallet.json().path("low").asBoolean());assertTrue(wallet.json().path("canRequestRefund").asBoolean());
        String id=created.text("id");
        assertEquals("APPROVED",call("POST","/api/v1/admin/wallets/requests/"+id+"/approve",null,root).text("status"));
        var again=call("POST","/api/v1/admin/wallets/requests/"+id+"/approve",null,root);
        assertEquals(409,again.status());assertEquals("WALLET_REQUEST_DECIDED",again.text("code"));
        assertEquals(50000,walletBalance(partnerId));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM wallet_ledger WHERE partner_id=? AND kind='TOPUP_APPROVED' AND delta=50000",Integer.class,partnerId));
        assertFalse(call("GET","/api/v1/partner/wallet",null,owner.access()).json().path("low").asBoolean());
    }
    @Test void refundHoldsPointsAtOnceAndRejectionReleasesThem() throws Exception {
        String root=superAccess();var owner=partner();String partnerId=owner.created().text("partnerId");
        assertEquals(201,walletPost("/api/v1/admin/wallets/"+partnerId+"/topups",root,10000,UUID.randomUUID(),"CASH").status());
        assertEquals(10000,walletBalance(partnerId));
        var tooMuch=walletPost("/api/v1/partner/wallet/refunds",owner.access(),10001,UUID.randomUUID(),"TRANSFER");
        assertEquals(409,tooMuch.status());assertEquals("INSUFFICIENT_BALANCE",tooMuch.text("code"));
        var refund=walletPost("/api/v1/partner/wallet/refunds",owner.access(),4000,UUID.randomUUID(),"TRANSFER");
        assertEquals(201,refund.status());
        assertEquals(6000,walletBalance(partnerId));
        assertEquals(4000,jdbc.queryForObject("SELECT held FROM partner_wallets WHERE partner_id=?",Long.class,partnerId));
        var noReason=call("POST","/api/v1/admin/wallets/requests/"+refund.text("id")+"/reject",Map.of("reason"," "),root);
        assertEquals(400,noReason.status());
        assertEquals("REJECTED",call("POST","/api/v1/admin/wallets/requests/"+refund.text("id")+"/reject",Map.of("reason","حساب غير صحيح"),root).text("status"));
        assertEquals(10000,walletBalance(partnerId),"rejection returns the held points");
        assertEquals(0,jdbc.queryForObject("SELECT held FROM partner_wallets WHERE partner_id=?",Long.class,partnerId));

        var second=walletPost("/api/v1/partner/wallet/refunds",owner.access(),3000,UUID.randomUUID(),"CASH");
        var noPayout=call("POST","/api/v1/admin/wallets/requests/"+second.text("id")+"/approve",null,root);
        assertEquals(400,noPayout.status());assertEquals("PAYOUT_METHOD_REQUIRED",noPayout.text("code"));
        var paid=call("POST","/api/v1/admin/wallets/requests/"+second.text("id")+"/approve",Map.of("payoutMethod","CASH","payoutReference","سند 4"),root);
        assertEquals("APPROVED",paid.text("status"));assertEquals("CASH",paid.text("payoutMethod"));
        assertEquals(7000,walletBalance(partnerId));
        assertEquals(0,jdbc.queryForObject("SELECT held FROM partner_wallets WHERE partner_id=?",Long.class,partnerId),"paid out: held points leave the wallet");
    }
    @Test void concurrentRefundsNeverOverdrawTheWallet() throws Exception {
        String root=superAccess();var owner=partner();String partnerId=owner.created().text("partnerId");
        walletPost("/api/v1/admin/wallets/"+partnerId+"/topups",root,5000,UUID.randomUUID(),"CASH");
        var pool=Executors.newFixedThreadPool(4);
        try {
            var tasks=new ArrayList<Callable<Integer>>();
            for(int i=0;i<4;i++)tasks.add(()->walletPost("/api/v1/partner/wallet/refunds",owner.access(),2000,UUID.randomUUID(),"CASH").status());
            var codes=new ArrayList<Integer>();for(var f:pool.invokeAll(tasks))codes.add(f.get());
            assertEquals(2,codes.stream().filter(c->c==201).count(),codes.toString());
            assertEquals(2,codes.stream().filter(c->c==409).count(),codes.toString());
        } finally {pool.shutdownNow();}
        assertEquals(1000,walletBalance(partnerId));
        assertEquals(4000,jdbc.queryForObject("SELECT held FROM partner_wallets WHERE partner_id=?",Long.class,partnerId));
    }
    @Test void workspaceGivesTheOwnerEveryStoreAndAnEmployeeOnlyItsStoresAndGrants() throws Exception {
        String root=superAccess();var owner=partner();String phone=nextPhone();
        assertEquals(201,employee(owner,phone,Set.of("catalog.read","wallet.read")).status());
        var mine=call("GET","/api/v1/partner/workspace",null,owner.access());
        assertEquals(200,mine.status());assertTrue(mine.json().path("owner").asBoolean());
        assertEquals(owner.created().text("partnerId"),mine.text("partnerId"));
        assertEquals(owner.created().text("storeId"),mine.json().path("stores").get(0).path("id").asString());
        assertEquals(0,mine.json().path("permissions").size());
        var staff=login(phone,"012345");
        var theirs=call("GET","/api/v1/partner/workspace",null,staff.access());
        assertEquals(200,theirs.status());assertFalse(theirs.json().path("owner").asBoolean());
        assertEquals(1,theirs.json().path("stores").size());
        assertEquals(List.of("catalog.read","wallet.read"),
            java.util.stream.StreamSupport.stream(theirs.json().path("permissions").spliterator(),false).map(n->n.asString()).toList());
        assertEquals(403,call("GET","/api/v1/partner/workspace",null,root).status());
        assertEquals(401,call("GET","/api/v1/partner/workspace",null,null).status());
    }
    @Test void walletAccessFollowsOwnershipGrantsAndPartnerStatus() throws Exception {
        String root=superAccess();var owner=partner();var other=partner();String partnerId=owner.created().text("partnerId");
        var admin=login(account(AccountRole.ADMIN));
        assertEquals(403,call("GET","/api/v1/admin/wallets",null,admin.access()).status());
        assertEquals(403,call("GET","/api/v1/admin/wallets",null,owner.access()).status());
        assertEquals(401,call("GET","/api/v1/partner/wallet",null,null).status());
        assertEquals(204,roleGrants(root,"ADMIN",Set.of("wallet.manage")).status());
        assertEquals(200,call("GET","/api/v1/admin/wallets",null,admin.access()).status());

        String viewer=nextPhone(),payer=nextPhone();
        assertEquals(201,employee(owner,viewer,Set.of("wallet.read")).status());
        assertEquals(201,employee(owner,payer,Set.of("wallet.read","wallet.topup.request")).status());
        var v=login(viewer,"012345");var p=login(payer,"012345");
        var seen=call("GET","/api/v1/partner/wallet",null,v.access());
        assertEquals(200,seen.status());assertEquals(partnerId,seen.text("partnerId"));
        assertFalse(seen.json().path("canRequestTopup").asBoolean());assertFalse(seen.json().path("canRequestRefund").asBoolean());
        assertEquals(403,walletPost("/api/v1/partner/wallet/topups",v.access(),5000,UUID.randomUUID(),"CASH").status());
        assertEquals(201,walletPost("/api/v1/partner/wallet/topups",p.access(),5000,UUID.randomUUID(),"CASH").status());
        assertEquals(403,walletPost("/api/v1/partner/wallet/refunds",p.access(),1,UUID.randomUUID(),"CASH").status(),"refunds are owner-only");
        assertEquals(other.created().text("partnerId"),call("GET","/api/v1/partner/wallet",null,other.access()).text("partnerId"));

        jdbc.update("UPDATE partner_owners SET status='SUSPENDED' WHERE id=?",partnerId);
        var frozen=call("GET","/api/v1/admin/wallets/"+partnerId,null,root);
        assertTrue(frozen.json().path("frozen").asBoolean());
        var direct=walletPost("/api/v1/admin/wallets/"+partnerId+"/topups",root,5000,UUID.randomUUID(),"CASH");
        assertEquals(409,direct.status());assertEquals("PARTNER_NOT_ACTIVE",direct.text("code"));
        String pending=jdbc.queryForObject("SELECT id FROM wallet_requests WHERE partner_id=? AND status='PENDING'",String.class,partnerId).strip();
        assertEquals("PARTNER_NOT_ACTIVE",call("POST","/api/v1/admin/wallets/requests/"+pending+"/approve",null,root).text("code"));
        jdbc.update("UPDATE partner_owners SET status='ACTIVE' WHERE id=?",partnerId);
    }

    // ---- Default city/neighborhood (user decision 2026-11-19) ----
    Response putDefaults(String access,Object city,Object address) throws Exception {
        var body=new LinkedHashMap<String,Object>();body.put("cityId",city);body.put("addressId",address);
        return call("PUT","/api/v1/admin/reference-defaults",body,access);
    }
    @Test void defaultLocationIsSetByAdministrationAndOfferedToFormsAndGuests() throws Exception {
        jdbc.update("DELETE FROM reference_defaults");
        String root=superAccess();var admin=login(account(AccountRole.ADMIN));var owner=partner();
        assertEquals(204,call("GET","/api/v1/public/location-defaults",null,null).status());
        assertTrue(call("GET","/api/v1/reference-data",null,root).json().path("defaults").isNull());
        assertEquals(401,putDefaults(null,CITY,ADDRESS).status());
        assertEquals(403,putDefaults(admin.access(),CITY,ADDRESS).status());
        assertEquals(403,putDefaults(owner.access(),CITY,ADDRESS).status());
        var set=putDefaults(root,CITY,ADDRESS);
        assertEquals(200,set.status());assertEquals(CITY.toString(),set.text("cityId"));assertEquals("حي اختبار",set.text("addressName"));
        var guest=call("GET","/api/v1/public/location-defaults",null,null);
        assertEquals(200,guest.status());assertEquals(ADDRESS.toString(),guest.text("addressId"));assertEquals("مدينة اختبار",guest.text("cityName"));
        assertFalse(guest.json().toString().contains("updated"));
        var forms=call("GET","/api/v1/reference-data",null,owner.access()).json().path("defaults");
        assertEquals(CITY.toString(),forms.path("cityId").asString());assertEquals(ADDRESS.toString(),forms.path("addressId").asString());
        assertEquals(204,roleGrants(root,"ADMIN",Set.of("partner.create")).status());
        String other=UUID.randomUUID().toString();
        jdbc.update("INSERT INTO addresses(id,city_id,name) VALUES (?,?,?)",other,OTHER_CITY.toString(),"حي آخر "+other);
        var changed=putDefaults(admin.access(),OTHER_CITY,other);
        assertEquals(200,changed.status());assertEquals(OTHER_CITY.toString(),changed.text("cityId"));
        assertEquals(other,call("GET","/api/v1/public/location-defaults",null,null).text("addressId"));
    }
    @Test void defaultNeighborhoodMustBelongToTheDefaultCity() throws Exception {
        String root=superAccess();
        assertEquals(204,roleGrants(root,"ADMIN",Set.of()).status());
        var before=jdbc.queryForList("SELECT city_id,address_id FROM reference_defaults");
        var mismatch=putDefaults(root,OTHER_CITY,ADDRESS);
        assertEquals(400,mismatch.status());assertEquals("INVALID_LOCATION_OR_CATEGORY",mismatch.text("code"));
        assertEquals(400,putDefaults(root,UUID.randomUUID(),UUID.randomUUID()).status());
        assertEquals(400,putDefaults(root,CITY,null).status());
        assertEquals(before,jdbc.queryForList("SELECT city_id,address_id FROM reference_defaults"));
    }
    @Test void publicDirectoryFiltersByStoreCityAndHidesSuspendedStores() throws Exception {
        String root=superAccess();var here=partner();var there=partner();
        String other=UUID.randomUUID().toString();
        jdbc.update("INSERT INTO addresses(id,city_id,name) VALUES (?,?,?)",other,OTHER_CITY.toString(),"حي بعيد "+other);
        var body=storeBody("متجر المدينة الثانية");body.put("cityId",OTHER_CITY);body.put("addressId",other);
        var far=call("POST",partnerPath(there)+"/stores",body,root);assertEquals(201,far.status());
        String hereStore=here.created().text("storeId"),farStore=far.text("storeId");
        var inCity=call("GET","/api/v1/public/partners?cityId="+CITY,null,null).json().toString();
        assertTrue(inCity.contains(hereStore));assertFalse(inCity.contains(farStore));
        var inOther=call("GET","/api/v1/public/partners?cityId="+OTHER_CITY,null,null).json().toString();
        assertTrue(inOther.contains(farStore));assertFalse(inOther.contains(hereStore));
        var all=call("GET","/api/v1/public/partners",null,null).json().toString();
        assertTrue(all.contains(hereStore));assertTrue(all.contains(farStore));
        assertFalse(all.contains(here.created().text("partnerId")),"owner ids are not exposed");
        assertEquals(204,call("PUT",partnerPath(there)+"/stores/"+farStore+"/status",Map.of("status","SUSPENDED"),root).status());
        assertFalse(call("GET","/api/v1/public/partners?cityId="+OTHER_CITY,null,null).json().toString().contains(farStore));
    }

    // ---- store catalog (plan decisions 11, 12, 14, 24-28, 62-68) ----
    Map<String,Object> productBody(long price,Long memberPrice,long points,boolean tracked,Integer stock){
        var p=new LinkedHashMap<String,Object>();
        p.put("categoryId","7f0b1c1e-0001-4a00-8000-000000000001");p.put("name","برغر");p.put("description","لحم مشوي");
        p.put("priceAmount",price);p.put("memberPriceAmount",memberPrice);p.put("points",points);p.put("stockTracked",tracked);p.put("stock",stock);
        return p;
    }
    Response createProduct(Partner owner,UUID request,Map<String,Object> product) throws Exception {
        return call("POST","/api/v1/partner/stores/"+owner.created().text("storeId")+"/products",Map.of("requestId",request,"product",product),owner.access());
    }
    Response publicProducts(Partner owner) throws Exception {
        return call("GET","/api/v1/public/stores/"+owner.created().text("storeId")+"/products",null,null);
    }
    Response upload(String access,String productId,byte[] bytes) throws Exception {
        String boundary="jovx"+UUID.randomUUID();
        var out=new java.io.ByteArrayOutputStream();
        out.write(("--"+boundary+"\r\nContent-Disposition: form-data; name=\"file\"; filename=\"p.bin\"\r\nContent-Type: application/octet-stream\r\n\r\n").getBytes());
        out.write(bytes);out.write(("\r\n--"+boundary+"--\r\n").getBytes());
        var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/v1/partner/products/"+productId+"/images"))
            .timeout(Duration.ofSeconds(20)).header("Authorization","Bearer "+access).header("Content-Type","multipart/form-data; boundary="+boundary)
            .POST(HttpRequest.BodyPublishers.ofByteArray(out.toByteArray())).build();
        var r=client.send(request,HttpResponse.BodyHandlers.ofString());
        return new Response(r.statusCode(),r.body().isBlank()?JSON.nullNode():JSON.readTree(r.body()),r.headers());
    }
    static byte[] png(int w,int h) throws Exception {
        var image=new java.awt.image.BufferedImage(w,h,java.awt.image.BufferedImage.TYPE_INT_ARGB);
        var out=new java.io.ByteArrayOutputStream();javax.imageio.ImageIO.write(image,"png",out);return out.toByteArray();
    }
    @Test void productIsReviewedBeforeMembersSeeItAtTheMemberPrice() throws Exception {
        String root=superAccess();var owner=partner();
        var lowPoints=createProduct(owner,UUID.randomUUID(),productBody(10000,null,199,false,null));
        assertEquals(400,lowPoints.status());assertEquals("POINTS_BELOW_MINIMUM",lowPoints.text("code"));
        var weakDiscount=createProduct(owner,UUID.randomUUID(),productBody(10000,9500L,200,false,null));
        assertEquals("MEMBER_PRICE_BELOW_MINIMUM_DISCOUNT",weakDiscount.text("code"),"10% minimum: 9,500 gives only 5%");
        UUID request=UUID.randomUUID();
        var created=createProduct(owner,request,productBody(10000,null,200,true,5));
        assertEquals(201,created.status(),created.json().toString());
        assertEquals("PENDING",created.text("reviewStatus"));assertEquals(9000,created.json().path("memberPriceAmount").asLong());
        assertEquals(200,created.json().path("minimumPoints").asLong());
        assertEquals(created.text("id"),createProduct(owner,request,productBody(10000,null,200,true,5)).text("id"),"retry returns the first product");
        assertEquals(0,publicProducts(owner).json().size(),"pending products are hidden");
        String id=created.text("id");
        assertEquals("APPROVED",call("POST","/api/v1/admin/catalog/products/"+id+"/approve",null,root).text("reviewStatus"));
        var shown=publicProducts(owner).json();
        assertEquals(1,shown.size());assertEquals(9000,shown.get(0).path("memberPriceAmount").asLong());
        assertFalse(shown.get(0).path("outOfStock").asBoolean());

        var stocked=call("PUT","/api/v1/partner/products/"+id+"/stock",Map.of("stock",0),owner.access());
        assertEquals("APPROVED",stocked.text("reviewStatus"),"stock alone keeps the approval");
        assertTrue(publicProducts(owner).json().get(0).path("outOfStock").asBoolean());
        var hidden=call("PUT","/api/v1/partner/products/"+id+"/published",Map.of("published",false),owner.access());
        assertEquals("APPROVED",hidden.text("reviewStatus"));assertEquals(0,publicProducts(owner).json().size());
        call("PUT","/api/v1/partner/products/"+id+"/published",Map.of("published",true),owner.access());

        int version=call("GET","/api/v1/partner/stores/"+owner.created().text("storeId")+"/products",null,owner.access()).json().get(0).path("version").asInt();
        var stale=call("PUT","/api/v1/partner/products/"+id,Map.of("expectedVersion",version-1,"product",productBody(12000,8000L,240,true,0)),owner.access());
        assertEquals(409,stale.status());assertEquals("PRODUCT_CHANGED_RELOAD",stale.text("code"));
        var edited=call("PUT","/api/v1/partner/products/"+id,Map.of("expectedVersion",version,"product",productBody(12000,8000L,240,true,0)),owner.access());
        assertEquals("PENDING",edited.text("reviewStatus"),"a price change returns to review");assertTrue(edited.json().path("customMemberPrice").asBoolean());
        assertEquals(0,publicProducts(owner).json().size());
        assertEquals(204,call("DELETE","/api/v1/partner/products/"+id,null,owner.access()).status());
        assertEquals(0,call("GET","/api/v1/partner/stores/"+owner.created().text("storeId")+"/products",null,owner.access()).json().size());
    }
    String named(String root,Partner owner,String name,long price,long points,Integer stock,boolean approve) throws Exception {
        var body=productBody(price,null,points,stock!=null,stock);body.put("name",name);
        var created=createProduct(owner,UUID.randomUUID(),body);assertEquals(201,created.status(),created.json().toString());
        if(approve)assertEquals("APPROVED",call("POST","/api/v1/admin/catalog/products/"+created.text("id")+"/approve",null,root).text("reviewStatus"));
        return created.text("id");
    }
    List<String> names(Response page){
        var out=new ArrayList<String>();for(var n:page.json().path("items"))out.add(n.path("name").asString());return out;
    }
    @Test void publicSearchFiltersSortsAndPagesOnlyVisibleProductsWithArabicInsensitiveText() throws Exception {
        String root=superAccess();var owner=partner();String store=owner.created().text("storeId");
        named(root,owner,"كنافة نابلسية",30000,600,null,true);
        named(root,owner,"إفطار برغر",10000,900,null,true);
        named(root,owner,"عصير برتقال",5000,100,0,true);
        named(root,owner,"منتج معلّق",1000,20,null,false);
        String base="/api/v1/public/products?storeId="+store;

        var all=call("GET",base+"&sort=PRICE_ASC",null,null);
        assertEquals(200,all.status(),all.json().toString());assertEquals(3,all.json().path("total").asLong(),"pending products stay hidden");
        assertEquals(List.of("عصير برتقال","إفطار برغر","كنافة نابلسية"),names(all));
        var first=all.json().path("items").get(0);
        assertEquals(4500,first.path("memberPriceAmount").asLong(),"10% automatic member price");
        assertEquals(store,first.path("storeId").asString());assertFalse(first.path("cityName").asString().isBlank());
        assertEquals(List.of("كنافة نابلسية","إفطار برغر","عصير برتقال"),names(call("GET",base+"&sort=PRICE_DESC",null,null)));
        assertEquals(List.of("إفطار برغر","كنافة نابلسية","عصير برتقال"),names(call("GET",base+"&sort=POINTS_DESC",null,null)));
        assertEquals("كنافة نابلسية",names(call("GET",base+"&sort=SAVING_DESC",null,null)).getFirst(),"3,000 saved");

        assertEquals(List.of("إفطار برغر"),names(call("GET",base+"&q="+enc("افطار"),null,null)),"hamza-insensitive");
        assertEquals(List.of("كنافة نابلسية"),names(call("GET",base+"&q="+enc("كُنافه"),null,null)),"tashkeel and ta marbuta");
        assertEquals(List.of("إفطار برغر","كنافة نابلسية"),names(call("GET",base+"&inStock=true&sort=PRICE_ASC",null,null)));
        assertEquals(List.of("إفطار برغر"),names(call("GET",base+"&minPrice=5000&maxPrice=20000",null,null)));
        assertEquals(0,call("GET",base+"&categoryId=7f0b1c1e-0002-4a00-8000-000000000002",null,null).json().path("total").asLong());
        String storeName=call("GET","/api/v1/partner/workspace",null,owner.access()).json().path("stores").get(0).path("name").asString();
        assertEquals(3,call("GET",base+"&q="+enc(storeName),null,null).json().path("total").asLong(),"the store name matches its products");

        var page=call("GET",base+"&sort=PRICE_ASC&limit=1&offset=1",null,null);
        assertEquals(3,page.json().path("total").asLong());assertEquals(List.of("إفطار برغر"),names(page));
        for(String bad:List.of("&sort=CHEAPEST","&limit=51","&limit=0","&offset=-1","&minPrice=10&maxPrice=5","&minPrice=-1")){
            var refused=call("GET",base+bad,null,null);
            assertEquals(400,refused.status(),bad);assertEquals("INVALID_QUERY",refused.text("code"),bad);
        }
        assertEquals(204,call("PUT","/api/v1/partner/stores/"+store+"/status",Map.of("status","SUSPENDED"),owner.access()).status());
        assertEquals(0,call("GET",base,null,null).json().path("total").asLong(),"a suspended store disappears");
    }
    static String enc(String value){return java.net.URLEncoder.encode(value,java.nio.charset.StandardCharsets.UTF_8);}
    @Test void imagesAreReencodedAtMostFourAndSendTheProductBackToReview() throws Exception {
        String root=superAccess();var owner=partner();
        String id=createProduct(owner,UUID.randomUUID(),productBody(5000,null,100,false,null)).text("id");
        call("POST","/api/v1/admin/catalog/products/"+id+"/approve",null,root);
        var notImage=upload(owner.access(),id,"hello, not an image".getBytes());
        assertEquals(400,notImage.status());assertEquals("IMAGE_TYPE_NOT_ALLOWED",notImage.text("code"));
        var first=upload(owner.access(),id,png(2400,600));
        assertEquals(200,first.status(),first.json().toString());assertEquals("PENDING",first.text("reviewStatus"));
        String url=first.json().path("images").get(0).path("url").asString();
        assertTrue(url.startsWith("/api/v1/public/images/products/"+id+"/"));
        var served=client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+url)).build(),HttpResponse.BodyHandlers.ofByteArray());
        assertEquals(200,served.statusCode());
        var image=javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(served.body()));
        assertEquals(1200,image.getWidth(),"resized to 1200 px");assertEquals(300,image.getHeight());
        assertEquals((byte)0xff,served.body()[0],"re-encoded as JPEG");
        for(int i=0;i<3;i++)assertEquals(200,upload(owner.access(),id,png(50,50)).status());
        var fifth=upload(owner.access(),id,png(50,50));
        assertEquals(409,fifth.status());assertEquals("TOO_MANY_IMAGES",fifth.text("code"));
        String cover=first.json().path("images").get(0).path("id").asString();
        var removed=call("DELETE","/api/v1/partner/products/"+id+"/images/"+cover,null,owner.access());
        assertEquals(3,removed.json().path("images").size());assertEquals(0,removed.json().path("images").get(0).path("position").asInt());
        assertEquals(404,client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+url)).build(),HttpResponse.BodyHandlers.ofByteArray()).statusCode());
        var other=partner();
        assertEquals(404,upload(other.access(),id,png(50,50)).status(),"another partner cannot touch the product");
    }
    @Test void reviewNeedsTheGrantReasonsAndTheRightStateWhileEmployeesStayInTheirStores() throws Exception {
        String root=superAccess();var owner=partner();var other=partner();
        String id=createProduct(owner,UUID.randomUUID(),productBody(8000,null,160,false,null)).text("id");
        var admin=login(account(AccountRole.ADMIN));
        assertEquals(403,call("GET","/api/v1/admin/catalog/products?status=PENDING",null,admin.access()).status());
        assertEquals(204,roleGrants(root,"ADMIN",Set.of("catalog.review")).status());
        assertTrue(call("GET","/api/v1/admin/catalog/products?status=PENDING",null,admin.access()).json().toString().contains(id));
        assertEquals(400,call("POST","/api/v1/admin/catalog/products/"+id+"/reject",Map.of("reason"," "),admin.access()).status());
        var hideEarly=call("POST","/api/v1/admin/catalog/products/"+id+"/hide",Map.of("reason","مخالف"),admin.access());
        assertEquals(409,hideEarly.status());assertEquals("PRODUCT_NOT_APPROVED",hideEarly.text("code"));
        var rejected=call("POST","/api/v1/admin/catalog/products/"+id+"/reject",Map.of("reason","الصورة غير واضحة"),admin.access());
        assertEquals("REJECTED",rejected.text("reviewStatus"));assertEquals("الصورة غير واضحة",rejected.text("reviewReason"));
        assertEquals(409,call("POST","/api/v1/admin/catalog/products/"+id+"/approve",null,admin.access()).status());

        String reader=nextPhone(),writer=nextPhone();
        assertEquals(201,employee(owner,reader,Set.of("catalog.read")).status());
        assertEquals(201,employee(owner,writer,Set.of("catalog.read","catalog.submit")).status());
        var r=login(reader,"012345");var w=login(writer,"012345");
        String store="/api/v1/partner/stores/"+owner.created().text("storeId")+"/products";
        assertEquals(200,call("GET",store,null,r.access()).status());
        assertEquals(403,call("POST",store,Map.of("requestId",UUID.randomUUID(),"product",productBody(1000,null,20,false,null)),r.access()).status());
        assertEquals(201,call("POST",store,Map.of("requestId",UUID.randomUUID(),"product",productBody(1000,null,20,false,null)),w.access()).status());
        var foreign=call("GET","/api/v1/partner/stores/"+other.created().text("storeId")+"/products",null,w.access());
        assertEquals(404,foreign.status());assertEquals("STORE_NOT_FOUND",foreign.text("code"));
        assertEquals(404,call("GET",store,null,other.access()).status());
        var categories=call("GET","/api/v1/public/product-categories",null,null);
        assertEquals(200,categories.status());assertEquals(21,categories.json().size());
    }

    @Test void base64CardCodesFromThePrintedBarcodeAreStockedSoldAndClaimed() throws Exception {
        String root=superAccess(),planId=plan(root,"بطاقة مطبوعة "+UUID.randomUUID(),6,true),phone=nextPhone();
        String code=Base64.getEncoder().encodeToString(UUID.randomUUID().toString().getBytes()).substring(0,22)+"+/==";
        var stocked=call("POST","/api/v1/admin/member-cards/batches",Map.of("batchLabel","PDF417","codes",List.of(code)),root);
        assertEquals(1,stocked.json().path("added").asInt(),stocked.json().toString());
        assertEquals(200,sell(root,UUID.randomUUID(),phone,code,planId).status());
        assertEquals(204,claim(code,phone,"048291").status());
        assertEquals(200,login(phone,"048291").status());
    }

    @Test void aCardNotInStockIsRecordedAtTheSaleButASoldCardStaysRefused() throws Exception {
        String root=superAccess(),planId=plan(root,"بيع مباشر "+UUID.randomUUID(),6,true),phone=nextPhone();
        String code="Kp"+UUID.randomUUID().toString().replace("-","").substring(0,14)+"+Q==";
        var sold=sell(root,UUID.randomUUID(),phone,code,planId);
        assertEquals(200,sold.status(),sold.json().toString());
        assertEquals("ASSIGNED",jdbc.queryForObject("SELECT status FROM member_cards WHERE code=?",String.class,code));
        assertEquals("بيع مباشر",jdbc.queryForObject("SELECT batch_label FROM member_cards WHERE code=?",String.class,code));
        var again=sell(root,UUID.randomUUID(),nextPhone(),code,planId);
        assertEquals(409,again.status());assertEquals("CARD_NOT_AVAILABLE",again.text("code"));
        assertEquals(204,claim(code,phone,"048291").status());
    }

    // ---- point of sale (plan decisions 6-17, 39-41, 70-73) ----
    String approvedProduct(String root,Partner owner,long price,long points,Integer stock) throws Exception {
        var created=createProduct(owner,UUID.randomUUID(),productBody(price,null,points,stock!=null,stock));
        assertEquals(201,created.status(),created.json().toString());
        assertEquals("APPROVED",call("POST","/api/v1/admin/catalog/products/"+created.text("id")+"/approve",null,root).text("reviewStatus"));
        return created.text("id");
    }
    Response scanMember(String access,Partner owner,String code) throws Exception {
        return call("POST","/api/v1/partner/sales/scans",Map.of("storeId",owner.created().text("storeId"),"code",code),access);
    }
    Response cancel(String access,String saleId) throws Exception {
        return call("POST","/api/v1/partner/sales/"+saleId+"/cancellation",Map.of("reason","خطأ في الكمية"),access);
    }
    String requestId(Response sale){return sale.json().path("cancellation").path("id").asString();}
    @Test void anAcceptedCancellationMovesPointsBackRestoresStockAndLeavesTheReports() throws Exception {
        String root=superAccess();var owner=partner();String partnerId=owner.created().text("partnerId");
        var m=claimedMember(root,plan(root,"إلغاء "+UUID.randomUUID(),6,true));
        String product=approvedProduct(root,owner,10000,200,5);
        walletPost("/api/v1/admin/wallets/"+partnerId+"/topups",root,10000,UUID.randomUUID(),"CASH");
        var member=login(m.phone(),"048291");
        var sale=sellProducts(owner.access(),UUID.randomUUID(),scanMember(owner.access(),owner,m.code()).text("scanId"),product,2);
        String saleId=sale.text("id");
        assertEquals(9600,walletBalance(partnerId));assertEquals(400,memberPoints(m.id()));assertEquals("COMPLETED",sale.text("status"));

        var asked=cancel(owner.access(),saleId);
        assertEquals(201,asked.status(),asked.json().toString());
        assertEquals("PENDING",asked.json().path("cancellation").path("status").asString());
        assertEquals("خطأ في الكمية",asked.json().path("cancellation").path("reason").asString());
        assertEquals("CANCEL_ALREADY_PENDING",cancel(owner.access(),saleId).text("code"));
        var waiting=call("GET","/api/v1/member/cancellations",null,member.access());
        assertEquals(1,waiting.json().size());assertEquals(saleId,waiting.json().get(0).path("id").asString());
        assertEquals(9600,walletBalance(partnerId),"nothing moves before the member answers");

        String request=requestId(asked);
        var other=claimedMember(root,plan(root,"آخر "+UUID.randomUUID(),6,true));
        assertEquals(404,call("POST","/api/v1/member/cancellations/"+request+"/accept",null,login(other.phone(),"048291").access()).status());
        assertEquals(403,call("POST","/api/v1/member/cancellations/"+request+"/accept",null,owner.access()).status());

        var done=call("POST","/api/v1/member/cancellations/"+request+"/accept",null,member.access());
        assertEquals(200,done.status(),done.json().toString());
        assertEquals("CANCELLED",done.text("status"));assertEquals("ACCEPTED",done.json().path("cancellation").path("status").asString());
        assertEquals(10000,walletBalance(partnerId));assertEquals(0,memberPoints(m.id()));
        assertEquals(5,jdbc.queryForObject("SELECT stock FROM products WHERE id=?",Integer.class,product));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM wallet_ledger WHERE partner_id=? AND kind='SALE_POINTS_RETURNED' AND delta=400 AND reference_id=?",
            Integer.class,partnerId,saleId));
        boolean returned=false;
        for(var e:call("GET","/api/v1/member/points/history",null,member.access()).json())
            returned|="SALE_CANCELLED".equals(e.path("kind").asString())&&e.path("delta").asLong()==-400;
        assertTrue(returned,"the points leave the member with their own movement");

        var again=call("POST","/api/v1/member/cancellations/"+request+"/accept",null,member.access());
        assertEquals(200,again.status());assertEquals(10000,walletBalance(partnerId),"a repeated accept moves nothing");
        assertEquals("CANCEL_REQUEST_CLOSED",call("POST","/api/v1/member/cancellations/"+request+"/reject",null,member.access()).text("code"));
        assertEquals("SALE_ALREADY_CANCELLED",cancel(owner.access(),saleId).text("code"));

        var summary=call("GET","/api/v1/partner/sales/summary?days=1",null,owner.access());
        assertEquals(0,summary.json().path("sales").asLong(),"cancelled sales leave the reports");
        var purchases=call("GET","/api/v1/member/purchases",null,member.access()).json();
        assertEquals(0,purchases.path("totalSavings").asLong());
        assertEquals("CANCELLED",purchases.path("items").get(0).path("status").asString(),"history still shows it");
    }
    @Test void cancellationWindowsShortPointsRejectionAndTheSellerRule() throws Exception {
        String root=superAccess();var owner=partner();String partnerId=owner.created().text("partnerId");
        var m=claimedMember(root,plan(root,"مهلة "+UUID.randomUUID(),6,true));
        walletPost("/api/v1/admin/wallets/"+partnerId+"/topups",root,100000,UUID.randomUUID(),"CASH");
        var member=login(m.phone(),"048291");
        String cashierPhone=nextPhone();assertEquals(201,employee(owner,cashierPhone,Set.of("member.scan","sale.confirm","sale.free")).status());
        var cashier=login(cashierPhone,"012345");
        String product=approvedProduct(root,owner,10000,200,null);

        String ownerSale=sellProducts(owner.access(),UUID.randomUUID(),scanMember(owner.access(),owner,m.code()).text("scanId"),product,1).text("id");
        String cashierSale=sellProducts(cashier.access(),UUID.randomUUID(),scanMember(cashier.access(),owner,m.code()).text("scanId"),product,1).text("id");
        var notYours=cancel(cashier.access(),ownerSale);
        assertEquals(403,notYours.status());assertEquals("SALE_NOT_YOURS",notYours.text("code"));
        var mine=cancel(cashier.access(),cashierSale);assertEquals(201,mine.status(),mine.json().toString());

        var rejected=call("POST","/api/v1/member/cancellations/"+requestId(mine)+"/reject",null,member.access());
        assertEquals("REJECTED",rejected.json().path("cancellation").path("status").asString());assertEquals("COMPLETED",rejected.text("status"));
        assertEquals(400,memberPoints(m.id()));

        jdbc.update("UPDATE points_accounts SET balance=100 WHERE member_account_id=?",m.id());
        var short_=call("POST","/api/v1/member/cancellations/"+requestId(cancel(owner.access(),cashierSale))+"/accept",null,member.access());
        assertEquals(409,short_.status());assertEquals("INSUFFICIENT_MEMBER_POINTS",short_.text("code"),"spent points block the cancellation");
        assertEquals(100,memberPoints(m.id()));

        TIME.advance(Duration.ofHours(24).plusMinutes(1));
        member=login(m.phone(),"048291");
        String ownerPhone=jdbc.queryForObject("SELECT phone_e164 FROM users WHERE id=?",String.class,owner.created().text("accountId"));
        owner=new Partner(login(ownerPhone,"048291").access(),owner.created());
        var waiting=call("GET","/api/v1/member/cancellations",null,member.access());
        assertEquals(200,waiting.status());
        assertEquals(0,waiting.json().size(),"the member's 24 hours passed");
        var receipt=call("GET","/api/v1/partner/sales/"+cashierSale,null,owner.access());
        assertEquals("EXPIRED",receipt.json().path("cancellation").path("status").asString());
        var late=cancel(owner.access(),ownerSale);
        assertEquals(409,late.status());assertEquals("CANCEL_WINDOW_CLOSED",late.text("code"),"24 hours after the sale");
    }
    JsonNode kind(JsonNode box,String kind){
        for(var n:box.path("items"))if(kind.equals(n.path("kind").asString()))return n;
        return box.path("missing-"+kind);
    }
    JsonNode inbox(String access) throws Exception {
        var r=call("GET","/api/v1/notifications",null,access);assertEquals(200,r.status(),r.json().toString());return r.json();
    }
    @Test void aSaleNotifiesTheMemberInTheSameTransactionAndTheSwitchStopsIt() throws Exception {
        String root=superAccess();var owner=partner();String partnerId=owner.created().text("partnerId");
        var m=claimedMember(root,plan(root,"إشعار "+UUID.randomUUID(),6,true));
        var member=login(m.phone(),"048291");
        String product=approvedProduct(root,owner,10000,200,null);

        var refused=sellProducts(owner.access(),UUID.randomUUID(),scanMember(owner.access(),owner,m.code()).text("scanId"),product,1);
        assertEquals("INSUFFICIENT_WALLET_BALANCE",refused.text("code"));
        assertEquals(0,inbox(member.access()).path("items").size(),"a failed sale leaves no notification");

        walletPost("/api/v1/admin/wallets/"+partnerId+"/topups",root,10000,UUID.randomUUID(),"CASH");
        String saleId=sellProducts(owner.access(),UUID.randomUUID(),scanMember(owner.access(),owner,m.code()).text("scanId"),product,1).text("id");
        var box=inbox(member.access());
        assertEquals(1,box.path("unread").asLong());
        var n=box.path("items").get(0);
        assertEquals("SALE_RECEIPT",n.path("kind").asString());assertEquals(saleId,n.path("data").path("saleId").asString());
        assertTrue(n.path("body").asString().contains("9,000"),n.path("body").asString());
        assertEquals(204,call("PUT","/api/v1/devices/token",Map.of("token","device-"+UUID.randomUUID(),"platform","ANDROID"),member.access()).status());
        assertEquals(204,call("POST","/api/v1/notifications/"+n.path("id").asString()+"/read",null,member.access()).status());
        assertEquals(0,inbox(member.access()).path("unread").asLong());
        assertEquals(404,call("POST","/api/v1/notifications/"+n.path("id").asString()+"/read",null,owner.access()).status(),"another account's notification");

        while(dispatcher.dispatchDue()>0){}
        assertEquals("SKIPPED",jdbc.queryForObject("SELECT status FROM notification_outbox WHERE notification_id=?",String.class,n.path("id").asString()));
        assertEquals("PUSH_NOT_CONFIGURED",jdbc.queryForObject("SELECT last_error FROM notification_outbox WHERE notification_id=?",String.class,n.path("id").asString()));

        var admin=login(account(AccountRole.ADMIN));
        assertEquals(403,call("PUT","/api/v1/admin/notification-settings",Map.of("changes",List.of(Map.of("kind","SALE_RECEIPT","enabled",false))),admin.access()).status());
        try {
            var off=call("PUT","/api/v1/admin/notification-settings",Map.of("changes",List.of(Map.of("kind","SALE_RECEIPT","enabled",false))),root);
            assertEquals(200,off.status(),off.json().toString());
            assertEquals("UNKNOWN_NOTIFICATION_KIND",call("PUT","/api/v1/admin/notification-settings",Map.of("changes",List.of(Map.of("kind","NOPE","enabled",false))),root).text("code"));
            sellProducts(owner.access(),UUID.randomUUID(),scanMember(owner.access(),owner,m.code()).text("scanId"),product,1);
            assertEquals(1,inbox(member.access()).path("items").size(),"a switched-off kind is not raised");
        } finally {
            call("PUT","/api/v1/admin/notification-settings",Map.of("changes",List.of(Map.of("kind","SALE_RECEIPT","enabled",true))),root);
        }
        String employeePhone=nextPhone();employee(owner,employeePhone,Set.of("member.scan"));
        assertEquals(403,call("GET","/api/v1/notifications",null,login(employeePhone,"012345").access()).status(),"employees get no notifications");
    }
    @Test void partnerHearsAboutReviewsWalletDecisionsLowBalanceAndAdministrationMessages() throws Exception {
        String root=superAccess();var owner=partner();String partnerId=owner.created().text("partnerId");
        String product=approvedProduct(root,owner,10000,200,null);
        var reviewed=kind(inbox(owner.access()),"PRODUCT_REVIEWED");
        assertEquals(product,reviewed.path("data").path("productId").asString());

        String topup=walletPost("/api/v1/partner/wallet/topups",owner.access(),5000,UUID.randomUUID(),"CASH").text("id");
        assertEquals(200,call("POST","/api/v1/admin/wallets/requests/"+topup+"/reject",Map.of("reason","لم يصل المبلغ"),root).status());
        assertTrue(kind(inbox(owner.access()),"WALLET_REQUEST_RESULT").path("body").asString().contains("لم يصل المبلغ"));

        int alerted=lowBalance.run();
        assertTrue(alerted>=1);
        assertFalse(kind(inbox(owner.access()),"LOW_BALANCE").isMissingNode());
        assertEquals(0,lowBalance.run(),"once a day");

        var admin=login(account(AccountRole.ADMIN));UUID request=UUID.randomUUID();
        var body=Map.of("requestId",request,"partnerId",partnerId,"title","صيانة","body","توقف قصير الليلة");
        assertEquals(403,call("POST","/api/v1/admin/partner-messages",body,admin.access()).status());
        assertEquals(204,roleGrants(root,"ADMIN",Set.of("notification.manage")).status());
        var sent=call("POST","/api/v1/admin/partner-messages",body,admin.access());
        assertEquals(201,sent.status(),sent.json().toString());assertEquals(1,sent.json().path("recipients").asInt());
        assertEquals(1,call("POST","/api/v1/admin/partner-messages",body,admin.access()).json().path("recipients").asInt());
        long messages=0;for(var n:inbox(owner.access()).path("items"))if("ADMIN_MESSAGE".equals(n.path("kind").asString()))messages++;
        assertEquals(1,messages,"a retried request sends nothing twice");
        assertEquals(204,call("POST","/api/v1/notifications/read-all",null,owner.access()).status());
        assertEquals(0,inbox(owner.access()).path("unread").asLong());
    }
    Response sellProducts(String access,UUID request,String scanId,String productId,int quantity) throws Exception {
        return call("POST","/api/v1/partner/sales",Map.of("requestId",request,"scanId",scanId,"kind","PRODUCTS",
            "lines",List.of(Map.of("productId",productId,"quantity",quantity))),access);
    }
    Response sellFree(String access,UUID request,String scanId,long gross,Object percent,Object points) throws Exception {
        var body=new LinkedHashMap<String,Object>();body.put("requestId",request);body.put("scanId",scanId);body.put("kind","FREE");
        body.put("grossAmount",gross);body.put("discountPercent",percent);body.put("points",points);
        return call("POST","/api/v1/partner/sales",body,access);
    }
    long memberPoints(String memberId){
        var rows=jdbc.queryForList("SELECT balance FROM points_accounts WHERE member_account_id=?",Long.class,memberId);
        return rows.isEmpty()?0:rows.getFirst();
    }

    @Test void productInvoiceDebitsTheWalletCreditsTheMemberAndTakesStockOnce() throws Exception {
        String root=superAccess();var owner=partner();String partnerId=owner.created().text("partnerId");
        var m=claimedMember(root,plan(root,"نقطة بيع "+UUID.randomUUID(),6,true));
        String product=approvedProduct(root,owner,10000,200,5);
        assertEquals(201,walletPost("/api/v1/admin/wallets/"+partnerId+"/topups",root,10000,UUID.randomUUID(),"CASH").status());
        var member=login(m.phone(),"048291");
        String token=call("POST","/api/v1/member/scan-token",null,member.access()).text("token");

        var scan=scanMember(owner.access(),owner,token);
        assertEquals(201,scan.status(),scan.json().toString());
        assertEquals("عضو اختبار",scan.json().path("member").path("name").asString());
        assertTrue(scan.json().path("member").path("memberNumber").asString().startsWith("JVX-"));
        assertEquals(TIME.instant().plus(Duration.ofMinutes(5)),instantOf(scan,"expiresAt"));
        var reused=scanMember(owner.access(),owner,token);
        assertEquals(400,reused.status());assertEquals("MEMBER_SCAN_INVALID",reused.text("code"),"a QR works once");

        var picker=call("GET","/api/v1/partner/sales/stores/"+owner.created().text("storeId")+"/products",null,owner.access());
        assertEquals(1,picker.json().size());assertEquals(9000,picker.json().get(0).path("memberPrice").asLong());

        UUID request=UUID.randomUUID();
        var sale=sellProducts(owner.access(),request,scan.text("scanId"),product,2);
        assertEquals(201,sale.status(),sale.json().toString());
        assertEquals(20000,sale.json().path("grossAmount").asLong());assertEquals(18000,sale.json().path("netAmount").asLong());
        assertEquals(2000,sale.json().path("discountAmount").asLong(),"the member's saving");
        assertEquals(400,sale.json().path("points").asLong());
        assertEquals(9600,walletBalance(partnerId));assertEquals(400,memberPoints(m.id()));
        assertEquals(3,jdbc.queryForObject("SELECT stock FROM products WHERE id=?",Integer.class,product));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM wallet_ledger WHERE partner_id=? AND kind='SALE_POINTS' AND delta=-400 AND reference_id=?",
            Integer.class,partnerId,sale.text("id")));

        var replay=sellProducts(owner.access(),request,scan.text("scanId"),product,2);
        assertEquals(sale.text("id"),replay.text("id"),"a retried submit returns the first sale");
        assertEquals(9600,walletBalance(partnerId),"and changes nothing");
        var different=sellProducts(owner.access(),request,scan.text("scanId"),product,1);
        assertEquals(409,different.status());assertEquals("REQUEST_ID_REUSED",different.text("code"));
        var second=sellProducts(owner.access(),UUID.randomUUID(),scan.text("scanId"),product,1);
        assertEquals(409,second.status());assertEquals("SCAN_ALREADY_USED",second.text("code"),"one scan, one invoice");

        var cardScan=scanMember(owner.access(),owner,m.code());
        assertEquals(201,cardScan.status(),"the printed card has no scan limit");
        var tooMany=sellProducts(owner.access(),UUID.randomUUID(),cardScan.text("scanId"),product,4);
        assertEquals(409,tooMany.status());assertEquals("OUT_OF_STOCK",tooMany.text("code"));
        assertEquals(3,jdbc.queryForObject("SELECT stock FROM products WHERE id=?",Integer.class,product));

        var purchases=call("GET","/api/v1/member/purchases",null,member.access());
        assertEquals(200,purchases.status());assertEquals(2000,purchases.json().path("totalSavings").asLong());
        assertEquals(1,purchases.json().path("items").size());
        assertEquals(2,purchases.json().path("items").get(0).path("lines").get(0).path("quantity").asInt());
        assertEquals("SALE",call("GET","/api/v1/member/points/history",null,member.access()).json().get(0).path("kind").asString());
    }

    @Test void freeSaleKeepsTheMinimumDiscountAndPointsAndAShortWalletChangesNothing() throws Exception {
        String root=superAccess();var owner=partner();String partnerId=owner.created().text("partnerId");
        var m=claimedMember(root,plan(root,"بيع حر "+UUID.randomUUID(),6,true));
        assertEquals(201,walletPost("/api/v1/admin/wallets/"+partnerId+"/topups",root,1000,UUID.randomUUID(),"CASH").status());
        String scan=scanMember(owner.access(),owner,m.code()).text("scanId");

        var short_=sellFree(owner.access(),UUID.randomUUID(),scan,100000,null,null);
        assertEquals(409,short_.status());assertEquals("INSUFFICIENT_WALLET_BALANCE",short_.text("code"));
        assertEquals(1000,walletBalance(partnerId));assertEquals(0,memberPoints(m.id()));
        assertNull(jdbc.queryForObject("SELECT consumed_at FROM sale_scans WHERE id=?",java.sql.Timestamp.class,scan),"the scan is still usable");

        assertEquals(201,walletPost("/api/v1/admin/wallets/"+partnerId+"/topups",root,10000,UUID.randomUUID(),"CASH").status());
        assertEquals("DISCOUNT_BELOW_MINIMUM",sellFree(owner.access(),UUID.randomUUID(),scan,100000,5,null).text("code"));
        assertEquals("POINTS_BELOW_MINIMUM",sellFree(owner.access(),UUID.randomUUID(),scan,100000,null,1999).text("code"));

        var defaults=sellFree(owner.access(),UUID.randomUUID(),scan,100000,null,null);
        assertEquals(201,defaults.status(),defaults.json().toString());
        assertEquals(10000,defaults.json().path("discountAmount").asLong(),"decision 6: 100,000 at 10%");
        assertEquals(90000,defaults.json().path("netAmount").asLong());assertEquals(2000,defaults.json().path("points").asLong());
        assertEquals(9000,walletBalance(partnerId));assertEquals(2000,memberPoints(m.id()));

        String next=scanMember(owner.access(),owner,m.code()).text("scanId");
        var raised=sellFree(owner.access(),UUID.randomUUID(),next,12345,15,300);
        assertEquals(201,raised.status());assertEquals(1852,raised.json().path("discountAmount").asLong(),"15% of 12,345 = 1,851.75");
        assertEquals(10493,raised.json().path("netAmount").asLong());assertEquals(300,raised.json().path("points").asLong());

        String late=scanMember(owner.access(),owner,m.code()).text("scanId");
        TIME.advance(Duration.ofMinutes(5));
        var expired=sellFree(owner.access(),UUID.randomUUID(),late,1000,null,null);
        assertEquals(409,expired.status());assertEquals("SCAN_EXPIRED",expired.text("code"));

        var summary=call("GET","/api/v1/partner/sales/summary?days=7",null,owner.access());
        assertEquals(200,summary.status());assertEquals(2,summary.json().path("sales").asLong());
        assertEquals(1,summary.json().path("customers").asLong());assertEquals(2300,summary.json().path("points").asLong());
    }

    @Test void stoppedCardsAndEndedMembershipsCannotBuy() throws Exception {
        String root=superAccess();var owner=partner();
        var m=claimedMember(root,plan(root,"منتهية "+UUID.randomUUID(),6,true));
        assertEquals("MEMBER_SCAN_INVALID",scanMember(owner.access(),owner,"UNKNOWN-CARD-9").text("code"));
        assertEquals("MEMBER_SCAN_INVALID",scanMember(owner.access(),owner,"JX2.not-a-real-token").text("code"));
        String scan=scanMember(owner.access(),owner,m.code()).text("scanId");
        assertEquals(204,call("POST","/api/v1/admin/members/"+m.id()+"/card/suspension",null,root).status());
        assertEquals("CARD_NOT_ACTIVE",scanMember(owner.access(),owner,m.code()).text("code"));
        assertEquals("CARD_NOT_ACTIVE",sellFree(owner.access(),UUID.randomUUID(),scan,1000,null,null).text("code"),"checked again at the sale");

        var other=claimedMember(root,plan(root,"منتهية 2 "+UUID.randomUUID(),6,true));
        TIME.advance(Duration.ofDays(200));
        var later=partner();
        var ended=scanMember(later.access(),later,other.code());
        assertEquals(409,ended.status());assertEquals("MEMBERSHIP_EXPIRED",ended.text("code"));
    }

    @Test void employeesSellOnlyWithTheirGrantsInTheirStoresAndReadOnlyTheirScope() throws Exception {
        String root=superAccess();var owner=partner();String partnerId=owner.created().text("partnerId");
        var m=claimedMember(root,plan(root,"كاشير "+UUID.randomUUID(),6,true));
        String product=approvedProduct(root,owner,5000,100,null);
        assertEquals(201,walletPost("/api/v1/admin/wallets/"+partnerId+"/topups",root,5000,UUID.randomUUID(),"CASH").status());
        String phone=nextPhone();
        assertEquals(201,employee(owner,phone,Set.of("member.scan","sale.confirm","sale.read.own")).status());
        String cashier=login(phone,"012345").access();

        var context=call("GET","/api/v1/partner/sales/context",null,cashier);
        assertEquals(200,context.status());
        assertTrue(context.json().path("can").path("scan").asBoolean());assertTrue(context.json().path("can").path("sellProducts").asBoolean());
        assertFalse(context.json().path("can").path("freeSale").asBoolean());assertFalse(context.json().path("can").path("reports").asBoolean());
        assertEquals(1,context.json().path("stores").size());

        String scan=scanMember(cashier,owner,m.code()).text("scanId");
        assertEquals(403,sellFree(cashier,UUID.randomUUID(),scan,1000,null,null).status(),"free sale needs its own grant (decision 13)");
        var sold=sellProducts(cashier,UUID.randomUUID(),scan,product,1);
        assertEquals(201,sold.status(),sold.json().toString());
        assertEquals("موظف اختبار",sold.text("sellerName"));
        var ownerScan=scanMember(owner.access(),owner,m.code()).text("scanId");
        assertEquals(404,sellProducts(cashier,UUID.randomUUID(),ownerScan,product,1).status(),"a scan belongs to the seller who made it");
        var ownerSold=sellProducts(owner.access(),UUID.randomUUID(),ownerScan,product,1);
        assertEquals(201,ownerSold.status());

        assertEquals(1,call("GET","/api/v1/partner/sales",null,cashier).json().size(),"own sales only");
        assertEquals(2,call("GET","/api/v1/partner/sales",null,owner.access()).json().size());
        assertEquals(403,call("GET","/api/v1/partner/sales/summary",null,cashier).status());
        assertEquals(404,call("GET","/api/v1/partner/sales/"+ownerSold.text("id"),null,cashier).status());
        assertEquals(200,call("GET","/api/v1/partner/sales/"+sold.text("id"),null,cashier).status());

        var stranger=partner();
        assertEquals(404,scanMember(stranger.access(),owner,m.code()).status(),"another partner's store is not found");
        assertEquals(0,call("GET","/api/v1/partner/sales",null,stranger.access()).json().size());
        var member=login(m.phone(),"048291");
        assertEquals(403,call("GET","/api/v1/partner/sales",null,member.access()).status());
    }

    @Test void concurrentSubmitsOfOneInvoiceSellOnce() throws Exception {
        String root=superAccess();var owner=partner();String partnerId=owner.created().text("partnerId");
        var m=claimedMember(root,plan(root,"تزامن "+UUID.randomUUID(),6,true));
        assertEquals(201,walletPost("/api/v1/admin/wallets/"+partnerId+"/topups",root,10000,UUID.randomUUID(),"CASH").status());
        String scan=scanMember(owner.access(),owner,m.code()).text("scanId");
        UUID request=UUID.randomUUID();
        var pool=Executors.newFixedThreadPool(4);
        try {
            var results=new ArrayList<Future<Response>>();
            for(int i=0;i<4;i++)results.add(pool.submit(()->sellFree(owner.access(),request,scan,50000,null,null)));
            var ids=new HashSet<String>();
            for(var f:results){var r=f.get();assertEquals(201,r.status(),r.json().toString());ids.add(r.text("id"));}
            assertEquals(1,ids.size());
        } finally {pool.shutdownNow();}
        assertEquals(9000,walletBalance(partnerId));assertEquals(1000,memberPoints(m.id()));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM sales WHERE partner_id=?",Integer.class,partnerId));
    }
}