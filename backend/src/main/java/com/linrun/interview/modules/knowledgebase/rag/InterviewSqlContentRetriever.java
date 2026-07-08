package com.linrun.interview.modules.knowledgebase.rag;

import com.linrun.interview.common.security.UserContext;
import dev.langchain4j.experimental.rag.content.retriever.sql.SqlDatabaseContentRetriever;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.input.PromptTemplate;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.rag.query.Query;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.datasource.DelegatingDataSource;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
    private final int maxRows;
    private final Long userId;

    public InterviewSqlContentRetriever(DataSource dataSource, ChatModel chatModel,
                                        ContentRetriever fallbackRetriever) {
        this(dataSource, chatModel, fallbackRetriever, "", Set.of(), 8, 100,
            UserContext.requireUserId());
    }

    public InterviewSqlContentRetriever(DataSource dataSource, ChatModel chatModel,
                                        ContentRetriever fallbackRetriever,
                                        String dynamicDatabaseStructure,
                                        Collection<String> dynamicTables,
                                        int queryTimeoutSeconds,
                                        int maxRows) {
        this(dataSource, chatModel, fallbackRetriever, dynamicDatabaseStructure, dynamicTables,
            queryTimeoutSeconds, maxRows, UserContext.requireUserId());
    }

    public InterviewSqlContentRetriever(DataSource dataSource, ChatModel chatModel,
                                        ContentRetriever fallbackRetriever,
                                        String dynamicDatabaseStructure,
                                        Collection<String> dynamicTables,
                                        int queryTimeoutSeconds,
                                        int maxRows,
                                        Long userId) {
        this(dataSource, chatModel, fallbackRetriever, dynamicDatabaseStructure, dynamicTables,
            queryTimeoutSeconds, maxRows, userId, null);
    }

    public InterviewSqlContentRetriever(DataSource dataSource, ChatModel chatModel,
                                        ContentRetriever fallbackRetriever,
                                        String dynamicDatabaseStructure,
                                        Collection<String> dynamicTables,
                                        int queryTimeoutSeconds,
                                        int maxRows,
                                        Long userId,
                                        PromptTemplate promptTemplate) {
        this.maxRows = Math.max(maxRows, 1);
        this.userId = userId;
        String databaseStructure = DATABASE_STRUCTURE
            + (dynamicDatabaseStructure == null ? "" : dynamicDatabaseStructure);
        var sqlBuilder = SqlDatabaseContentRetriever.builder()
            .dataSource(new SafeReadOnlyDataSource(dataSource, allowedTables(dynamicTables),
                queryTimeoutSeconds, this.maxRows, userId))
            .sqlDialect("MySQL")
            .databaseStructure(databaseStructure)
            .chatModel(chatModel)
            .maxRetries(1);
        if (promptTemplate != null) {
            sqlBuilder.promptTemplate(promptTemplate);
        }
        this.sqlRetriever = sqlBuilder.build();
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
        return """
            当前用户 user_id = %d。今天日期是 %s。
            只能查询上述白名单表，只能生成 SELECT 语句，禁止 UNION/INTERSECT/EXCEPT。
            每一张被查询的表都必须带上 user_id = %d 的过滤条件（多表 JOIN 时每张表都要用别名限定，如 a.user_id = %d AND b.user_id = %d）。
            返回明细数据时最多返回 %d 行；需要限制明细行数时使用 LIMIT %d。
            用户问题：%s
            """.formatted(userId, LocalDate.now(), userId, userId, userId, maxRows, maxRows, question);
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

    private Set<String> allowedTables(Collection<String> dynamicTables) {
        Set<String> tables = new LinkedHashSet<>(List.of(
            "resumes",
            "resume_analyses",
            "interview_sessions",
            "interview_answers",
            "interview_schedule"
        ));
        if (dynamicTables != null) {
            tables.addAll(dynamicTables);
        }
        return tables;
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

final class SafeReadOnlyDataSource extends DelegatingDataSource {

    private final Set<String> allowedTables;
    private final int queryTimeoutSeconds;
    private final int maxRows;
    private final Long userId;

    SafeReadOnlyDataSource(DataSource targetDataSource, Set<String> allowedTables,
                           int queryTimeoutSeconds, int maxRows, Long userId) {
        super(targetDataSource);
        this.allowedTables = allowedTables;
        this.queryTimeoutSeconds = Math.max(queryTimeoutSeconds, 1);
        this.maxRows = Math.max(maxRows, 1);
        this.userId = userId;
    }

    @Override
    public Connection getConnection() throws SQLException {
        return wrap(super.getConnection());
    }

    @Override
    public Connection getConnection(String username, String password) throws SQLException {
        return wrap(super.getConnection(username, password));
    }

    private Connection wrap(Connection connection) throws SQLException {
        connection.setReadOnly(true);
        return SqlSafety.proxy(connection, allowedTables, queryTimeoutSeconds, maxRows, userId);
    }
}

final class SqlSafety {

    private static final Pattern DANGEROUS = Pattern.compile(
        "\\b(insert|update|delete|merge|create|alter|drop|truncate|grant|revoke|copy|call|execute|vacuum|analyze|union|intersect|except)\\b",
        Pattern.CASE_INSENSITIVE);
    private static final Pattern TABLE_REF = Pattern.compile(
        "\\b(?:from|join)\\s+\"?([a-zA-Z_][a-zA-Z0-9_]*)\"?", Pattern.CASE_INSENSITIVE);
    /** 捕获 from/join 的表名与可选别名，用于「多表时逐表校验 user_id」。 */
    private static final Pattern TABLE_REF_ALIAS = Pattern.compile(
        "\\b(?:from|join)\\s+\"?([a-zA-Z_][a-zA-Z0-9_]*)\"?"
            + "(?:\\s+(?:as\\s+)?\"?([a-zA-Z_][a-zA-Z0-9_]*)\"?)?",
        Pattern.CASE_INSENSITIVE);
    /** 别名位置若匹配到这些 SQL 关键字，说明该表无别名（避免把 where/on 等误当别名）。 */
    private static final Set<String> ALIAS_STOP_WORDS = Set.of(
        "on", "where", "group", "order", "limit", "offset", "having", "join", "inner",
        "left", "right", "full", "cross", "outer", "using", "union", "intersect", "except");
    private static final Pattern CTE_NAME = Pattern.compile(
        "(?:\\bwith\\s+(?:recursive\\s+)?|,\\s*)\"?([a-zA-Z_][a-zA-Z0-9_]*)\"?\\s+as\\s*\\(",
        Pattern.CASE_INSENSITIVE);
    private static final Pattern FROM_CLAUSE = Pattern.compile(
        "\\bfrom\\b(.*?)(\\bwhere\\b|\\bgroup\\b|\\border\\b|\\blimit\\b|\\boffset\\b|\\bunion\\b|$)",
        Pattern.CASE_INSENSITIVE);
    private static final Pattern USER_FILTER = Pattern.compile(
        "(?:\\b[a-zA-Z_][a-zA-Z0-9_]*\\.)?\\buser_id\\b\\s*=\\s*(\\d+)");

    private SqlSafety() {
    }

    static Connection proxy(Connection connection, Set<String> allowedTables, int timeoutSeconds, int maxRows) {
        return proxy(connection, allowedTables, timeoutSeconds, maxRows, null);
    }

    static Connection proxy(Connection connection, Set<String> allowedTables, int timeoutSeconds, int maxRows,
                            Long userId) {
        return (Connection) java.lang.reflect.Proxy.newProxyInstance(
            Connection.class.getClassLoader(),
            new Class<?>[]{Connection.class},
            (proxy, method, args) -> {
                String name = method.getName();
                boolean preparedSql = ("prepareStatement".equals(name) || "prepareCall".equals(name))
                    && args != null && args.length > 0 && args[0] instanceof String;
                if (preparedSql) {
                    validate((String) args[0], allowedTables, userId);
                }
                Object result = invoke(connection, method, args);
                if ("createStatement".equals(name) && result instanceof Statement statement) {
                    return statementProxy(statement, allowedTables, timeoutSeconds, maxRows, userId);
                }
                if (preparedSql && result instanceof PreparedStatement statement) {
                    configure(statement, timeoutSeconds, maxRows);
                }
                return result;
            });
    }

    private static Statement statementProxy(Statement statement, Set<String> allowedTables,
                                            int timeoutSeconds, int maxRows, Long userId) throws SQLException {
        configure(statement, timeoutSeconds, maxRows);
        return (Statement) java.lang.reflect.Proxy.newProxyInstance(
            Statement.class.getClassLoader(),
            new Class<?>[]{Statement.class},
            (proxy, method, args) -> {
                if (isStatementSqlMethod(method.getName())
                    && args != null && args.length > 0 && args[0] instanceof String sql) {
                    validate(sql, allowedTables, userId);
                }
                return invoke(statement, method, args);
            });
    }

    private static boolean isStatementSqlMethod(String methodName) {
        return "execute".equals(methodName)
            || "executeQuery".equals(methodName)
            || "executeUpdate".equals(methodName)
            || "executeLargeUpdate".equals(methodName)
            || "addBatch".equals(methodName);
    }

    private static void configure(Statement statement, int timeoutSeconds, int maxRows) throws SQLException {
        statement.setQueryTimeout(Math.max(timeoutSeconds, 1));
        statement.setMaxRows(Math.max(maxRows, 1));
    }

    private static Object invoke(Object target, java.lang.reflect.Method method, Object[] args) throws Throwable {
        try {
            return method.invoke(target, args);
        } catch (java.lang.reflect.InvocationTargetException e) {
            throw e.getTargetException();
        }
    }

    static void validate(String sql, Set<String> allowedTables) throws SQLException {
        validate(sql, allowedTables, null);
    }

    static void validate(String sql, Set<String> allowedTables, Long userId) throws SQLException {
        String normalized = stripComments(sql).trim().toLowerCase(Locale.ROOT);
        if (!(normalized.startsWith("select ") || normalized.startsWith("with "))) {
            throw new SQLException("Text2SQL 只允许 SELECT/WITH 查询");
        }
        if (normalized.contains(";") || DANGEROUS.matcher(normalized).find()) {
            throw new SQLException("Text2SQL 查询包含危险语句");
        }
        if (hasCommaJoin(normalized)) {
            throw new SQLException("Text2SQL 不允许逗号连接表，请使用显式 JOIN");
        }
        Set<String> cteNames = cteNames(normalized);
        List<String> baseTableScopes = new ArrayList<>();
        Matcher matcher = TABLE_REF_ALIAS.matcher(normalized);
        while (matcher.find()) {
            String table = matcher.group(1);
            if (cteNames.contains(table)) {
                continue;
            }
            if (!allowedTables.contains(table)) {
                throw new SQLException("Text2SQL 表不在白名单: " + table);
            }
            String alias = matcher.group(2);
            if (alias != null && ALIAS_STOP_WORDS.contains(alias)) {
                alias = null;
            }
            baseTableScopes.add(alias != null ? alias : table);
        }
        if (baseTableScopes.isEmpty()) {
            throw new SQLException("Text2SQL 查询必须访问白名单表");
        }
        if (userId != null) {
            validateUserScope(normalized, userId, baseTableScopes);
        }
    }

    private static void validateUserScope(String normalizedSql, Long userId, List<String> baseTableScopes)
            throws SQLException {
        if (!normalizedSql.contains(" where ")) {
            throw new SQLException("Text2SQL 查询必须包含 user_id 过滤条件");
        }
        if (Pattern.compile("\\bor\\b").matcher(normalizedSql).find()) {
            throw new SQLException("Text2SQL 查询不允许 OR，避免绕过 user_id 过滤");
        }
        String me = String.valueOf(userId);
        // 任意出现的 user_id 常量都必须等于当前用户，杜绝 user_id = <他人> 直读
        Matcher matcher = USER_FILTER.matcher(normalizedSql);
        boolean found = false;
        while (matcher.find()) {
            found = true;
            if (!me.equals(matcher.group(1))) {
                throw new SQLException("Text2SQL user_id 与当前用户不一致");
            }
        }
        if (!found) {
            throw new SQLException("Text2SQL 查询必须包含当前用户 user_id 过滤条件");
        }
        // 多表 JOIN：要求每张表都带自己的 user_id = <me>，防止只约束一张表、JOIN 出他人数据
        if (baseTableScopes.size() > 1) {
            for (String scope : baseTableScopes) {
                Pattern perTable = Pattern.compile(
                    "\\b" + Pattern.quote(scope) + "\\.user_id\\s*=\\s*" + me + "\\b");
                if (!perTable.matcher(normalizedSql).find()) {
                    throw new SQLException(
                        "Text2SQL 多表查询要求每张表都带 user_id 过滤，缺失: " + scope);
                }
            }
        }
    }

    private static Set<String> cteNames(String normalizedSql) {
        Set<String> names = new LinkedHashSet<>();
        Matcher matcher = CTE_NAME.matcher(normalizedSql);
        while (matcher.find()) {
            names.add(matcher.group(1));
        }
        return names;
    }

    private static boolean hasCommaJoin(String normalizedSql) {
        Matcher matcher = FROM_CLAUSE.matcher(normalizedSql);
        while (matcher.find()) {
            if (matcher.group(1).contains(",")) {
                return true;
            }
        }
        return false;
    }

    private static String stripComments(String sql) {
        return sql.replaceAll("(?s)/\\*.*?\\*/", " ")
            .replaceAll("(?m)--.*$", " ");
    }
}
