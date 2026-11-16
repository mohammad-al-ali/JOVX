package com.jovx.api.security.internal.accounts;

import com.jovx.api.security.api.EmployeeAccounts;
import com.jovx.api.security.internal.roles.RoleStore;
import com.jovx.api.security.internal.sessions.AuthFailure;
import java.util.*;
import javax.sql.DataSource;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Component @Profile("local-postgres")
final class EmployeeProvisioning implements EmployeeAccounts {
    private final JdbcAccountStore accounts;
    private final JdbcTemplate jdbc;
    private final DataSource source;
    private final RoleStore roles;
    EmployeeProvisioning(JdbcAccountStore accounts,DataSource source){this.accounts=accounts;this.source=source;jdbc=new JdbcTemplate(source);roles=new RoleStore(source);}
    private void transaction(){if(!TransactionSynchronizationManager.hasResource(source))throw new IllegalStateException("Employee command requires domain transaction");}
    public UUID create(String name,String phone,String pin){transaction();var id=UUID.randomUUID();accounts.create(id,name,phone,AccountRole.PARTNER_EMPLOYEE,pin);return id;}
    public List<Map<String,Object>> catalog(UUID owner){
        var effective=Set.copyOf(roles.effectivePermissions(owner));
        return jdbc.queryForList("""
            SELECT p.id,p.\"key\",p.name_ar,p.description_ar,p.level FROM permissions p
            JOIN role_permissions rp ON rp.permission_id=p.id JOIN roles r ON r.id=rp.role_id
            WHERE r.\"key\"='PARTNER_EMPLOYEE' AND p.level='PARTNER' AND p.delegable_to_employee=TRUE ORDER BY p.id
            """).stream().filter(p->effective.contains(p.get("key"))).toList();
    }
    public void replacePermissions(UUID owner,UUID employee,Set<String> requested){
        transaction();
        if(requested==null || requested.size()>100) throw new AuthFailure(400,"INVALID_PERMISSIONS");
        // Serialize permission replacement against changes to the employee role's ceiling.
        jdbc.queryForList("SELECT id FROM roles WHERE \"key\"='PARTNER_EMPLOYEE' FOR UPDATE");
        if(!Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM users u JOIN roles r ON r.id=u.role_id WHERE u.id=? AND r.\"key\"='PARTNER_EMPLOYEE')",Boolean.class,employee.toString())))
            throw new AuthFailure(409,"TARGET_MUST_BE_PARTNER_EMPLOYEE");
        var allowed=new HashMap<String,Integer>();
        for(var p:catalog(owner))allowed.put((String)p.get("key"),((Number)p.get("id")).intValue());
        if(!allowed.keySet().containsAll(requested)) throw new AuthFailure(403,"PERMISSION_NOT_DELEGABLE");
        jdbc.update("DELETE FROM user_permissions WHERE user_id=?",employee.toString());
        for(String key:requested)jdbc.update("INSERT INTO user_permissions(user_id,permission_id,granted_by) VALUES (?,?,?)",employee.toString(),allowed.get(key),owner.toString());
    }
}
