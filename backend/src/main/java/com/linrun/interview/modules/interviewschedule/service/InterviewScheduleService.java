package com.linrun.interview.modules.interviewschedule.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.linrun.interview.common.exception.BusinessException;
import com.linrun.interview.common.exception.ErrorCode;
import com.linrun.interview.common.mybatis.EntityQueries;
import com.linrun.interview.common.mybatis.MapperUtils;
import com.linrun.interview.common.security.UserContext;
import com.linrun.interview.modules.interviewschedule.mapper.InterviewScheduleMapper;
import com.linrun.interview.modules.interviewschedule.model.CreateScheduleRequest;
import com.linrun.interview.modules.interviewschedule.model.InterviewScheduleDTO;
import com.linrun.interview.modules.interviewschedule.model.InterviewScheduleEntity;
import com.linrun.interview.modules.interviewschedule.model.InterviewStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
public class InterviewScheduleService {

  private final InterviewScheduleMapper interviewScheduleMapper;

  @Transactional
  public InterviewScheduleDTO create(CreateScheduleRequest request) {
    InterviewScheduleEntity entity = new InterviewScheduleEntity();
    BeanUtils.copyProperties(request, entity);
    entity.setUserId(UserContext.requireUserId());
    entity.setStatus(InterviewStatus.PENDING);
    entity.setCreatedAt(LocalDateTime.now());
    entity.setUpdatedAt(LocalDateTime.now());
    return toDTO(MapperUtils.save(interviewScheduleMapper, entity));
  }

  @Transactional
  public InterviewScheduleDTO update(Long id, CreateScheduleRequest request) {
    InterviewScheduleEntity entity = getByIdOrThrow(id);
    BeanUtils.copyProperties(request, entity, "id", "status");
    entity.setUpdatedAt(LocalDateTime.now());
    return toDTO(MapperUtils.save(interviewScheduleMapper, entity));
  }

  @Transactional
  public void delete(Long id) {
    interviewScheduleMapper.deleteById(getByIdOrThrow(id).getId());
  }

  @Transactional
  public InterviewScheduleDTO updateStatus(Long id, InterviewStatus status) {
    InterviewScheduleEntity entity = getByIdOrThrow(id);
    entity.setStatus(status);
    entity.setUpdatedAt(LocalDateTime.now());
    return toDTO(MapperUtils.save(interviewScheduleMapper, entity));
  }

  public List<InterviewScheduleDTO> getAll(String status, LocalDateTime start, LocalDateTime end) {
    Long userId = UserContext.requireUserId();
    List<InterviewScheduleEntity> entities;
    if (start != null && end != null) {
      entities = interviewScheduleMapper.selectList(
        Wrappers.<InterviewScheduleEntity>lambdaQuery()
          .eq(InterviewScheduleEntity::getUserId, userId)
          .between(InterviewScheduleEntity::getInterviewTime, start, end)
          .orderByAsc(InterviewScheduleEntity::getInterviewTime));
    } else if (status != null) {
      entities = interviewScheduleMapper.selectList(
        Wrappers.<InterviewScheduleEntity>lambdaQuery()
          .eq(InterviewScheduleEntity::getUserId, userId)
          .eq(InterviewScheduleEntity::getStatus, InterviewStatus.valueOf(status))
          .orderByAsc(InterviewScheduleEntity::getInterviewTime));
    } else {
      entities = interviewScheduleMapper.selectList(
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
    return EntityQueries.byUserAndId(
        interviewScheduleMapper, UserContext.requireUserId(), id,
        InterviewScheduleEntity::getUserId, InterviewScheduleEntity::getId)
      .orElseThrow(() -> new BusinessException(ErrorCode.INTERVIEW_SCHEDULE_NOT_FOUND, "面试日程不存在: " + id));
  }

  private InterviewScheduleDTO toDTO(InterviewScheduleEntity entity) {
    InterviewScheduleDTO dto = new InterviewScheduleDTO();
    BeanUtils.copyProperties(entity, dto);
    return dto;
  }
}
