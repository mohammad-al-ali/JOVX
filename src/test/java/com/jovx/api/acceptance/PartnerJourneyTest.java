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
    static final UUID CITY=UUID.randomUUID(),OTHER_CITY=UUID.randomUUID(),ADDRESS=UUID.randomUUID(),CATEGORY=UUID.randomUUID();
    @LocalServerPort int port;
    @Autowired JdbcAccountStore accounts;
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
        body.put("userName","أحمد");body.put("name","مطعم أحمد");body.put("categoryId",CATEGORY);body.put("phone",phone);body.put("pin","048291");
        body.put("discountPercent",10);body.put("branchName","الفرع الرئيسي");body.put("cityId",CITY);body.put("addressId",ADDRESS);
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
        assertEquals(1,context.json().path("establishments").size());
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
        for(String field:List.of("userName","name","categoryId","phone","pin","discountPercent","branchName","cityId","addressId")){
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
    @Test void failedBranchPersistenceRollsBackAccountOwnerAndEstablishment() throws Exception {
        String admin=superAccess(),phone=nextPhone();int before=jdbc.queryForObject("SELECT COUNT(*) FROM partner_establishments",Integer.class);
        jdbc.execute("CREATE FUNCTION test_reject_branch() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'Injected test storage failure'; END $$");
        jdbc.execute("CREATE TRIGGER test_reject_branch BEFORE INSERT ON partner_branches FOR EACH ROW EXECUTE FUNCTION test_reject_branch()");
        try{assertEquals(503,create(admin,phone).status());}finally{jdbc.execute("DROP TRIGGER test_reject_branch ON partner_branches");jdbc.execute("DROP FUNCTION test_reject_branch()");}
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM users WHERE phone_e164=?",Integer.class,phone));
        assertEquals(before,jdbc.queryForObject("SELECT COUNT(*) FROM partner_establishments",Integer.class));
    }
    @Test void ownerCanSeeMultipleEstablishmentsButNeverAnotherPartnersEstablishment() throws Exception {
        String admin=superAccess(),phone=nextPhone();var created=create(admin,phone);activate(admin,created);
        String second=UUID.randomUUID().toString();
        jdbc.update("INSERT INTO partner_establishments(id,partner_id,category_id,name,status) VALUES (?,?,?,'صيدلية','ACTIVE')",second,created.text("partnerId"),CATEGORY.toString());
        var other=create(admin,nextPhone());activate(admin,other);
        var context=call("GET","/api/v1/partner/context",null,login(phone,"048291").access());
        assertEquals(200,context.status());assertEquals(2,context.json().path("establishments").size());
        assertTrue(context.json().toString().contains(second));assertFalse(context.json().toString().contains(other.text("establishmentId")));
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
        body.put("branchIds",branches);body.put("permissions",permissions);return body;
    }
    Response employee(Partner owner,String phone,Set<String> permissions) throws Exception {
        return call("POST","/api/v1/partner/employees",employeeBody(phone,Set.of(owner.created().text("branchId")),permissions),owner.access());
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
        assertEquals(204,call("PUT",path,Map.of("roleKey","MEMBER"),root).status());
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
    @Test void anotherPartnerCannotChangeEmployeePermissionsOrAssignForeignBranches() throws Exception {
        var owner=partner();var other=partner();var created=employee(owner,nextPhone(),Set.of("sale.confirm"));
        assertEquals(404,employeeGrants(other.access(),created.text("userId"),Set.of()).status());
        String phone=nextPhone();var body=employeeBody(phone,Set.of(owner.created().text("branchId"),other.created().text("branchId")),Set.of());
        assertEquals(400,call("POST","/api/v1/partner/employees",body,owner.access()).status());
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM users WHERE phone_e164=?",Integer.class,phone));
    }
    @Test void employeeCanWorkAcrossEstablishmentsOfTheSamePartner() throws Exception {
        var owner=partner();String establishment=UUID.randomUUID().toString(),branch=UUID.randomUUID().toString();
        jdbc.update("INSERT INTO partner_establishments(id,partner_id,category_id,name,status) VALUES (?,?,?,'Second','ACTIVE')",establishment,owner.created().text("partnerId"),CATEGORY.toString());
        jdbc.update("INSERT INTO partner_branches(id,establishment_id,name,city_id,address_id,status) VALUES (?,?,'Second',?,?,'ACTIVE')",branch,establishment,CITY.toString(),ADDRESS.toString());
        var body=employeeBody(nextPhone(),Set.of(owner.created().text("branchId"),branch),Set.of("catalog.read"));
        var created=call("POST","/api/v1/partner/employees",body,owner.access());assertEquals(201,created.status());
        assertEquals(2,created.json().path("branchIds").size());
        var context=call("GET","/api/v1/partner/context",null,owner.access());
        assertTrue(context.json().toString().contains(branch));
        assertTrue(context.json().toString().contains(owner.created().text("branchId")));
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
    @Test void employeeRequestsRequireNameBranchesAndExplicitPermissionSelection() throws Exception {
        var owner=partner();String phone=nextPhone();
        for(String field:List.of("name","phone","pin","branchIds","permissions")){
            var body=employeeBody(phone,Set.of(owner.created().text("branchId")),Set.of());body.remove(field);
            assertEquals(400,call("POST","/api/v1/partner/employees",body,owner.access()).status());
        }
        var body=employeeBody(phone,Set.of(),Set.of());assertEquals(400,call("POST","/api/v1/partner/employees",body,owner.access()).status());
        body=employeeBody(phone,Set.of(owner.created().text("branchId")),Set.of());body.put("roleKey","SUPER_ADMIN");
        assertEquals(400,call("POST","/api/v1/partner/employees",body,owner.access()).status());
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM users WHERE phone_e164=?",Integer.class,phone));
    }
    @Test void failedEmployeeBranchWriteRollsBackUserAffiliationAndPermissions() throws Exception {
        var owner=partner();String phone=nextPhone();
        jdbc.execute("CREATE FUNCTION reject_employee_branch() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'Injected test storage failure'; END $$");
        jdbc.execute("CREATE TRIGGER reject_employee_branch BEFORE INSERT ON partner_employee_branches FOR EACH ROW EXECUTE FUNCTION reject_employee_branch()");
        try{assertEquals(503,employee(owner,phone,Set.of("sale.confirm")).status());}finally{jdbc.execute("DROP TRIGGER reject_employee_branch ON partner_employee_branches");jdbc.execute("DROP FUNCTION reject_employee_branch()");}
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM users WHERE phone_e164=?",Integer.class,phone));
    }

    // ---- Partner management after registration (approved 2026-11-17) ----
    Map<String,Object> establishmentBody(String name){
        var body=new LinkedHashMap<String,Object>();body.put("name",name);body.put("categoryId",CATEGORY);body.put("contactPhone",null);
        body.put("branchName",name+" - فرع");body.put("cityId",CITY);body.put("addressId",ADDRESS);return body;
    }
    Map<String,Object> branchBody(String name,Object city,Object address){
        var body=new LinkedHashMap<String,Object>();body.put("name",name);body.put("cityId",city);body.put("addressId",address);body.put("addressDetails","شارع 1");return body;
    }
    String partnerPath(Partner p){return "/api/v1/admin/partners/"+p.created().text("partnerId");}

    @Test void administrationListsAndReadsPartnersOnlyWithCreateOrBranchGrant() throws Exception {
        String root=superAccess();var owner=partner();var admin=login(account(AccountRole.ADMIN));
        assertEquals(403,call("GET","/api/v1/admin/partners",null,admin.access()).status());
        assertEquals(403,call("GET",partnerPath(owner),null,owner.access()).status());
        var list=call("GET","/api/v1/admin/partners",null,root);
        assertEquals(200,list.status());assertTrue(list.json().toString().contains(owner.created().text("partnerId")));
        assertFalse(list.json().toString().toLowerCase(Locale.ROOT).contains("pin"));
        assertEquals(204,roleGrants(root,"ADMIN",Set.of("partner.branch.manage")).status());
        var detail=call("GET",partnerPath(owner),null,admin.access());
        assertEquals(200,detail.status());assertEquals("ACTIVE",detail.text("status"));assertEquals("أحمد",detail.text("ownerName"));
        var establishment=detail.json().path("establishments").get(0);
        assertEquals("مطاعم اختبار",establishment.path("categoryName").asString());
        assertEquals("حي اختبار",establishment.path("branches").get(0).path("addressName").asString());
        assertEquals(404,call("GET","/api/v1/admin/partners/"+UUID.randomUUID(),null,root).status());
    }
    @Test void minimumDiscountIsAdministrationCreateOnlyAndValidated() throws Exception {
        String root=superAccess();var owner=partner();var admin=login(account(AccountRole.ADMIN));String path=partnerPath(owner)+"/discount";
        assertEquals(403,call("PUT",path,Map.of("discountPercent",12),owner.access()).status());
        assertEquals(204,roleGrants(root,"ADMIN",Set.of("partner.branch.manage")).status());
        assertEquals(403,call("PUT",path,Map.of("discountPercent",12),admin.access()).status());
        assertEquals(204,roleGrants(root,"ADMIN",Set.of("partner.create")).status());
        assertEquals(204,call("PUT",path,Map.of("discountPercent",12.5),admin.access()).status());
        for(String bad:List.of("-1","101","1.234"))assertEquals(400,call("PUT",path,"{\"discountPercent\":"+bad+"}",root).status());
        assertEquals(0,new java.math.BigDecimal("12.50").compareTo(new java.math.BigDecimal(
            call("GET","/api/v1/partner/context",null,owner.access()).json().path("minimumDiscountPercent").asString())));
    }
    @Test void establishmentsAreAddedAndEditedByAdministrationOrOwnerWithoutTouchingLogin() throws Exception {
        String root=superAccess();var owner=partner();var other=partner();
        var byAdmin=call("POST",partnerPath(owner)+"/establishments",establishmentBody("صيدلية الإدارة"),root);
        assertEquals(201,byAdmin.status());assertEquals("ACTIVE",byAdmin.text("status"));
        var own=call("POST","/api/v1/partner/establishments",establishmentBody("مقهى الشريك"),owner.access());
        assertEquals(201,own.status());
        var context=call("GET","/api/v1/partner/context",null,owner.access()).json().toString();
        assertTrue(context.contains(byAdmin.text("establishmentId")));assertTrue(context.contains(own.text("branchId")));
        String phone=jdbc.queryForObject("SELECT phone_e164 FROM users WHERE id=?",String.class,owner.created().text("accountId"));
        var edit=new LinkedHashMap<String,Object>();edit.put("name","مقهى جديد");edit.put("categoryId",CATEGORY);edit.put("contactPhone",phone);
        assertEquals(204,call("PUT","/api/v1/partner/establishments/"+own.text("establishmentId"),edit,owner.access()).status());
        assertEquals("مقهى جديد",jdbc.queryForObject("SELECT name FROM partner_establishments WHERE id=?",String.class,own.text("establishmentId")));
        edit.put("contactPhone",null);
        assertEquals(204,call("PUT",partnerPath(owner)+"/establishments/"+own.text("establishmentId"),edit,root).status());
        assertNull(jdbc.queryForObject("SELECT contact_phone FROM partner_establishments WHERE id=?",String.class,own.text("establishmentId")));
        assertEquals(phone,jdbc.queryForObject("SELECT phone_e164 FROM users WHERE id=?",String.class,owner.created().text("accountId")));
        assertEquals(404,call("PUT","/api/v1/partner/establishments/"+own.text("establishmentId"),edit,other.access()).status());
        assertEquals(404,call("PUT",partnerPath(other)+"/establishments/"+own.text("establishmentId"),edit,root).status());
        edit.put("categoryId",UUID.randomUUID());
        assertEquals(400,call("PUT","/api/v1/partner/establishments/"+own.text("establishmentId"),edit,owner.access()).status());
    }
    @Test void establishmentAddedWhilePendingIsActivatedWithThePartner() throws Exception {
        String root=superAccess(),phone=nextPhone();var created=create(root,phone);
        var extra=call("POST","/api/v1/admin/partners/"+created.text("partnerId")+"/establishments",establishmentBody("منشأة ثانية"),root);
        assertEquals(201,extra.status());assertEquals("PENDING",extra.text("status"));
        activate(root,created);
        var context=call("GET","/api/v1/partner/context",null,login(phone,"048291").access());
        assertEquals(2,context.json().path("establishments").size());
    }
    @Test void branchManagementNeedsItsOwnAdministrationGrantAndStaysWithinThePartner() throws Exception {
        String root=superAccess();var owner=partner();var other=partner();var admin=login(account(AccountRole.ADMIN));
        String establishment=owner.created().text("establishmentId");
        String addPath=partnerPath(owner)+"/establishments/"+establishment+"/branches";
        assertEquals(204,roleGrants(root,"ADMIN",Set.of("partner.create")).status());
        assertEquals(403,call("POST",addPath,branchBody("فرع الإدارة",CITY,ADDRESS),admin.access()).status());
        assertEquals(204,roleGrants(root,"ADMIN",Set.of("partner.branch.manage")).status());
        var byAdmin=call("POST",addPath,branchBody("فرع الإدارة",CITY,ADDRESS),admin.access());
        assertEquals(201,byAdmin.status());
        var own=call("POST","/api/v1/partner/establishments/"+establishment+"/branches",branchBody("فرع الشريك",CITY,ADDRESS),owner.access());
        assertEquals(201,own.status());
        assertEquals(400,call("PUT","/api/v1/partner/branches/"+own.text("branchId"),branchBody("خطأ",OTHER_CITY,ADDRESS),owner.access()).status());
        assertEquals(204,call("PUT","/api/v1/partner/branches/"+own.text("branchId"),branchBody("فرع معدل",CITY,ADDRESS),owner.access()).status());
        assertEquals("فرع معدل",jdbc.queryForObject("SELECT name FROM partner_branches WHERE id=?",String.class,own.text("branchId")));
        assertEquals(404,call("PUT","/api/v1/partner/branches/"+own.text("branchId"),branchBody("x",CITY,ADDRESS),other.access()).status());
        assertEquals(404,call("POST","/api/v1/partner/establishments/"+establishment+"/branches",branchBody("x",CITY,ADDRESS),other.access()).status());
        assertEquals(404,call("PUT",partnerPath(other)+"/branches/"+own.text("branchId")+"/status",Map.of("status","SUSPENDED"),root).status());
        assertEquals(400,call("PUT","/api/v1/partner/branches/"+own.text("branchId")+"/status",Map.of("status","DELETED"),owner.access()).status());
    }
    @Test void suspendingAnyBranchIsAllowedAndBlocksEmployeesOnlyAssignedThere() throws Exception {
        var owner=partner();String phone=nextPhone();assertEquals(201,employee(owner,phone,Set.of()).status());
        var worker=login(phone,"012345");assertEquals(200,worker.status());
        String status="/api/v1/partner/branches/"+owner.created().text("branchId")+"/status";
        assertEquals(204,call("PUT",status,Map.of("status","SUSPENDED"),owner.access()).status());
        assertEquals(204,call("PUT",status,Map.of("status","SUSPENDED"),owner.access()).status());
        assertEquals(401,session(worker.access()).status());assertEquals(401,login(phone,"012345").status());
        assertEquals(200,session(owner.access()).status());
        assertEquals(204,call("PUT",status,Map.of("status","ACTIVE"),owner.access()).status());
        assertEquals(200,login(phone,"012345").status());
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
    @Test void ownerReassignsEmployeeBranchesAcrossOwnEstablishmentsOnly() throws Exception {
        var owner=partner();var other=partner();String phone=nextPhone();String user=employee(owner,phone,Set.of()).text("userId");
        var second=call("POST","/api/v1/partner/establishments",establishmentBody("منشأة أخرى"),owner.access());
        String base="/api/v1/partner/employees/"+user+"/branches";
        assertEquals(204,call("PUT",base,Map.of("branchIds",Set.of(second.text("branchId"))),owner.access()).status());
        assertEquals(List.of(second.text("branchId")),jdbc.queryForList("SELECT branch_id FROM partner_employee_branches WHERE user_id=?",String.class,user));
        assertEquals(400,call("PUT",base,Map.of("branchIds",Set.of(other.created().text("branchId"))),owner.access()).status());
        assertEquals(400,call("PUT",base,Map.of("branchIds",Set.of()),owner.access()).status());
        assertEquals(List.of(second.text("branchId")),jdbc.queryForList("SELECT branch_id FROM partner_employee_branches WHERE user_id=?",String.class,user));
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
}
