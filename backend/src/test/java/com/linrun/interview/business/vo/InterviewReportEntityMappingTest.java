package com.linrun.interview.business.vo;

import com.linrun.interview.business.entity.InterviewReportEntity;
import static org.assertj.core.api.Assertions.assertThat;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.TableField;
import java.lang.reflect.Field;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("报告可空状态字段映射")
class InterviewReportEntityMappingTest {

  @Test
  @DisplayName("完成、失败和重试时必须允许显式清空租约与失败信息")
  void shouldAlwaysUpdateNullableRecoveryFields() throws Exception {
    assertAlways("generationClaimedAt");
    assertAlways("failureCode");
    assertAlways("failureDetail");
  }

  private void assertAlways(String fieldName) throws Exception {
    Field field = InterviewReportEntity.class.getDeclaredField(fieldName);
    TableField annotation = field.getAnnotation(TableField.class);
    assertThat(annotation).isNotNull();
    assertThat(annotation.updateStrategy()).isEqualTo(FieldStrategy.ALWAYS);
  }
}
