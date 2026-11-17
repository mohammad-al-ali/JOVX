-- Approved 2026-11-17: branch create/edit/suspend/reactivate by administration needs its own grant.
-- SUPER_ADMIN is always allowed; partner.create alone does not imply branch management.
-- Not delegable to employees and not granted to any role automatically.
INSERT INTO permissions(id,"key",name_ar,description_ar,level,delegable_to_employee) VALUES
 (3,'partner.branch.manage','إدارة فروع الشركاء',
  'إنشاء فروع منشآت الشركاء وتعديلها وإيقافها وإعادة تفعيلها.','ADMIN',FALSE);
