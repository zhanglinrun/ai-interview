package com.linrun.interview.modules.knowledgebase.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = Neo4jProperties.PREFIX)
public class Neo4jProperties {

  public static final String PREFIX = "neo4j";

  private String uri = "bolt://localhost:7687";
  private String username = "neo4j";
  private String password = "neo4j666";
}
