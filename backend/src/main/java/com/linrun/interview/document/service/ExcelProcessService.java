package com.linrun.interview.document.service;

/**
 * DATA_QUERY Excel/CSV 动态表生命周期。
 */
public interface ExcelProcessService {

 /**
 * 生成多租户 DATA_QUERY 物理表名。
 */
 String generatePhysicalTableName(Long userId, String originalFilename);

 /**
 * 删除物理表并清理 {@code table_meta} 记录。
 */
 void dropTable(String tableName);
}
