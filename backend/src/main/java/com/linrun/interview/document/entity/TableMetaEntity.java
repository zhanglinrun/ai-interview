package com.linrun.interview.document.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.linrun.interview.infra.persistence.BaseEntity;

import java.time.LocalDateTime;

/**
 * DATA_QUERY 动态表元数据。
 *
 * <p>多租户适配：增加 {@code userId}，物理表名带用户前缀，避免不同用户同名 Excel 互相覆盖。
 */
@TableName("table_meta")
public class TableMetaEntity extends BaseEntity {

 @TableId(value = "id", type = IdType.AUTO)
 private Long id;

 @TableField("user_id")
 private Long userId;

 @TableField("table_name")
 private String tableName;

 @TableField("description")
 private String description;

 @TableField("create_sql")
 private String createSql;

 @TableField("columns_info")
 private String columnsInfo;

 @TableField("version_id")
 private Long versionId;

 @TableField("created_at")
 private LocalDateTime createdAt;

 @TableField("updated_at")
 private LocalDateTime updatedAt;

 public Long getId() {
 return id;
 }

 public void setId(Long id) {
 this.id = id;
 }

 public Long getUserId() {
 return userId;
 }

 public void setUserId(Long userId) {
 this.userId = userId;
 }

 public String getTableName() {
 return tableName;
 }

 public void setTableName(String tableName) {
 this.tableName = tableName;
 }

 public String getDescription() {
 return description;
 }

 public void setDescription(String description) {
 this.description = description;
 }

 public String getCreateSql() {
 return createSql;
 }

 public void setCreateSql(String createSql) {
 this.createSql = createSql;
 }

 public String getColumnsInfo() {
 return columnsInfo;
 }

 public void setColumnsInfo(String columnsInfo) {
 this.columnsInfo = columnsInfo;
 }

 public Long getVersionId() {
 return versionId;
 }

 public void setVersionId(Long versionId) {
 this.versionId = versionId;
 }

 public LocalDateTime getCreatedAt() {
 return createdAt;
 }

 public void setCreatedAt(LocalDateTime createdAt) {
 this.createdAt = createdAt;
 }

 public LocalDateTime getUpdatedAt() {
 return updatedAt;
 }

 public void setUpdatedAt(LocalDateTime updatedAt) {
 this.updatedAt = updatedAt;
 }
}
