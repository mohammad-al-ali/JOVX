-- Partners have stores, not branches (V8). Only the Arabic texts shown to partners change;
-- permission ids and keys stay the same, so grants and code are unaffected.
UPDATE permissions SET description_ar = 'مسح بطاقة العضو في المتاجر المكلف بها الموظف.'
 WHERE "key" = 'member.scan';
UPDATE permissions SET description_ar = 'تأكيد عملية البيع في المتاجر المكلف بها الموظف.'
 WHERE "key" = 'sale.confirm';
UPDATE permissions SET name_ar = 'عرض مبيعات المتجر',
                       description_ar = 'عرض سجل المبيعات للمتاجر المكلف بها الموظف.'
 WHERE "key" = 'sale.read.branch';
UPDATE permissions SET name_ar = 'عرض تقارير المتجر',
                       description_ar = 'عرض تقارير المتاجر المكلف بها الموظف.'
 WHERE "key" = 'report.read.branch';
