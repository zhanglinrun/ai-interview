package com.linrun.interview.config;

import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * 领域化目录的最小架构护栏：旧 modules/旧令牌实现不能回流，Controller 不能越过应用层
 * 直接依赖基础设施实现。新增领域能力应通过 port 或 application service 接入。
 */
@AnalyzeClasses(packages = "com.linrun.interview")
class ArchitectureRulesTest {

    @ArchTest
    static final ArchRule legacyModulesMustNotReturn = noClasses()
        .should().resideInAnyPackage("..modules..", "..infrastructure..", "..common.security..", "..dingtalk..");

    @ArchTest
    static final ArchRule controllersMustNotDependOnInfrastructure = noClasses()
        .that().resideInAnyPackage("..controller..")
        .should().dependOnClassesThat().resideInAnyPackage("..infra..", "..infrastructure..");

    @ArchTest
    static final ArchRule featureModulesMustStayFlat = noClasses()
        .should().resideInAnyPackage(
            "..ai.provider..",
            "..business.candidate..",
            "..business.capability..",
            "..business.interview..",
            "..business.practice..",
            "..business.report..",
            "..chat.model..",
            "..document.embedding..",
            "..document.model..",
            "..document.parser..",
            "..document.permission..",
            "..document.splitter..",
            "..document.storage..",
            "..document.version..",
            "..github.chunk..",
            "..github.mcp..",
            "..rag.aggregator..",
            "..rag.citation..",
            "..rag.evaluation..",
            "..rag.evidence..",
            "..rag.intent..",
            "..rag.retriever..",
            "..rag.rewrite..",
            "..rag.router..",
            "..rag.trace..");
}
