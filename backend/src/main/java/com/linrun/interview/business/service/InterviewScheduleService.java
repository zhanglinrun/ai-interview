package com.linrun.interview.business.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.linrun.interview.common.exception.BusinessException;
import com.linrun.interview.common.exception.ErrorCode;
import com.linrun.interview.auth.security.UserContext;
import com.linrun.interview.business.mapper.InterviewScheduleMapper;
import com.linrun.interview.business.vo.CreateScheduleRequest;
import com.linrun.interview.business.vo.InterviewScheduleDTO;
import com.linrun.interview.business.entity.InterviewScheduleEntity;
import com.linrun.interview.business.constant.InterviewStatus;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
public class InterviewScheduleService extends ServiceImpl<InterviewScheduleMapper, InterviewScheduleEntity> {

  @Transactional
  public InterviewScheduleDTO create(CreateScheduleRequest request) {
    InterviewScheduleEntity entity = new InterviewScheduleEntity();
    BeanUtils.copyProperties(request, entity);
    entity.setUserId(UserContext.requireUserId());
    entity.setStatus(InterviewStatus.PENDING);
    entity.setCreatedAt(LocalDateTime.now());
    entity.setUpdatedAt(LocalDateTime.now());
    save(entity);
    return toDTO(entity);
  }

  @Transactional
  public InterviewScheduleDTO update(Long id, CreateScheduleRequest request) {
    InterviewScheduleEntity entity = getByIdOrThrow(id);
    BeanUtils.copyProperties(request, entity, "id", "status");
    entity.setUpdatedAt(LocalDateTime.now());
    updateById(entity);
    return toDTO(entity);
  }

  @Transactional
  public void delete(Long id) {
    removeById(getByIdOrThrow(id).getId());
  }

  @Transactional
  public InterviewScheduleDTO updateStatus(Long id, InterviewStatus status) {
    InterviewScheduleEntity entity = getByIdOrThrow(id);
    entity.setStatus(status);
    entity.setUpdatedAt(LocalDateTime.now());
    updateById(entity);
    return toDTO(entity);
  }

  public List<InterviewScheduleDTO> getAll(String status, LocalDateTime start, LocalDateTime end) {
    Long userId = UserContext.requireUserId();
    List<InterviewScheduleEntity> entities;
    if (start != null && end != null) {
      entities = list(
        Wrappers.<InterviewScheduleEntity>lambdaQuery()
          .eq(InterviewScheduleEntity::getUserId, userId)
          .between(InterviewScheduleEntity::getInterviewTime, start, end)
          .orderByAsc(InterviewScheduleEntity::getInterviewTime));
    } else if (status != null) {
      entities = list(
        Wrappers.<InterviewScheduleEntity>lambdaQuery()
          .eq(InterviewScheduleEntity::getUserId, userId)
          .eq(InterviewScheduleEntity::getStatus, InterviewStatus.valueOf(status))
          .orderByAsc(InterviewScheduleEntity::getInterviewTime));
    } else {
      entities = list(
        Wrappers.<InterviewScheduleEntity>lambdaQuery()
          .eq(InterviewScheduleEntity::getUserId, userId)
          .orderByAsc(InterviewScheduleEntity::getInterviewTime));
    }
    return entities.stream().map(this::toDTO).toList();
  }

  public InterviewScheduleDTO getById(Long id) {
    return toDTO(getByIdOrThrow(id));
  }

  private InterviewScheduleEntity getByIdOrThrow(Long id) {
    InterviewScheduleEntity entity = getOne(Wrappers.<InterviewScheduleEntity>lambdaQuery()
        .eq(InterviewScheduleEntity::getUserId, UserContext.requireUserId())
        .eq(InterviewScheduleEntity::getId, id));
    if (entity == null) {
      throw new BusinessException(ErrorCode.INTERVIEW_SCHEDULE_NOT_FOUND, "面试日程不存在: " + id);
    }
    return entity;
  }

  private InterviewScheduleDTO toDTO(InterviewScheduleEntity entity) {
    InterviewScheduleDTO dto = new InterviewScheduleDTO();
    BeanUtils.copyProperties(entity, dto);
    return dto;
  }
}
