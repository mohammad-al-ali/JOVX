package com.jovx.api.security.internal.accounts;

import com.jovx.api.security.api.AccountDirectory;
import java.util.*;
import javax.sql.DataSource;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component @Profile("local-postgres")
final class JdbcAccountDirectory implements AccountDirectory {
    private final JdbcTemplate jdbc;
    JdbcAccountDirectory(DataSource source){jdbc=new JdbcTemplate(source);}
    public Map<UUID,AccountSummary> summaries(Collection<UUID> ids){
        var result=new HashMap<UUID,AccountSummary>();
        if(ids==null || ids.isEmpty()) return result;
        var keys=ids.stream().map(UUID::toString).distinct().toList();
        String marks=String.join(",",Collections.nCopies(keys.size(),"?"));
        jdbc.query("SELECT id,name,phone_e164,status FROM users WHERE id IN ("+marks+")",
            (org.springframework.jdbc.core.RowCallbackHandler) r->{
                var id=UUID.fromString(r.getString(1));
                result.put(id,new AccountSummary(id,r.getString(2),r.getString(3),r.getString(4)));
            },keys.toArray());
        return result;
    }
    public List<UUID> search(String role,String text,int limit){
        if(text==null || text.isBlank()) return List.of();
        String name=text.strip().toLowerCase(Locale.ROOT).replace("\\","\\\\").replace("%","\\%").replace("_","\\_");
        String digits=text.replaceAll("[^0-9]","");
        // "00963..." is the international form of "+963..."; a national "09..." is stored as "+9639...".
        if(digits.startsWith("00")) digits=digits.substring(2);
        else if(digits.startsWith("0")) digits=digits.substring(1);
        String phone=digits.length()>=3?"%"+digits+"%":null;
        return jdbc.query("""
            SELECT u.id FROM users u JOIN roles r ON r.id=u.role_id
            WHERE r.\"key\"=? AND (lower(u.name) LIKE ? ESCAPE '\\' OR (CAST(? AS varchar) IS NOT NULL AND u.phone_e164 LIKE ?))
            ORDER BY u.created_at DESC LIMIT ?
            """,(r,i)->UUID.fromString(r.getString(1)),role,"%"+name+"%",phone,phone,Math.max(1,Math.min(limit,100)));
    }
}
