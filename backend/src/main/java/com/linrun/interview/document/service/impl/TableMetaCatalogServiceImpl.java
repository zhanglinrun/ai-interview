package com.linrun.interview.document.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.linrun.interview.document.entity.TableMetaEntity;
import com.linrun.interview.document.mapper.TableMetaMapper;
import com.linrun.interview.document.service.TableMetaCatalogService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.List;

/**
 * DATA_QUERY 动态表元数据目录（按 userId 多租户隔离）。
 */
@Service
@RequiredArgsConstructor
public class TableMetaCatalogServiceImpl implements TableMetaCatalogService {

    private final TableMetaMapper tableMetaMapper;

    @Override
    public List<TableMetaEntity> listActiveForQuery(Long userId) {
        if (userId == null || userId <= 0) {
            return Collections.emptyList();
        }
        // DATA_QUERY 同一逻辑表在所有版本中复用同一个物理表，只要 create_sql 有效即暴露给 Text2SQL。
        return tableMetaMapper.selectList(
            Wrappers.<TableMetaEntity>lambdaQuery()
                .eq(TableMetaEntity::getUserId, userId)
                .isNotNull(TableMetaEntity::getCreateSql)
                .ne(TableMetaEntity::getCreateSql, ""));
    }
}
