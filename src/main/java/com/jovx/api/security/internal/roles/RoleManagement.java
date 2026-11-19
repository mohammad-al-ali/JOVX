package com.jovx.api.security.internal.roles;

import com.jovx.api.security.api.AccountRoleConstraint;
import com.jovx.api.security.internal.sessions.*;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.*;
import javax.sql.DataSource;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service @Profile("local-postgres")
public final class RoleManagement {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final AuthSessionService sessions;
    private final List<AccountRoleConstraint> constraints;
    private final Clock clock;
    private final RoleStore store;
    public RoleManagement(DataSource source,AuthSessionService sessions,List<AccountRoleConstraint> constraints,Clock clock) {
        jdbc=new JdbcTemplate(source);tx=new TransactionTemplate(new JdbcTransactionManager(source));
        this.sessions=sessions;this.constraints=List.copyOf(constraints);this.clock=clock;store=new RoleStore(source);
    }
    public Map<String,Object> catalog(UUID actor,UUID sid) {
        return tx.execute(s->{sessions.requireRole(actor,sid,"SUPER_ADMIN");
            return Map.of("roles",store.roles(),"permissions",store.permissions(),
                "rolePermissions",jdbc.queryForList("SELECT role_id,permission_id FROM role_permissions ORDER BY role_id,permission_id"));});
    }
    public void replace(UUID actor,UUID sid,String roleKey,Set<String> keys) {
        tx.executeWithoutResult(s->{
            sessions.requireRole(actor,sid,"SUPER_ADMIN");
            var role=role(roleKey);
            if("SUPER_ADMIN".equals(roleKey)) throw new AuthFailure(409,"SUPER_ADMIN_HAS_FULL_ACCESS");
            var permissions=resolve(keys,(String)role.get("level"),roleKey.endsWith("_EMPLOYEE"));
            jdbc.update("DELETE FROM role_permissions WHERE role_id=?",role.get("id"));
            for(var p:permissions) jdbc.update("INSERT INTO role_permissions(role_id,permission_id,level) VALUES (?,?,?)",role.get("id"),p,role.get("level"));
            // Revoking an employee ceiling must not leave dormant grants that reappear when restored.
            jdbc.update("""
                DELETE FROM user_permissions up USING users u
                WHERE u.id=up.user_id AND u.role_id=? AND NOT EXISTS (SELECT 1 FROM role_permissions rp
                  WHERE rp.role_id=u.role_id AND rp.permission_id=up.permission_id)
                """,role.get("id"));
            jdbc.update("""
                DELETE FROM user_permissions up USING users grantor
                WHERE grantor.id=up.granted_by AND grantor.role_id=? AND NOT EXISTS (SELECT 1 FROM role_permissions rp
                  WHERE rp.role_id=grantor.role_id AND rp.permission_id=up.permission_id)
                """,role.get("id"));
            audit(actor,actor,"ROLE_PERMISSIONS_CHANGED",roleKey);
        });
    }
    public void assign(UUID actor,UUID sid,UUID target,String roleKey) {
        tx.executeWithoutResult(s->{
            sessions.requireRole(actor,sid,"SUPER_ADMIN");
            var role=role(roleKey);
            var users=jdbc.queryForList("SELECT u.role_id,r.\"key\" FROM users u JOIN roles r ON r.id=u.role_id WHERE u.id=? FOR UPDATE",target.toString());
            if(users.isEmpty()) throw new AuthFailure(404,"USER_NOT_FOUND");
            var current=users.getFirst();
            if(roleKey.equals(current.get("key"))) return;
            // Affiliation creation/transfer is a domain command, not a naked role update.
            if(Set.of("PARTNER","PARTNER_EMPLOYEE","ADMIN_EMPLOYEE","MEMBER").contains(roleKey)
                || constraints.stream().anyMatch(p->!p.permits(target,roleKey)))
                throw new AuthFailure(409,"ROLE_REQUIRES_DOMAIN_PROVISIONING");
            if("SUPER_ADMIN".equals(current.get("key"))) {
                jdbc.queryForList("SELECT id FROM roles WHERE \"key\"='SUPER_ADMIN' FOR UPDATE");
                if(jdbc.queryForObject("SELECT COUNT(*) FROM users WHERE role_id=? AND status='ACTIVE'",Integer.class,current.get("role_id"))<=1)
                    throw new AuthFailure(409,"LAST_SUPER_ADMIN");
            }
            jdbc.update("UPDATE users SET role_id=? WHERE id=?",role.get("id"),target.toString());
            jdbc.update("DELETE FROM user_permissions WHERE user_id=?",target.toString());
            jdbc.update("UPDATE identity_sessions SET revoked_at=? WHERE account_id=? AND revoked_at IS NULL",Timestamp.from(clock.instant()),target.toString());
            audit(actor,target,"USER_ROLE_ASSIGNED",roleKey);
        });
    }
    private Map<String,Object> role(String key) {
        var rows=jdbc.queryForList("SELECT id,\"key\",level FROM roles WHERE \"key\"=? FOR UPDATE",key);
        if(rows.isEmpty()) throw new AuthFailure(400,"UNKNOWN_ROLE");
        return rows.getFirst();
    }
    private List<Integer> resolve(Set<String> keys,String level,boolean employee) {
        if(keys==null || keys.size()>100) throw new AuthFailure(400,"INVALID_PERMISSIONS");
        var result=new ArrayList<Integer>();
        for(String key:keys){
            var rows=jdbc.queryForList("SELECT id,level,delegable_to_employee FROM permissions WHERE \"key\"=?",key);
            if(rows.isEmpty() || !level.equals(rows.getFirst().get("level"))) throw new AuthFailure(400,"PERMISSION_LEVEL_MISMATCH");
            if(employee && !Boolean.TRUE.equals(rows.getFirst().get("delegable_to_employee"))) throw new AuthFailure(400,"PERMISSION_NOT_DELEGABLE");
            result.add(((Number)rows.getFirst().get("id")).intValue());
        }
        return result;
    }
    private void audit(UUID actor,UUID target,String action,String roleKey) {
        jdbc.update("INSERT INTO role_events(id,actor_id,target_user_id,action,role_key,created_at) VALUES (?,?,?,?,?,?)",
            UUID.randomUUID().toString(),actor.toString(),target.toString(),action,roleKey,Timestamp.from(clock.instant()));
    }
}
