package com.linrun.interview.config;

import com.baomidou.mybatisplus.core.handlers.MetaObjectHandler;
import org.apache.ibatis.reflection.MetaObject;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

@Component
public class MyMetaObjectHandler implements MetaObjectHandler {

  // 实体未标注 @TableField(fill=...)，strict* 变体会整体跳过填充；这里按属性存在性 + 空值判断
  // 直接填充，保证 createdAt/updatedAt 在无 DB 默认值的表也能写入。
  @Override
  public void insertFill(MetaObject metaObject) {
    LocalDateTime now = LocalDateTime.now();
    fillIfAbsent(metaObject, "createdAt", now);
    fillIfAbsent(metaObject, "updatedAt", now);
  }

  @Override
  public void updateFill(MetaObject metaObject) {
    if (metaObject.hasSetter("updatedAt")) {
      setFieldValByName("updatedAt", LocalDateTime.now(), metaObject);
    }
  }

  private void fillIfAbsent(MetaObject metaObject, String field, LocalDateTime value) {
    if (!metaObject.hasSetter(field) || !metaObject.hasGetter(field)) {
      return;
    }
    if (metaObject.getValue(field) == null) {
      setFieldValByName(field, value, metaObject);
    }
  }
}
