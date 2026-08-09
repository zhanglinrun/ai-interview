package com.linrun.interview.infra.persistence;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.core.toolkit.support.SFunction;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * 常用 MyBatis-Plus 查询组合，替代 JPA Repository 命名查询。
 */
public final class EntityQueries {

  private EntityQueries() {
  }

  public static <T> Optional<T> byUserAndId(
      BaseMapper<T> mapper,
      Long userId,
      Long id,
      SFunction<T, Long> userIdGetter,
      SFunction<T, Long> idGetter) {
    if (userId == null || id == null) {
      return Optional.empty();
    }
    return MapperUtils.selectOneOptional(mapper, Wrappers.<T>lambdaQuery()
        .eq(userIdGetter, userId)
        .eq(idGetter, id));
  }

  public static <T> boolean existsByUserAndId(
      BaseMapper<T> mapper,
      Long userId,
      Long id,
      SFunction<T, Long> userIdGetter,
      SFunction<T, Long> idGetter) {
    return byUserAndId(mapper, userId, id, userIdGetter, idGetter).isPresent();
  }

  public static <T> List<T> listByUserId(
      BaseMapper<T> mapper,
      Long userId,
      SFunction<T, Long> userIdGetter) {
    return mapper.selectList(Wrappers.<T>lambdaQuery().eq(userIdGetter, userId));
  }

  public static <T> List<T> listByUserIdOrderByDesc(
      BaseMapper<T> mapper,
      Long userId,
      SFunction<T, Long> userIdGetter,
      SFunction<T, ?> orderDesc) {
    return mapper.selectList(Wrappers.<T>lambdaQuery()
        .eq(userIdGetter, userId)
        .orderByDesc(orderDesc));
  }

  public static <T> List<T> listByUserIdAndIdIn(
      BaseMapper<T> mapper,
      Long userId,
      Collection<Long> ids,
      SFunction<T, Long> userIdGetter,
      SFunction<T, Long> idGetter) {
    if (ids == null || ids.isEmpty()) {
      return List.of();
    }
    return mapper.selectList(Wrappers.<T>lambdaQuery()
        .eq(userIdGetter, userId)
        .in(idGetter, ids));
  }

  public static <T> Optional<T> selectOne(
      BaseMapper<T> mapper,
      SFunction<T, ?> column,
      Object value) {
    return MapperUtils.selectOneOptional(mapper, Wrappers.<T>lambdaQuery().eq(column, value));
  }

  public static <T> List<T> selectList(
      BaseMapper<T> mapper,
      ConsumerWrapper<T> builder) {
    LambdaQueryWrapper<T> wrapper = Wrappers.lambdaQuery();
    builder.accept(wrapper);
    return mapper.selectList(wrapper);
  }

  @FunctionalInterface
  public interface ConsumerWrapper<T> {
    void accept(LambdaQueryWrapper<T> wrapper);
  }
}
