package com.jovx.api.security.api;

import java.util.*;

/** Caller owns the transaction and must authorize the owning Partner and employee affiliation. */
public interface EmployeeAccounts {
    UUID create(String name,String phone,String pin);
    void replacePermissions(UUID owningPartnerUser,UUID employee,Set<String> permissions);
    List<Map<String,Object>> catalog(UUID owningPartnerUser);
}
