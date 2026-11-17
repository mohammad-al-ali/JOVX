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
}
