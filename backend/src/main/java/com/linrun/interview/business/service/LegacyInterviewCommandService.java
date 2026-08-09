package com.linrun.interview.business.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.linrun.interview.auth.security.UserContext;
import com.linrun.interview.business.constant.InterviewCommandStatus;
import com.linrun.interview.business.constant.InterviewCommandType;
import com.linrun.interview.business.entity.InterviewCommandEntity;
import com.linrun.interview.business.entity.InterviewSessionEntity;
import com.linrun.interview.business.mapper.InterviewCommandMapper;
import com.linrun.interview.business.mapper.InterviewSessionMapper;
import com.linrun.interview.business.vo.SubmitAnswerResponse;
import com.linrun.interview.common.exception.BusinessException;
import com.linrun.interview.common.exception.ErrorCode;
import com.linrun.interview.infra.observability.TraceContext;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Command reservation for the original text-interview endpoint.  The job
 * interview state machine has its own richer implementation; this service
 * gives the legacy session the same durable command semantics without merging
 * the two session models.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LegacyInterviewCommandService {
  private final InterviewCommandMapper commandMapper;
  private final InterviewSessionMapper sessionMapper;
  private final ObjectMapper objectMapper;

  @Transactional(rollbackFor = Exception.class)
  public Reservation reserve(Long userId, String sessionId, String commandId,
                             long expectedVersion) {
    if (userId == null || sessionId == null || sessionId.isBlank()
        || commandId == null || commandId.isBlank() || commandId.length() > 64
        || expectedVersion < 0) {
      throw new BusinessException(ErrorCode.BAD_REQUEST, "答案提交命令参数不完整");
    }
    InterviewCommandEntity existing = find(userId, sessionId, commandId);
    if (existing != null) {
      if (existing.getCommandType() != InterviewCommandType.SUBMIT_ANSWER
          || value(existing.getExpectedSessionVersion()) != expectedVersion) {
        throw new BusinessException(ErrorCode.BAD_REQUEST, "同一 commandId 不能复用于不同会话版本");
      }
      if (existing.getStatus() == InterviewCommandStatus.COMPLETED) {
        return new Reservation(existing, readResult(existing.getResultJson()), false);
      }
      if (existing.getStatus() == InterviewCommandStatus.PROCESSING) {
        throw new BusinessException(ErrorCode.INTERVIEW_COMMAND_IN_PROGRESS);
      }
      throw new BusinessException(ErrorCode.INTERVIEW_INVALID_STATE,
          "该指令此前失败，请使用新的 commandId");
    }

    InterviewSessionEntity session = sessionMapper.selectOne(
        Wrappers.<InterviewSessionEntity>lambdaQuery()
            .eq(InterviewSessionEntity::getUserId, userId)
            .eq(InterviewSessionEntity::getSessionId, sessionId)
            .last("LIMIT 1"));
    if (session == null) {
      throw new BusinessException(ErrorCode.INTERVIEW_SESSION_NOT_FOUND);
    }
    if (value(session.getSessionVersion()) != expectedVersion) {
      throw new BusinessException(ErrorCode.INTERVIEW_SESSION_VERSION_CONFLICT);
    }
    if (session.getStatus() == InterviewSessionEntity.SessionStatus.COMPLETED
        || session.getStatus() == InterviewSessionEntity.SessionStatus.EVALUATED) {
      throw new BusinessException(ErrorCode.INTERVIEW_ALREADY_COMPLETED);
    }

    int claimed = sessionMapper.update(null, Wrappers.<InterviewSessionEntity>lambdaUpdate()
        .eq(InterviewSessionEntity::getId, session.getId())
        .eq(InterviewSessionEntity::getUserId, userId)
        .eq(InterviewSessionEntity::getSessionVersion, expectedVersion)
        .isNull(InterviewSessionEntity::getActiveCommandId)
        .set(InterviewSessionEntity::getActiveCommandId, commandId));
    if (claimed != 1) {
      throw new BusinessException(ErrorCode.INTERVIEW_COMMAND_IN_PROGRESS);
    }

    LocalDateTime now = LocalDateTime.now();
    InterviewCommandEntity command = InterviewCommandEntity.builder()
        .userId(userId)
        .sessionId(sessionId)
        .commandId(commandId)
        .traceId(TraceContext.getTraceId())
        .commandType(InterviewCommandType.SUBMIT_ANSWER)
        .expectedSessionVersion(expectedVersion)
        .status(InterviewCommandStatus.PROCESSING)
        .createdAt(now)
        .updatedAt(now)
        .build();
    try {
      commandMapper.insert(command);
    } catch (RuntimeException duplicate) {
      InterviewCommandEntity raced = find(userId, sessionId, commandId);
      if (raced != null) {
        if (raced.getStatus() == InterviewCommandStatus.COMPLETED) {
          // This transaction may have claimed an otherwise idle session after
          // the original command committed.  Do not leave that claim behind
          // when the unique command row tells us the work is already done.
          releaseClaim(session, commandId);
          return new Reservation(raced, readResult(raced.getResultJson()), false);
        }
        if (raced.getStatus() == InterviewCommandStatus.PROCESSING) {
          throw new BusinessException(ErrorCode.INTERVIEW_COMMAND_IN_PROGRESS);
        }
        releaseClaim(session, commandId);
        throw new BusinessException(ErrorCode.INTERVIEW_INVALID_STATE,
            "该指令此前失败，请使用新的 commandId");
      }
      sessionMapper.update(null, Wrappers.<InterviewSessionEntity>lambdaUpdate()
          .eq(InterviewSessionEntity::getId, session.getId())
          .eq(InterviewSessionEntity::getActiveCommandId, commandId)
          .set(InterviewSessionEntity::getActiveCommandId, null));
      throw duplicate;
    }
    return new Reservation(command, null, true);
  }

  private void releaseClaim(InterviewSessionEntity session, String commandId) {
    if (session == null || commandId == null) {
      return;
    }
    sessionMapper.update(null, Wrappers.<InterviewSessionEntity>lambdaUpdate()
        .eq(InterviewSessionEntity::getId, session.getId())
        .eq(InterviewSessionEntity::getActiveCommandId, commandId)
        .set(InterviewSessionEntity::getActiveCommandId, null));
  }

  @Transactional(rollbackFor = Exception.class)
  public void complete(Reservation reservation, SubmitAnswerResponse response) {
    if (reservation == null || !reservation.fresh() || reservation.command() == null) {
      return;
    }
    InterviewCommandEntity command = commandMapper.selectById(reservation.command().getId());
    if (command == null || command.getStatus() != InterviewCommandStatus.PROCESSING) {
      return;
    }
    LocalDateTime now = LocalDateTime.now();
    command.setStatus(InterviewCommandStatus.COMPLETED);
    command.setResultJson(write(response));
    command.setUpdatedAt(now);
    command.setCompletedAt(now);
    commandMapper.updateById(command);
  }

  @Transactional(rollbackFor = Exception.class)
  public void fail(Reservation reservation, Throwable failure) {
    if (reservation == null || !reservation.fresh() || reservation.command() == null) {
      return;
    }
    InterviewCommandEntity command = commandMapper.selectById(reservation.command().getId());
    if (command == null || command.getStatus() != InterviewCommandStatus.PROCESSING) {
      return;
    }
    LocalDateTime now = LocalDateTime.now();
    sessionMapper.update(null, Wrappers.<InterviewSessionEntity>lambdaUpdate()
        .eq(InterviewSessionEntity::getUserId, command.getUserId())
        .eq(InterviewSessionEntity::getSessionId, command.getSessionId())
        .eq(InterviewSessionEntity::getActiveCommandId, command.getCommandId())
        .set(InterviewSessionEntity::getActiveCommandId, null));
    command.setStatus(InterviewCommandStatus.FAILED);
    command.setFailureCode(failure instanceof BusinessException business
        ? String.valueOf(business.getCode()) : "INTERNAL_ERROR");
    command.setFailureDetail(truncate(failure == null ? null : failure.getMessage(), 500));
    command.setUpdatedAt(now);
    command.setCompletedAt(now);
    commandMapper.updateById(command);
  }

  private InterviewCommandEntity find(Long userId, String sessionId, String commandId) {
    return commandMapper.selectOne(Wrappers.<InterviewCommandEntity>lambdaQuery()
        .eq(InterviewCommandEntity::getUserId, userId)
        .eq(InterviewCommandEntity::getSessionId, sessionId)
        .eq(InterviewCommandEntity::getCommandId, commandId)
        .last("LIMIT 1"));
  }

  private SubmitAnswerResponse readResult(String json) {
    try {
      return objectMapper.readValue(json, SubmitAnswerResponse.class);
    } catch (Exception e) {
      throw new BusinessException(ErrorCode.INTERNAL_ERROR, "历史答案提交结果损坏", e);
    }
  }

  private String write(Object value) {
    try {
      return objectMapper.writeValueAsString(value);
    } catch (Exception e) {
      throw new BusinessException(ErrorCode.INTERNAL_ERROR, "序列化答案提交结果失败", e);
    }
  }

  private long value(Long value) {
    return value == null ? 0L : value;
  }

  private String truncate(String value, int max) {
    if (value == null || value.length() <= max) {
      return value;
    }
    return value.substring(0, max);
  }

  public record Reservation(InterviewCommandEntity command,
                            SubmitAnswerResponse duplicateResult,
                            boolean fresh) {
  }
}
