package com.jovx.api.security.internal.config;

import com.jovx.api.security.internal.sessions.AuthFailure;
import com.jovx.api.security.internal.sessions.AttemptLimiter;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.*;
import org.springframework.dao.DataAccessException;
import org.springframework.web.filter.OncePerRequestFilter;

/** Bounds credential payloads including chunked requests, and never trusts client-supplied forwarding headers. */
final class AuthRequestFilter extends OncePerRequestFilter {
    private final AttemptLimiter limiter;
    AuthRequestFilter(AttemptLimiter limiter){this.limiter=limiter;}
    @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain) throws ServletException,IOException {
        response.setHeader("Cache-Control","no-store");
        String path=request.getServletPath();
        if(!"POST".equals(request.getMethod()) || !java.util.Set.of("/api/v1/auth/login","/api/v1/auth/refresh","/api/v1/auth/logout").contains(path)){
            forward(request,response,chain); return;
        }
        try {limiter.consume(path.endsWith("login")?"login":"token",request.getRemoteAddr());}
        catch(AuthFailure ex){error(response,ex.status(),ex.code(),ex.retrySeconds());return;}
        catch(DataAccessException ex){error(response,503,"AUTH_UNAVAILABLE",0);return;}
        byte[] bytes=request.getInputStream().readNBytes(4097);
        if(bytes.length>4096){error(response,413,"REQUEST_TOO_LARGE",0);return;}
        forward(new HttpServletRequestWrapper(request){
            @Override public ServletInputStream getInputStream(){
                var input=new ByteArrayInputStream(bytes);
                return new ServletInputStream(){
                    public int read(){return input.read();}
                    public boolean isFinished(){return input.available()==0;}
                    public boolean isReady(){return true;}
                    public void setReadListener(ReadListener listener){throw new UnsupportedOperationException("Synchronous JSON only");}
                };
            }
            @Override public BufferedReader getReader(){return new BufferedReader(new InputStreamReader(getInputStream(),java.nio.charset.StandardCharsets.UTF_8));}
        },response,chain);
    }
    private static void forward(HttpServletRequest request,HttpServletResponse response,FilterChain chain) throws ServletException,IOException {
        try {chain.doFilter(request,response);}
        catch(org.springframework.security.authentication.AuthenticationServiceException ex){
            // Spring's bearer failure handler rethrows infrastructure failures by default.
            // Preserve the client's session and make the retryable outage explicit.
            if(response.isCommitted())throw ex;
            error(response,503,"AUTH_UNAVAILABLE",0);
        }
    }
    static void error(HttpServletResponse response,int status,String code,long retry) throws IOException {
        response.setStatus(status); response.setContentType("application/json"); response.setHeader("Cache-Control","no-store");
        if(retry>0)response.setHeader("Retry-After",Long.toString(retry));
        response.getWriter().write("{\"code\":\""+code+"\"}");
    }
}
