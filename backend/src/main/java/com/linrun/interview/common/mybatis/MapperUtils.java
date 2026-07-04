package com.linrun.interview.common.mybatis;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.linrun.interview.common.exception.BusinessException;
import com.linrun.interview.common.exception.ErrorCode;

import java.io.Serializable;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Optional;

/**
 * MyBatis-Plus 通用 CRUD 操作工具类。
 */
public final class MapperUtils {

  private MapperUtils() {
  }

  public static <T> T requireById(BaseMapper<T> mapper, Serializable id, ErrorCode code, String message) {
    return Optional.ofNullable(mapper.selectById(id))
        .orElseThrow(() -> new BusinessException(code, message));
  }

  public static <T> Optional<T> selectOneOptional(BaseMapper<T> mapper, Wrapper<T> wrapper) {
    return Optional.ofNullable(mapper.selectOne(wrapper));
  }

  public static <T> List<T> selectList(BaseMapper<T> mapper, Wrapper<T> wrapper) {
    return mapper.selectList(wrapper);
  }

  public static <T> boolean exists(BaseMapper<T> mapper, Wrapper<T> wrapper) {
    return mapper.selectCount(wrapper) > 0;
  }

  public static <T> T save(BaseMapper<T> mapper, T entity) {
    Object id = readId(entity);
    if (isBlankId(id)) {
      mapper.insert(entity);
    } else {
      mapper.updateById(entity);
    }
    return entity;
  }

  private static Object readId(Object entity) {
    try {
      Method getId = entity.getClass().getMethod("getId");
      return getId.invoke(entity);
    } catch (ReflectiveOperationException e) {
      Method getVersionId;
      try {
        getVersionId = entity.getClass().getMethod("getVersionId");
        return getVersionId.invoke(entity);
      } catch (ReflectiveOperationException ex) {
        throw new IllegalStateException("Entity has no getId/getVersionId: " + entity.getClass().getName(), ex);
      }
    }
  }

  private static boolean isBlankId(Object id) {
    if (id == null) {
      return true;
    }
    if (id instanceof Long l) {
      return l == 0L;
    }
    if (id instanceof String s) {
      return s.isBlank();
    }
    return false;
  }
}
