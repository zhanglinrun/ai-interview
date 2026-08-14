package com.linrun.interview.document.service;

import com.linrun.interview.document.entity.TableMetaEntity;

import java.util.List;

/**
 * DATA_QUERY 动态表元数据查询。
 */
public interface TableMetaCatalogService {

 List<TableMetaEntity> listActiveForQuery(Long userId);
}
