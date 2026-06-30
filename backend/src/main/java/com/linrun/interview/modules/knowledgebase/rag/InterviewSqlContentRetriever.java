package com.linrun.interview.modules.knowledgebase.rag;

import com.linrun.interview.common.security.UserContext;
import dev.langchain4j.experimental.rag.content.retriever.sql.SqlDatabaseContentRetriever;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.rag.query.Query;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.datasource.DelegatingDataSource;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;

/**
 * 面试业务 Text2SQL 检索器：只暴露白名单表结构，失败降级 ES。
 */
@Slf4j
public class InterviewSqlContentRetriever implements ContentRetriever {

    private static final String DATABASE_STRUCTURE = """
        Table resumes:
        - id bigint primary key
        - user_id bigint
        - original_filename varchar
        - file_size bigint
        - analyze_status varchar
        - uploaded_at timestamp
        - access_count integer

        Table resume_analyses:
        - id bigint primary key
        - user_id bigint
        - resume_id bigint
        - overall_score integer
        - content_score integer
        - structure_score integer
        - skill_match_score integer
        - expression_score integer
        - project_score integer
        - summary text
        - analyzed_at timestamp

        Table interview_sessions:
        - id bigint primary key
        - user_id bigint
        - session_id varchar
        - resume_id bigint
        - skill_id varchar
        - difficulty varchar
        - total_questions integer
        - status varchar
        - overall_score integer
        - created_at timestamp
        - completed_at timestamp

        Table interview_answers:
        - id bigint primary key
        - user_id bigint
        - session_id bigint
        - question_index integer
        - question text
        - category varchar
        - score integer
        - answered_at timestamp

        Table interview_schedule:
        - id bigint primary key
        - user_id bigint
        - company_name varchar
        - position varchar
        - interview_time timestamp
        - interview_type varchar
        - round_number integer
        - interviewer varchar
        - status varchar
        """;

    private final SqlDatabaseContentRetriever sqlRetriever;
    private final ContentRetriever fallbackRetriever;

    public InterviewSqlContentRetriever(DataSource dataSource, ChatModel chatModel,
                                        ContentRetriever fallbackRetriever) {
        this.sqlRetriever = SqlDatabaseContentRetriever.builder()
            .dataSource(new ReadOnlyDataSource(dataSource))
            .sqlDialect("PostgreSQL")
            .databaseStructure(DATABASE_STRUCTURE)
            .chatModel(chatModel)
            .maxRetries(1)
            .build();
        this.fallbackRetriever = fallbackRetriever;
    }

    @Override
    public List<Content> retrieve(Query query) {
        Query scopedQuery = new Query(buildScopedQuestion(query.text()), query.metadata());
        try {
            List<Content> results = sqlRetriever.retrieve(scopedQuery);
            if (results == null || results.isEmpty() || isSqlResultEmpty(results)) {
                log.info("[InterviewSqlContentRetriever] SQL 无结果，降级 ES: query={}", query.text());
                return fallbackRetriever.retrieve(query);
            }
            return results.stream().map(ContentUtil::markAsSkipRerank).toList();
        } catch (Exception e) {
            log.warn("[InterviewSqlContentRetriever] SQL 检索失败，降级 ES: {}", e.getMessage(), e);
            return fallbackRetriever.retrieve(query);
        }
    }

    private String buildScopedQuestion(String question) {
        Long userId = UserContext.requireUserId();
        return """
            当前用户 user_id = %d。今天日期是 %s。
            只能查询上述白名单表，只能生成 SELECT 语句，并且必须在 SQL 中加入 user_id = %d 的过滤条件。
            用户问题：%s
            """.formatted(userId, LocalDate.now(), userId, question);
    }

    private boolean isSqlResultEmpty(List<Content> results) {
        if (results.size() != 1) {
            return false;
        }
        String text = results.getFirst().textSegment().text();
        if (!text.startsWith("Result of executing '")) {
            return false;
        }
        int columnStart = text.lastIndexOf("':\n");
        if (columnStart < 0) {
            columnStart = text.indexOf(":\n");
        } else {
            columnStart++;
        }
        int dataStart = text.indexOf('\n', columnStart + 2);
        return dataStart < 0 || text.substring(dataStart + 1).trim().isEmpty();
    }
}

final class ReadOnlyDataSource extends DelegatingDataSource {

    ReadOnlyDataSource(DataSource targetDataSource) {
        super(targetDataSource);
    }

    @Override
    public Connection getConnection() throws SQLException {
        return readOnly(super.getConnection());
    }

    @Override
    public Connection getConnection(String username, String password) throws SQLException {
        return readOnly(super.getConnection(username, password));
    }

    private Connection readOnly(Connection connection) throws SQLException {
        connection.setReadOnly(true);
        return connection;
    }
}
