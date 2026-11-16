package com.jovx.api.security.internal.roles;

import java.util.*;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;

/** Role grants for ordinary accounts; employee roles are ceilings, never automatic grants. */
public final class RoleStore {
    private final JdbcTemplate jdbc;
    public RoleStore(DataSource source) { jdbc = new JdbcTemplate(source); }
    public List<String> effectivePermissions(UUID userId) {
        return jdbc.query("""
            SELECT DISTINCT p.\"key\" FROM users u JOIN roles r ON r.id=u.role_id
            JOIN role_permissions rp ON rp.role_id=r.id JOIN permissions p ON p.id=rp.permission_id
            WHERE u.id=? AND (r.\"key\" NOT IN ('PARTNER_EMPLOYEE','ADMIN_EMPLOYEE') OR
              (p.delegable_to_employee=TRUE AND EXISTS
                (SELECT 1 FROM user_permissions up JOIN users grantor ON grantor.id=up.granted_by
                 JOIN role_permissions gp ON gp.role_id=grantor.role_id AND gp.permission_id=up.permission_id
                 WHERE up.user_id=u.id AND up.permission_id=p.id AND grantor.status='ACTIVE')))
            ORDER BY p.\"key\"
            """, (row,i)->row.getString(1), userId.toString());
    }
    public List<Map<String,Object>> roles() {
        return jdbc.queryForList("SELECT id,\"key\",name_ar,level FROM roles ORDER BY id");
    }
    public List<Map<String,Object>> permissions() {
        return jdbc.queryForList("SELECT id,\"key\",name_ar,description_ar,level,delegable_to_employee FROM permissions ORDER BY id");
    }
}
