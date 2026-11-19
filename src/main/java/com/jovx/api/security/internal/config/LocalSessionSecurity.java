package com.jovx.api.security.internal.config;

import com.jovx.api.security.api.LoginEligibility;
import com.jovx.api.security.internal.sessions.*;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import java.security.Signature;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.*;
import org.springframework.core.env.Environment;
import org.springframework.core.io.ResourceLoader;
import org.springframework.dao.DataAccessException;
import org.springframework.security.authentication.*;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.converter.RsaKeyConverters;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.*;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration(proxyBeanMethods=false)
@Profile("local-postgres")
@EnableMethodSecurity
@EnableConfigurationProperties({JwtSettings.class,AuthSettings.class})
public class LocalSessionSecurity {
    @Bean @ConditionalOnMissingBean(Clock.class) Clock identityClock(){return Clock.systemUTC();}
    @Bean AuthenticationManager authenticationManager(DaoAuthenticationProvider provider){return new ProviderManager(provider);}
    @Bean JwtEncoder jwtEncoder(JwtSettings settings,Environment env,ResourceLoader loader) throws Exception {
        RSAPublicKey publicKey; RSAPrivateKey privateKey;
        try(var in=settings.publicKey().getInputStream()){publicKey=RsaKeyConverters.x509().convert(in);}
        try(var in=loader.getResource(env.getRequiredProperty("jovx.security.jwt.private-key")).getInputStream()){
            privateKey=RsaKeyConverters.pkcs8().convert(in);
        }
        if(publicKey==null || privateKey==null || publicKey.getModulus().bitLength()<2048
            || !publicKey.getModulus().equals(privateKey.getModulus())) throw new IllegalArgumentException("Invalid signing key pair");
        var proof=Signature.getInstance("SHA256withRSA"); proof.initSign(privateKey); proof.update(new byte[]{1,2,3}); var signature=proof.sign();
        proof.initVerify(publicKey); proof.update(new byte[]{1,2,3});
        if(!proof.verify(signature)) throw new IllegalArgumentException("Mismatched signing keys");
        return new NimbusJwtEncoder(new ImmutableJWKSet<>(new JWKSet(new RSAKey.Builder(publicKey).privateKey(privateKey).build())));
    }
    @Bean JwtDecoder jwtDecoder(JwtSettings settings,Clock clock) throws Exception {
        RSAPublicKey key;
        try(var in=settings.publicKey().getInputStream()){key=RsaKeyConverters.x509().convert(in);}
        var decoder=NimbusJwtDecoder.withPublicKey(key).signatureAlgorithm(SignatureAlgorithm.RS256).build();
        var time=new JwtTimestampValidator(Duration.ZERO); time.setClock(clock);
        OAuth2TokenValidator<Jwt> contract = jwt -> {
            boolean valid;
            try {
                UUID.fromString(jwt.getSubject()); UUID.fromString(jwt.getClaimAsString("sid"));
                valid=jwt.getAudience().contains(settings.audience()) && jwt.getExpiresAt()!=null && jwt.getNotBefore()!=null
                    && jwt.getIssuedAt()!=null && !jwt.getIssuedAt().isAfter(clock.instant())
                    && jwt.getExpiresAt().isAfter(jwt.getIssuedAt()) && jwt.getExpiresAt().isAfter(clock.instant()) && jwt.getId()!=null
                    && "access".equals(jwt.getClaimAsString("token_use")) && jwt.getClaimAsString("role")!=null;
            } catch(RuntimeException ex){valid=false;}
            return valid ? OAuth2TokenValidatorResult.success() : OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token"));
        };
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(time,new JwtIssuerValidator(settings.issuer()),contract));
        return decoder;
    }
    @Bean AuthSessionService authSessions(DataSource source,AuthenticationManager manager,JwtEncoder signer,JwtSettings jwt,
        Clock clock,AuthSettings settings,List<LoginEligibility> policies,com.jovx.api.security.internal.accounts.SixDigitPinEncoder pins){
        return new AuthSessionService(source,manager,signer,jwt.issuer(),jwt.audience(),clock,settings,policies,pins);
    }
    @Bean AttemptLimiter attemptLimiter(DataSource source,Clock clock,AuthSettings settings){return new AttemptLimiter(source,clock,settings);}
    @Bean SecurityFilterChain localApiSecurity(HttpSecurity http,AuthSessionService sessions,AttemptLimiter limiter) throws Exception {
        return http.csrf(AbstractHttpConfigurer::disable).httpBasic(AbstractHttpConfigurer::disable)
            .formLogin(AbstractHttpConfigurer::disable).logout(AbstractHttpConfigurer::disable)
            .requestCache(AbstractHttpConfigurer::disable).sessionManagement(s->s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(a->a.dispatcherTypeMatchers(jakarta.servlet.DispatcherType.ERROR).permitAll()
                .requestMatchers("/actuator/health").permitAll()
                .requestMatchers(org.springframework.http.HttpMethod.POST,"/api/v1/auth/login","/api/v1/auth/refresh","/api/v1/auth/logout").permitAll()
                // Public update check: a required update may have to block before login.
                .requestMatchers(org.springframework.http.HttpMethod.GET,"/api/v1/app-updates").permitAll()
                // Guest browsing and first-PIN card claim (plan decision 44); the claim is rate limited.
                .requestMatchers(org.springframework.http.HttpMethod.GET,"/api/v1/public/partners","/api/v1/public/membership-plans").permitAll()
                .requestMatchers(org.springframework.http.HttpMethod.POST,"/api/v1/member/claim").permitAll()
                // Every other API route needs a valid session. Each controller still declares its own
                // role/permission with @PreAuthorize (enforced by ModularArchitectureTest), so adding an
                // endpoint never requires editing this security module.
                .requestMatchers("/api/v1/**").authenticated()
                .anyRequest().denyAll())
            .addFilterBefore(new AuthRequestFilter(limiter),UsernamePasswordAuthenticationFilter.class)
            .exceptionHandling(e->e.authenticationEntryPoint((q,r,x)->AuthRequestFilter.error(r,401,"UNAUTHENTICATED",0))
                .accessDeniedHandler((q,r,x)->AuthRequestFilter.error(r,403,"FORBIDDEN",0)))
            .oauth2ResourceServer(o->o.authenticationEntryPoint((q,r,x)->AuthRequestFilter.error(r,
                x instanceof AuthenticationServiceException ? 503:401,
                x instanceof AuthenticationServiceException ? "AUTH_UNAVAILABLE":"INVALID_TOKEN",0))
                .jwt(j->j.jwtAuthenticationConverter(jwt->{
                    try {return new JwtAuthenticationToken(jwt,sessions.authorities(jwt).stream().map(SimpleGrantedAuthority::new).toList());}
                    catch(AuthFailure ex){throw new OAuth2AuthenticationException(new OAuth2Error("invalid_token"));}
                    catch(DataAccessException ex){throw new AuthenticationServiceException("AUTH_UNAVAILABLE",ex);}
                }))).build();
    }
}
