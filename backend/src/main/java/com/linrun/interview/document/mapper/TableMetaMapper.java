package com.linrun.interview.document.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.linrun.interview.document.entity.TableMetaEntity;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;
import java.util.Map;

/**
 * DATA_QUERY 动态表元数据 Mapper。
 */
@Mapper
public interface TableMetaMapper extends BaseMapper<TableMetaEntity> {

 @Update("${sql}")
 void executeCreateTable(@Param("sql") String sql);

 @Update("${sql}")
 void executeInsert(@Param("sql") String sql);

 @Select("${sql}")
 List<Map<String, Object>> executeQuery(@Param("sql") String sql);

 @Update("DROP TABLE IF EXISTS ${tableName}")
 void dropTable(@Param("tableName") String tableName);

 @Select("SELECT COUNT(*) FROM information_schema.tables "
 + "WHERE table_name = #{tableName} AND table_schema = DATABASE")
 int checkTableExists(@Param("tableName") String tableName);

 @Delete("DELETE FROM table_meta WHERE table_name = #{tableName}")
 void physicalDeleteByTableName(@Param("tableName") String tableName);
}
