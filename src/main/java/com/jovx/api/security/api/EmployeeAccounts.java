package com.jovx.api.security.api;

import java.util.*;

/** Caller owns the transaction and must authorize the owning Partner and employee affiliation. */
public interface EmployeeAccounts {
    UUID create(String name,String phone,String pin);
    void replacePermissions(UUID owningPartnerUser,UUID employee,Set<String> permissions);
    List<Map<String,Object>> catalog(UUID owningPartnerUser);
    /** Effective delegated grants currently usable by the employee. */
    Set<String> effectivePermissions(UUID employee);
    /** Suspension revokes every session; reactivation never revives a revoked session. */
    void setActive(UUID employee,boolean active);
    /** Replaces the PIN, clears lockout and revokes every session of that employee. */
    void changePin(UUID employee,String pin);
}
