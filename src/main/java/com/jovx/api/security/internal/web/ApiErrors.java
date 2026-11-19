package com.jovx.api.security.internal.web;

import com.jovx.api.security.internal.sessions.AuthFailure;
import com.jovx.api.security.internal.accounts.InternationalPhone;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.*;
import org.springframework.http.*;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.AuthenticationServiceException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.converter.HttpMessageNotReadableException;

@RestControllerAdvice
@Profile("local-postgres")
public class ApiErrors {
    @ExceptionHandler(AuthFailure.class) ResponseEntity<Problem> auth(AuthFailure ex){return error(ex.status(),ex.code(),ex.retrySeconds());}
    @ExceptionHandler(InternationalPhone.InvalidPhoneNumber.class)
    ResponseEntity<Problem> phone(){return error(400,"INVALID_PHONE_NUMBER",0);}
    @ExceptionHandler({MethodArgumentNotValidException.class,HttpMessageNotReadableException.class,IllegalArgumentException.class})
    ResponseEntity<Problem> input(Exception ex){return error(400,"INVALID_REQUEST",0);}
    @ExceptionHandler(DuplicateKeyException.class) ResponseEntity<Problem> duplicate(){return error(409,"CONFLICT",0);}
    @ExceptionHandler({DataAccessException.class,AuthenticationServiceException.class}) ResponseEntity<Problem> database(){return error(503,"SERVICE_UNAVAILABLE",0);}
    @ExceptionHandler(AccessDeniedException.class) ResponseEntity<Problem> denied(){return error(403,"FORBIDDEN",0);}
    /** The only place a business failure kind becomes an HTTP status. */
    @ExceptionHandler(com.jovx.api.common.DomainException.class)
    ResponseEntity<Problem> business(com.jovx.api.common.DomainException ex){return error(status(ex.kind()),ex.code(),0);}
    static int status(com.jovx.api.common.DomainException.Kind kind){
        return switch(kind){
            case INVALID -> 400; case FORBIDDEN -> 403; case NOT_FOUND -> 404; case CONFLICT -> 409;
            case UPSTREAM_FAILED -> 502; case UNAVAILABLE -> 503;
        };
    }
    /** Controllers may still reject malformed transport input (e.g. an unknown path value). */
    @ExceptionHandler(ResponseStatusException.class) ResponseEntity<Problem> transport(ResponseStatusException ex){
        return error(ex.getStatusCode().value(),ex.getReason()==null?"REQUEST_FAILED":ex.getReason(),0);
    }
    private static ResponseEntity<Problem> error(int status,String code,long retry){
        var builder=ResponseEntity.status(status).cacheControl(CacheControl.noStore());
        if(retry>0)builder.header("Retry-After",Long.toString(retry));
        return builder.body(new Problem(code));
    }
    record Problem(String code){}
}
