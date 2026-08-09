package com.linrun.interview.infra.persistence;

import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.Version;

/**
 * 知识库等实体的审计与并发控制基类（对齐业界实践 BaseEntity）。
 */
public abstract class BaseEntity {

  @Version
  protected Integer lockVersion;

  /** 0=未删除，1=已删除（MyBatis-Plus 逻辑删除） */
  @TableLogic
  protected Integer deleted;

  public Integer getLockVersion() {
    return lockVersion;
  }

  public void setLockVersion(Integer lockVersion) {
    this.lockVersion = lockVersion;
  }

  public Integer getDeleted() {
    return deleted;
  }

  public void setDeleted(Integer deleted) {
    this.deleted = deleted;
  }
}
