// ============================================================
// AI Interview 面试知识图谱测试数据（对齐 know-engine neo4j/test-data.cypher）
// 适用于 Neo4j 5.x + APOC，可在 Neo4j Browser / cypher-shell 中执行
//
// 导入方式（docker compose 已挂载到容器 /import）：
//   docker exec -it interview-neo4j cypher-shell -u neo4j -p neo4j666 -f /import/test-data.cypher
//
// 节点：Skill（技能方向）、Concept（知识点）、InterviewQuestion（面试题）
// 关系：COVERS、DEPENDS_ON、BELONGS_TO、RELATES_TO
// ============================================================

MATCH (n) DETACH DELETE n;

CREATE CONSTRAINT unique_concept_name IF NOT EXISTS
  FOR (c:Concept) REQUIRE c.name IS UNIQUE;

CREATE INDEX idx_skill_name IF NOT EXISTS
  FOR (s:Skill) ON (s.name);

CREATE INDEX idx_concept_name IF NOT EXISTS
  FOR (c:Concept) ON (c.name);

// 技能方向
MERGE (java:Skill {name: 'Java'})
  ON CREATE SET java.level = 'backend', java.aliases = ['Java后端', 'Java开发'];

MERGE (spring:Skill {name: 'Spring'})
  ON CREATE SET spring.level = 'framework', spring.aliases = ['Spring Boot', 'Spring框架'];

MERGE (db:Skill {name: '数据库'})
  ON CREATE SET db.level = 'storage', db.aliases = ['MySQL', 'Redis', '数据库'];

// 知识点
MERGE (jvm:Concept {name: 'JVM'})
  ON CREATE SET jvm.difficulty = 'hard', jvm.category = 'runtime';

MERGE (gc:Concept {name: '垃圾回收'})
  ON CREATE SET gc.difficulty = 'hard', gc.category = 'runtime';

MERGE (springIoc:Concept {name: 'Spring IOC'})
  ON CREATE SET springIoc.difficulty = 'medium', springIoc.category = 'framework';

MERGE (springAop:Concept {name: 'Spring AOP'})
  ON CREATE SET springAop.difficulty = 'medium', springAop.category = 'framework';

MERGE (mysql:Concept {name: 'MySQL'})
  ON CREATE SET mysql.difficulty = 'medium', mysql.category = 'database';

MERGE (redis:Concept {name: 'Redis'})
  ON CREATE SET redis.difficulty = 'medium', redis.category = 'cache';

MERGE (indexing:Concept {name: '索引优化'})
  ON CREATE SET indexing.difficulty = 'hard', indexing.category = 'database';

// 面试题
MERGE (q1:InterviewQuestion {id: 'Q_JVM_001'})
  ON CREATE SET q1.title = 'JVM 内存结构有哪些区域？', q1.difficulty = 'medium';

MERGE (q2:InterviewQuestion {id: 'Q_REDIS_001'})
  ON CREATE SET q2.title = 'Redis 持久化 RDB 和 AOF 的区别？', q2.difficulty = 'medium';

MERGE (q3:InterviewQuestion {id: 'Q_SPRING_001'})
  ON CREATE SET q3.title = 'Spring Bean 的生命周期？', q3.difficulty = 'medium';

// 技能覆盖知识点
MATCH (s:Skill {name: 'Java'}), (c:Concept {name: 'JVM'}) MERGE (s)-[:COVERS]->(c);
MATCH (s:Skill {name: 'Java'}), (c:Concept {name: '垃圾回收'}) MERGE (s)-[:COVERS]->(c);
MATCH (s:Skill {name: 'Spring'}), (c:Concept {name: 'Spring IOC'}) MERGE (s)-[:COVERS]->(c);
MATCH (s:Skill {name: 'Spring'}), (c:Concept {name: 'Spring AOP'}) MERGE (s)-[:COVERS]->(c);
MATCH (s:Skill {name: '数据库'}), (c:Concept {name: 'MySQL'}) MERGE (s)-[:COVERS]->(c);
MATCH (s:Skill {name: '数据库'}), (c:Concept {name: 'Redis'}) MERGE (s)-[:COVERS]->(c);
MATCH (s:Skill {name: '数据库'}), (c:Concept {name: '索引优化'}) MERGE (s)-[:COVERS]->(c);

// 知识点归属（供 RELATES_TO 示例查询）
MATCH (c:Concept {name: 'JVM'}), (s:Skill {name: 'Java'}) MERGE (c)-[:BELONGS_TO]->(s);
MATCH (c:Concept {name: '垃圾回收'}), (s:Skill {name: 'Java'}) MERGE (c)-[:BELONGS_TO]->(s);
MATCH (c:Concept {name: 'Spring IOC'}), (s:Skill {name: 'Spring'}) MERGE (c)-[:BELONGS_TO]->(s);
MATCH (c:Concept {name: 'Redis'}), (s:Skill {name: '数据库'}) MERGE (c)-[:BELONGS_TO]->(s);

// 依赖链
MATCH (gc:Concept {name: '垃圾回收'}), (jvm:Concept {name: 'JVM'}) MERGE (gc)-[:DEPENDS_ON]->(jvm);
MATCH (springAop:Concept {name: 'Spring AOP'}), (springIoc:Concept {name: 'Spring IOC'}) MERGE (springAop)-[:DEPENDS_ON]->(springIoc);
MATCH (indexing:Concept {name: '索引优化'}), (mysql:Concept {name: 'MySQL'}) MERGE (indexing)-[:DEPENDS_ON]->(mysql);

// 面试题关联知识点
MATCH (q:InterviewQuestion {id: 'Q_JVM_001'}), (c:Concept {name: 'JVM'}) MERGE (q)-[:RELATES_TO]->(c);
MATCH (q:InterviewQuestion {id: 'Q_REDIS_001'}), (c:Concept {name: 'Redis'}) MERGE (q)-[:RELATES_TO]->(c);
MATCH (q:InterviewQuestion {id: 'Q_SPRING_001'}), (c:Concept {name: 'Spring IOC'}) MERGE (q)-[:RELATES_TO]->(c);
