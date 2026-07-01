package com.linrun.interview.modules.knowledgebase.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("knowledge_base_data_tables")
public class KnowledgeBaseDataTableEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;

    private Long docId;

    private String physicalTableName;

    private String logicalName;

    private String description;

    private String columnsJson;

    private Integer rowCount;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;


}
