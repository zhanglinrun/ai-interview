package com.linrun.interview.chat.converter;

import com.linrun.interview.chat.dto.RagChatDTO.MessageDTO;
import com.linrun.interview.chat.dto.RagChatDTO.SessionDTO;
import com.linrun.interview.chat.dto.RagChatDTO.SessionDetailDTO;
import com.linrun.interview.chat.dto.RagChatDTO.SessionListItemDTO;
import com.linrun.interview.chat.entity.RagChatMessageEntity;
import com.linrun.interview.chat.entity.RagChatSessionEntity;
import com.linrun.interview.document.entity.KnowledgeBaseEntity;
import com.linrun.interview.document.converter.KnowledgeBaseMapper;
import com.linrun.interview.document.vo.KnowledgeBaseListItemDTO;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingConstants;
import org.mapstruct.Named;
import org.mapstruct.ReportingPolicy;

import java.util.Collection;
import java.util.List;

/**
 * RAG 聊天领域的 MapStruct 转换器。
 *
 * <p>转换器放在 converter 包而不是 MyBatis mapper 包，避免被
 * {@code @MapperScan} 当成数据库 Mapper 代理。</p>
 */
@Mapper(
    componentModel = MappingConstants.ComponentModel.SPRING,
    unmappedTargetPolicy = ReportingPolicy.IGNORE,
    uses = KnowledgeBaseMapper.class
)
public interface RagChatConverter {

    @Mapping(target = "knowledgeBaseIds", source = "session", qualifiedByName = "extractKnowledgeBaseIds")
    SessionDTO toSessionDTO(RagChatSessionEntity session);

    @Mapping(target = "type", source = "message", qualifiedByName = "getTypeString")
    MessageDTO toMessageDTO(RagChatMessageEntity message);

    List<MessageDTO> toMessageDTOList(List<RagChatMessageEntity> messages);

    @Named("extractKnowledgeBaseNames")
    default List<String> extractKnowledgeBaseNames(Collection<KnowledgeBaseEntity> knowledgeBases) {
        return knowledgeBases.stream().map(KnowledgeBaseEntity::getName).toList();
    }

    @Named("extractKnowledgeBaseIds")
    default List<Long> extractKnowledgeBaseIds(RagChatSessionEntity session) {
        return session.getKnowledgeBaseIds();
    }

    @Named("getTypeString")
    default String getTypeString(RagChatMessageEntity message) {
        return message.getTypeString();
    }

    @Mapping(target = "knowledgeBaseNames", source = "session.knowledgeBases",
        qualifiedByName = "extractKnowledgeBaseNames")
    @Mapping(target = "isPinned", source = "session", qualifiedByName = "getIsPinnedWithDefault")
    SessionListItemDTO toSessionListItemDTO(RagChatSessionEntity session);

    @Named("getIsPinnedWithDefault")
    default Boolean getIsPinnedWithDefault(RagChatSessionEntity session) {
        return session.getIsPinned() != null ? session.getIsPinned() : false;
    }

    default SessionDetailDTO toSessionDetailDTO(
            RagChatSessionEntity session,
            List<RagChatMessageEntity> messages,
            List<KnowledgeBaseListItemDTO> knowledgeBases) {
        return new SessionDetailDTO(
            session.getId(),
            session.getTitle(),
            knowledgeBases,
            toMessageDTOList(messages),
            session.getCreatedAt(),
            session.getUpdatedAt()
        );
    }
}
