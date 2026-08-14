package com.linrun.interview.business.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.linrun.interview.rag.model.DataDomain;
import com.linrun.interview.rag.model.EvidenceCandidate;
import com.linrun.interview.rag.model.EvidencePacket;
import com.linrun.interview.rag.model.EvidenceRef;
import com.linrun.interview.rag.model.EvidenceScope;
import com.linrun.interview.rag.model.EvidenceStatus;
import com.linrun.interview.document.service.impl.FileHashService;
import com.linrun.interview.business.mapper.CapabilityAtomDefinitionMapper;
import com.linrun.interview.business.mapper.PlatformKnowledgeManifestMapper;
import com.linrun.interview.business.mapper.QuestionTemplateMapper;
import com.linrun.interview.business.entity.CapabilityAtomDefinitionEntity;
import com.linrun.interview.business.constant.CatalogStatus;
import com.linrun.interview.business.entity.PlatformKnowledgeManifestEntity;
import com.linrun.interview.business.entity.QuestionTemplateEntity;
import com.linrun.interview.business.service.EvaluationRubricService;
import com.linrun.interview.github.dto.GithubEvidenceCardDTO;
import com.linrun.interview.github.dto.GithubEvidenceCardRequest;
import com.linrun.interview.github.dto.GithubEvidenceCardRequest.CapabilityTarget;
import com.linrun.interview.github.model.GithubRepositoryEntity;
import com.linrun.interview.github.service.GithubEvidenceCardService;
import com.linrun.interview.github.service.GithubEvidenceIndexer;
import com.linrun.interview.business.vo.InterviewPlan;
import com.linrun.interview.business.service.InterviewOrchestrator;
import com.linrun.interview.business.service.InterviewTopic;
import com.linrun.interview.business.service.InterviewTopic.Category;
import com.linrun.interview.business.service.InterviewTopicCatalog;
import com.linrun.interview.business.config.JobInterviewProperties;
import com.linrun.interview.business.vo.JobInterviewPlanSnapshot;
import com.linrun.interview.business.vo.JobInterviewPlanSnapshot.PlannedQuestion;
import com.linrun.interview.business.constant.JobInterviewStage;
import com.linrun.interview.business.entity.PreparationRunEntity;
import com.linrun.interview.business.job.JobCapabilityMappingEntity;
import com.linrun.interview.business.job.JobDescriptionEntity;
import com.linrun.interview.document.entity.KnowledgeBaseEntity;
import com.linrun.interview.rag.service.EvidenceRetrievalService;
import com.linrun.interview.rag.service.EvidenceSnapshotService;
import com.linrun.interview.business.entity.ResumeEntity;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

/** 用能力目录骨架、JD 原文证据和分域 RAG 构建可复现的岗位实战计划。 */
@Slf4j
@Service
@RequiredArgsConstructor
public class JobInterviewPlanBuilder {

  private static final String RUBRIC_CODE = "TECHNICAL_ANSWER";
  private static final String RUBRIC_VERSION = "1.0.0";
  private static final int MAX_EVIDENCE_PER_PACKET = 8;

  private final CapabilityAtomDefinitionMapper atomMapper;
  private final QuestionTemplateMapper questionTemplateMapper;
  private final PlatformKnowledgeManifestMapper platformKnowledgeMapper;
  private final EvaluationRubricService rubricService;
  private final EvidenceRetrievalService evidenceRetrievalService;
  private final EvidenceSnapshotService evidenceSnapshotService;
  private final GithubEvidenceCardService githubEvidenceCardService;
  private final InterviewOrchestrator orchestrator;
  private final ObjectProvider<AlgorithmPreparationPort> algorithmPortProvider;
  private final JobInterviewProperties properties;
  private final FileHashService fileHashService;
  private final ObjectMapper objectMapper;

  public PreparedPlan build(
      PreparationRunEntity run,
      JobDescriptionEntity job,
      List<JobCapabilityMappingEntity> mappings,
      ResumeEntity resume,
      GithubRepositoryEntity github,
      List<KnowledgeBaseEntity> knowledgeBases
  ) {
    rubricService.get(RUBRIC_CODE, RUBRIC_VERSION);
    List<String> degraded = new ArrayList<>();
    Map<String, String> dependencies = new LinkedHashMap<>();
    dependencies.put("JD", "READY");
    dependencies.put("CAPABILITY_TEMPLATE", "READY");
    dependencies.put("RUBRIC", "READY");
    if (run.getResumeId() == null) {
      dependencies.put("RESUME", "NOT_SELECTED");
    } else if (resume == null) {
      dependencies.put("RESUME", "DEGRADED");
      degraded.add("RESUME_UNAVAILABLE");
    } else {
      dependencies.put("RESUME", "READY");
    }
    if (Boolean.TRUE.equals(run.getIncludePersonalMaterials())
        && knowledgeBases.isEmpty()) {
      dependencies.put("PERSONAL_KNOWLEDGE", "DEGRADED");
      degraded.add("PERSONAL_KNOWLEDGE_UNAVAILABLE");
    } else {
      dependencies.put("PERSONAL_KNOWLEDGE",
          knowledgeBases.isEmpty() ? "NOT_SELECTED" : "READY");
    }

    Map<String, CapabilityAtomDefinitionEntity> atoms = loadAtoms(mappings);
    Map<String, List<QuestionTemplateEntity>> templates = loadQuestionTemplates(atoms);
    List<PlatformKnowledgeManifestEntity> platformKnowledge = loadPlatformKnowledge();
    Map<String, GithubEvidenceCardDTO> githubCards = loadGithubCards(
        run, mappings, github, degraded, dependencies);

    Map<String, EvidencePacket> packets = new LinkedHashMap<>();
    Map<String, String> snapshotIds = new LinkedHashMap<>();
    for (JobCapabilityMappingEntity mapping : mappings) {
      EvidencePacket packet = buildEvidencePacket(
          run, job, mapping, atoms.get(key(mapping)), platformKnowledge,
          resume, github, knowledgeBases, githubCards.get(key(mapping)), degraded);
      String snapshotId = evidenceSnapshotService.save(
          run.getUserId(), "JOB_INTERVIEW_PREPARATION", run.getRunId(), packet);
      packets.put(key(mapping), packet);
      snapshotIds.put(key(mapping), snapshotId);
    }
    dependencies.put("EVIDENCE", "READY");

    InterviewPlan orchestrationPlan = orchestrator.plan(new InterviewOrchestrator.PlanRequest(
        run.getRunId(), run.getUserId(), null, toTopic(job, mappings), "mid", 6,
        resume == null ? null : resume.getResumeText(),
        knowledgeBases.stream().map(KnowledgeBaseEntity::getId).toList()));
    dependencies.put("ORCHESTRATOR_PLAN", "READY");

    List<QuestionDraft> drafts = selectQuestionDrafts(
        job, mappings, templates, packets, snapshotIds, githubCards);
    AlgorithmPreparationPort.Reservation algorithm = reserveAlgorithm(run, job, degraded, dependencies);
    drafts.add(new QuestionDraft(
        JobInterviewStage.ALGORITHM,
        "ALGORITHM",
        algorithm.question(),
        "ALGORITHM_PROBLEM_SOLVING",
        algorithm.problemVersion(),
        algorithm.problemId(),
        algorithm.problemVersion(),
        RUBRIC_CODE,
        RUBRIC_VERSION,
        null,
        List.of(),
        EvidenceStatus.NONE));
    drafts.sort(Comparator.comparingInt(draft -> draft.stage().ordinal()));

    List<PlannedQuestion> questions = finalizeQuestions(drafts);
    String modelSnapshot = "BYOK:user:" + run.getUserId();
    String unsignedJson = writeJson(Map.of(
        "templateCode", job.getTemplateCode(),
        "templateVersion", job.getTemplateVersion(),
        "promptVersion", properties.getPromptVersion(),
        "modelSnapshot", modelSnapshot,
        "questions", questions,
        "evidenceSnapshotIds", snapshotIds.values(),
        "orchestrationPlan", orchestrationPlan));
    String planVersion = "plan-" + fileHashService.calculateHash(
        unsignedJson.getBytes(StandardCharsets.UTF_8)).substring(0, 20);
    JobInterviewPlanSnapshot snapshot = new JobInterviewPlanSnapshot(
        planVersion,
        properties.getPromptVersion(),
        job.getTemplateCode(),
        job.getTemplateVersion(),
        RUBRIC_CODE,
        RUBRIC_VERSION,
        modelSnapshot,
        questions,
        List.copyOf(snapshotIds.values()),
        degraded.stream().distinct().toList());
    return new PreparedPlan(snapshot, orchestrationPlan, Map.copyOf(dependencies));
  }

  private Map<String, CapabilityAtomDefinitionEntity> loadAtoms(
      List<JobCapabilityMappingEntity> mappings
  ) {
    Set<String> ids = mappings.stream().map(JobCapabilityMappingEntity::getAtomId)
        .collect(Collectors.toSet());
    List<CapabilityAtomDefinitionEntity> entities = atomMapper.selectList(
        Wrappers.<CapabilityAtomDefinitionEntity>lambdaQuery()
            .in(CapabilityAtomDefinitionEntity::getAtomId, ids));
    Map<String, CapabilityAtomDefinitionEntity> result = entities.stream()
        .collect(Collectors.toMap(
            atom -> atom.getAtomId() + "@" + atom.getVersion(),
            Function.identity(),
            (left, right) -> left));
    for (JobCapabilityMappingEntity mapping : mappings) {
      if (!result.containsKey(key(mapping))
          && !mapping.getAtomId().startsWith("JD_TEMP_")) {
        throw new IllegalStateException("能力定义版本不存在: " + key(mapping));
      }
    }
    return result;
  }

  private Map<String, List<QuestionTemplateEntity>> loadQuestionTemplates(
      Map<String, CapabilityAtomDefinitionEntity> atoms
  ) {
    if (atoms.isEmpty()) {
      return Map.of();
    }
    List<Long> atomIds = atoms.values().stream()
        .map(CapabilityAtomDefinitionEntity::getId).distinct().toList();
    Map<Long, String> keyById = atoms.entrySet().stream()
        .collect(Collectors.toMap(entry -> entry.getValue().getId(), Map.Entry::getKey));
    return questionTemplateMapper.selectList(Wrappers.<QuestionTemplateEntity>lambdaQuery()
            .in(QuestionTemplateEntity::getAtomDefinitionId, atomIds)
            .eq(QuestionTemplateEntity::getStatus, CatalogStatus.PUBLISHED))
        .stream()
        .collect(Collectors.groupingBy(
            template -> keyById.get(template.getAtomDefinitionId()),
            LinkedHashMap::new,
            Collectors.toList()));
  }

  private List<PlatformKnowledgeManifestEntity> loadPlatformKnowledge() {
    return platformKnowledgeMapper.selectList(
        Wrappers.<PlatformKnowledgeManifestEntity>lambdaQuery()
            .eq(PlatformKnowledgeManifestEntity::getStatus, CatalogStatus.PUBLISHED));
  }

  private Map<String, GithubEvidenceCardDTO> loadGithubCards(
      PreparationRunEntity run,
      List<JobCapabilityMappingEntity> mappings,
      GithubRepositoryEntity github,
      List<String> degraded,
      Map<String, String> dependencies
  ) {
    if (github == null) {
      if (run.getGithubRepositoryId() == null) {
        dependencies.put("GITHUB", "NOT_SELECTED");
      } else {
        dependencies.put("GITHUB", "DEGRADED");
        degraded.add("GITHUB_BINDING_UNAVAILABLE");
      }
      return Map.of();
    }
    try {
      List<CapabilityTarget> targets = mappings.stream()
          .map(mapping -> new CapabilityTarget(
              mapping.getAtomId(), mapping.getAtomVersion(), mapping.getCapabilityName(), List.of()))
          .toList();
      List<GithubEvidenceCardDTO> cards = githubEvidenceCardService.generate(
          run.getUserId(), github.getId(), new GithubEvidenceCardRequest(targets, 2));
      dependencies.put("GITHUB", "READY:" + github.getFixedCommitSha());
      return cards.stream().collect(Collectors.toMap(
          card -> card.atomId() + "@" + card.atomVersion(),
          Function.identity(),
          (left, right) -> left));
    } catch (Exception e) {
      degraded.add("GITHUB_EVIDENCE_UNAVAILABLE");
      dependencies.put("GITHUB", "DEGRADED");
      log.warn("GitHub 证据卡不可用，准备任务降级到 JD + PLATFORM: runId={}", run.getRunId(), e);
      return Map.of();
    }
  }

  private EvidencePacket buildEvidencePacket(
      PreparationRunEntity run,
      JobDescriptionEntity job,
      JobCapabilityMappingEntity mapping,
      CapabilityAtomDefinitionEntity atom,
      List<PlatformKnowledgeManifestEntity> platformKnowledge,
      ResumeEntity resume,
      GithubRepositoryEntity github,
      List<KnowledgeBaseEntity> knowledgeBases,
      GithubEvidenceCardDTO githubCard,
      List<String> degraded
  ) {
    String query = mapping.getCapabilityName() + " "
        + (atom == null ? "" : atom.getDescription()) + " "
        + nullToEmpty(mapping.getEvidenceText());
    List<EvidenceCandidate> direct = new ArrayList<>();
    direct.add(jdCandidate(job, mapping, 1));
    addPlatformCandidates(direct, mapping, platformKnowledge);
    if (resume != null && mapping.getCapabilityName().contains("项目")) {
      direct.add(resumeCandidate(resume, direct.size() + 1));
    }

    List<EvidenceCandidate> retrieved = List.of();
    List<String> packetDegraded = new ArrayList<>();
    try {
      EvidencePacket packet = evidenceRetrievalService.prepareEvidence(
          evidenceScope(run, job, platformKnowledge, github, knowledgeBases),
          key(mapping), query);
      retrieved = packet.candidates();
      packetDegraded.addAll(packet.degradedReasons());
    } catch (Exception e) {
      packetDegraded.add("EVIDENCE_RETRIEVAL_UNAVAILABLE");
      log.warn("准备证据检索失败，保留 JD/审核资料快照: runId={}, atom={}",
          run.getRunId(), mapping.getAtomId(), e);
    }
    degraded.addAll(packetDegraded);
    Map<String, EvidenceCandidate> unique = new LinkedHashMap<>();
    direct.forEach(candidate -> unique.put(candidate.ref().evidenceId(), candidate));
    addGithubCandidates(unique, githubCard);
    retrieved.forEach(candidate -> unique.putIfAbsent(candidate.ref().evidenceId(), candidate));
    List<EvidenceCandidate> candidates = unique.values().stream()
        .limit(MAX_EVIDENCE_PER_PACKET)
        .toList();
    EvidenceStatus status = candidates.size() > 1
        ? EvidenceStatus.SUFFICIENT : EvidenceStatus.WEAK;
    return new EvidencePacket(
        key(mapping), query, status, candidates, List.of(), packetDegraded);
  }

  /**
   * 将 GitHub 证据卡的固定 SHA 引用并入题目专属快照。
   *
   * <p>卡片只携带经过确定性筛选的引用，正文在评价时由 GithubEvidenceReader 按 evidenceId
   * 复核；因此这里不会把未经校验的整仓源码直接拼进题面快照。
   */
  static void addGithubCandidates(
      Map<String, EvidenceCandidate> candidates,
      GithubEvidenceCardDTO githubCard
  ) {
    if (githubCard == null || githubCard.evidenceRefs().isEmpty()) {
      return;
    }
    for (var ref : githubCard.evidenceRefs()) {
      candidates.putIfAbsent(
          ref.evidenceId(),
          new EvidenceCandidate(
              ref,
              "GitHub 固定 SHA 代码证据：" + ref.sourceLocator(),
              null,
              null,
              1.3d,
              candidates.size() + 1));
    }
  }

  private EvidenceScope evidenceScope(
      PreparationRunEntity run,
      JobDescriptionEntity job,
      List<PlatformKnowledgeManifestEntity> platformKnowledge,
      GithubRepositoryEntity github,
      List<KnowledgeBaseEntity> knowledgeBases
  ) {
    List<EvidenceScope.DomainScope> domains = new ArrayList<>();
    domains.add(new EvidenceScope.DomainScope(
        DataDomain.JOB,
        Set.of(String.valueOf(job.getId())),
        Set.of(String.valueOf(job.getVersion())),
        1.4d));
    Set<String> platformResources = platformKnowledge.stream()
        .map(PlatformKnowledgeManifestEntity::getResourceId).collect(Collectors.toSet());
    if (!platformResources.isEmpty()) {
      domains.add(new EvidenceScope.DomainScope(
          DataDomain.PLATFORM, platformResources, Set.of(), 1.2d));
    }
    if (Boolean.TRUE.equals(run.getIncludePersonalMaterials()) && !knowledgeBases.isEmpty()) {
      domains.add(new EvidenceScope.DomainScope(
          DataDomain.CANDIDATE,
          knowledgeBases.stream().map(kb -> String.valueOf(kb.getId())).collect(Collectors.toSet()),
          knowledgeBases.stream().map(kb -> String.valueOf(kb.getCurrentVersionId()))
              .collect(Collectors.toSet()),
          1.0d));
    }
    if (github != null) {
      domains.add(new EvidenceScope.DomainScope(
          DataDomain.GITHUB,
          Set.of(GithubEvidenceIndexer.resourceId(github.getId())),
          Set.of(github.getFixedCommitSha()),
          1.3d));
    }
    return new EvidenceScope(
        run.getUserId(), domains, Boolean.TRUE.equals(run.getIncludePersonalMaterials()));
  }

  private EvidenceCandidate jdCandidate(
      JobDescriptionEntity job,
      JobCapabilityMappingEntity mapping,
      int rank
  ) {
    String locator = "jd:" + job.getId() + "#chars="
        + value(mapping.getEvidenceStart()) + "-" + value(mapping.getEvidenceEnd());
    EvidenceRef ref = new EvidenceRef(
        "job:" + job.getId() + ":" + mapping.getAtomId() + ":" + mapping.getAtomVersion(),
        DataDomain.JOB,
        String.valueOf(job.getId()),
        String.valueOf(job.getVersion()),
        "JOB_DESCRIPTION_SPAN",
        locator,
        job.getContentHash());
    String text = mapping.getEvidenceText() == null
        ? "JD 已确认能力：" + mapping.getCapabilityName() : mapping.getEvidenceText();
    return new EvidenceCandidate(ref, text, null, null, 1.4d, rank);
  }

  private void addPlatformCandidates(
      List<EvidenceCandidate> result,
      JobCapabilityMappingEntity mapping,
      List<PlatformKnowledgeManifestEntity> manifests
  ) {
    for (PlatformKnowledgeManifestEntity manifest : manifests) {
      List<String> atomIds = readJson(
          manifest.getCapabilityAtomIdsJson(), new TypeReference<List<String>>() {}, List.of());
      if (!atomIds.contains(mapping.getAtomId())) {
        continue;
      }
      EvidenceRef ref = new EvidenceRef(
          manifest.getEvidenceId(), DataDomain.PLATFORM, manifest.getResourceId(),
          manifest.getResourceVersion(), manifest.getSourceType(), manifest.getSourceLocator(),
          manifest.getContentHash());
      result.add(new EvidenceCandidate(
          ref, manifest.getSummary(), null, null, 1.2d, result.size() + 1));
    }
  }

  private EvidenceCandidate resumeCandidate(ResumeEntity resume, int rank) {
    String hash = resume.getFileHash();
    if (hash == null || hash.isBlank()) {
      hash = fileHashService.calculateHash(
          nullToEmpty(resume.getResumeText()).getBytes(StandardCharsets.UTF_8));
    }
    EvidenceRef ref = new EvidenceRef(
        "resume:" + resume.getId() + ":snapshot",
        DataDomain.CANDIDATE,
        "resume:" + resume.getId(),
        hash.substring(0, Math.min(16, hash.length())),
        "RESUME",
        "resume:" + resume.getId(),
        hash);
    String text = nullToEmpty(resume.getResumeText());
    if (text.length() > 1000) {
      text = text.substring(0, 1000);
    }
    return new EvidenceCandidate(ref, text, null, null, 1.0d, rank);
  }

  private List<QuestionDraft> selectQuestionDrafts(
      JobDescriptionEntity job,
      List<JobCapabilityMappingEntity> mappings,
      Map<String, List<QuestionTemplateEntity>> templates,
      Map<String, EvidencePacket> packets,
      Map<String, String> snapshotIds,
      Map<String, GithubEvidenceCardDTO> githubCards
  ) {
    List<QuestionDraft> result = new ArrayList<>();
    addStageQuestions(
        result, JobInterviewStage.PROJECT_DEEP_DIVE, 2,
        job, mappings, templates, packets, snapshotIds, githubCards);
    addStageQuestions(
        result, JobInterviewStage.POSITION_TECH, 2,
        job, mappings, templates, packets, snapshotIds, githubCards);
    addStageQuestions(
        result, JobInterviewStage.ENGINEERING_SCENARIO, 1,
        job, mappings, templates, packets, snapshotIds, githubCards);
    return result;
  }

  private void addStageQuestions(
      List<QuestionDraft> result,
      JobInterviewStage stage,
      int count,
      JobDescriptionEntity job,
      List<JobCapabilityMappingEntity> mappings,
      Map<String, List<QuestionTemplateEntity>> templates,
      Map<String, EvidencePacket> packets,
      Map<String, String> snapshotIds,
      Map<String, GithubEvidenceCardDTO> githubCards
  ) {
    Set<String> alreadyUsed = result.stream().map(QuestionDraft::capabilityAtomId)
        .collect(Collectors.toCollection(LinkedHashSet::new));
    List<JobCapabilityMappingEntity> candidates = new ArrayList<>(mappings);
    candidates.sort(Comparator
        .comparing(
            (JobCapabilityMappingEntity mapping) -> stagePriority(
                mapping, stage, templates),
            Comparator.reverseOrder())
        .thenComparing(
            mapping -> Optional.ofNullable(mapping.getConfirmedWeight())
                .orElse(mapping.getSuggestedWeight()),
            Comparator.reverseOrder()));
    int added = 0;
    for (JobCapabilityMappingEntity mapping : candidates) {
      if (added >= count) {
        break;
      }
      QuestionTemplateEntity template = templates.getOrDefault(key(mapping), List.of()).stream()
          .filter(item -> stage.name().equals(item.getStage()))
          .findFirst()
          .orElse(null);
      if (template == null && alreadyUsed.contains(mapping.getAtomId())) {
        continue;
      }
      EvidencePacket packet = packets.get(key(mapping));
      GithubEvidenceCardDTO githubCard = githubCards.get(key(mapping));
      String question = questionText(mapping, stage, added, githubCard);
      result.add(new QuestionDraft(
          stage,
          stage == JobInterviewStage.PROJECT_DEEP_DIVE ? "PROJECT" : "TECHNICAL",
          question,
          mapping.getAtomId(),
          mapping.getAtomVersion(),
          template == null ? "JD_" + stage.name() : template.getQuestionCode(),
          template == null ? "jd-" + job.getVersion() : template.getVersion(),
          template == null ? RUBRIC_CODE : template.getRubricCode(),
          template == null ? RUBRIC_VERSION : template.getRubricVersion(),
          snapshotIds.get(key(mapping)),
          packet == null ? List.of() : packet.evidenceRefs().stream()
              .map(EvidenceRef::evidenceId).toList(),
          packet == null ? EvidenceStatus.NONE : packet.status()));
      alreadyUsed.add(mapping.getAtomId());
      added++;
    }
    while (added < count) {
      JobCapabilityMappingEntity fallback = candidates.get(added % candidates.size());
      EvidencePacket packet = packets.get(key(fallback));
      result.add(new QuestionDraft(
          stage, "TECHNICAL",
          questionText(fallback, stage, added, githubCards.get(key(fallback))),
          fallback.getAtomId(), fallback.getAtomVersion(), "JD_" + stage.name(),
          "jd-" + job.getVersion(), RUBRIC_CODE, RUBRIC_VERSION,
          snapshotIds.get(key(fallback)),
          packet == null ? List.of() : packet.evidenceRefs().stream()
              .map(EvidenceRef::evidenceId).toList(),
          packet == null ? EvidenceStatus.NONE : packet.status()));
      added++;
    }
  }

  private boolean hasTemplateForStage(
      JobCapabilityMappingEntity mapping,
      JobInterviewStage stage,
      Map<String, List<QuestionTemplateEntity>> templates
  ) {
    return templates.getOrDefault(key(mapping), List.of()).stream()
        .anyMatch(template -> stage.name().equals(template.getStage()));
  }

  private int stagePriority(
      JobCapabilityMappingEntity mapping,
      JobInterviewStage stage,
      Map<String, List<QuestionTemplateEntity>> templates
  ) {
    if (stage == JobInterviewStage.PROJECT_DEEP_DIVE
        && "PROJECT_TROUBLESHOOTING".equals(mapping.getAtomId())) {
      return 2;
    }
    return hasTemplateForStage(mapping, stage, templates) ? 1 : 0;
  }

  static String questionText(
      JobCapabilityMappingEntity mapping,
      JobInterviewStage stage,
      int stageQuestionIndex,
      GithubEvidenceCardDTO githubCard
  ) {
    if (stage == JobInterviewStage.PROJECT_DEEP_DIVE && stageQuestionIndex == 0) {
      return "结合你目前的学习或实习经历，选一个你参与最深、与应聘方向最相关的项目，"
          + "介绍项目背景、整体架构、团队分工和你负责的部分。";
    }
    if (stage == JobInterviewStage.PROJECT_DEEP_DIVE
        && githubCard != null && githubCard.interviewQuestion() != null
        && !githubCard.interviewQuestion().isBlank()) {
      return githubCard.interviewQuestion();
    }
    String atomId = mapping.getAtomId();
    return switch (stage) {
      case PROJECT_DEEP_DIVE -> projectQuestion(atomId, mapping.getCapabilityName());
      case POSITION_TECH -> positionQuestion(atomId, mapping.getCapabilityName());
      case ENGINEERING_SCENARIO -> engineeringQuestion(atomId, mapping.getCapabilityName());
      case ALGORITHM -> "";
    };
  }

  private static String projectQuestion(String atomId, String capabilityName) {
    return switch (atomId) {
      case "SPRING_APPLICATION" ->
          "如果你负责过 Spring 业务接口，选一个从 Controller 开始说完整调用链和事务边界；"
              + "如果没有，可以用熟悉的接口设计说明。";
      case "RAG_DOCUMENT_PIPELINE" ->
          "如果刚才介绍的项目包含 RAG，文档从上传到可检索经过哪些组件，其中哪一段由你负责？"
              + "如果没有，请说说你会如何设计这条链路。";
      case "PROJECT_TROUBLESHOOTING" ->
          "如果你亲自排查过项目问题，选一个说明现象、定位过程和最终根因；"
              + "如果没有，请结合一个可能的故障场景说明排查顺序。";
      default -> "如果项目中有和“" + capabilityName
          + "”相关的模块，请说说它解决什么问题、你负责什么；如果没有，请说明你的设计思路。";
    };
  }

  private static String positionQuestion(String atomId, String capabilityName) {
    return switch (atomId) {
      case "JAVA_LANGUAGE_FOUNDATION" ->
          "项目里有没有真实使用 Java 并发的场景？选一个说说为什么需要并发，以及如何保证线程安全。";
      case "SPRING_APPLICATION" ->
          "项目里是否使用过 Spring 事务？如果使用过，请说明在哪一层开启、哪些异常会触发回滚；"
              + "如果没有，请按你理解的实现方式回答。";
      case "DATABASE_TRANSACTION" ->
          "你有没有通过冗余字段或索引优化过查询？具体改了哪张表、哪些字段，数据一致性怎么保证？";
      case "CACHE_DISTRIBUTED" ->
          "项目里是否使用缓存？如果使用过，请说明缓存模式，以及一次写请求中数据库和缓存的"
              + "更新顺序；如果没有，请按常见业务场景说明你的选择。";
      case "MESSAGE_RELIABILITY" ->
          "项目里是否使用消息队列？如果使用过，请说明消息从生产到消费的步骤，以及生产确认、"
              + "消费确认和业务幂等；如果没有，请按常见业务场景回答。";
      case "BACKEND_SYSTEM_DESIGN" ->
          "选一个你熟悉的核心接口，说说它的请求流程、数据模型和主要性能瓶颈。";
      case "PROJECT_TROUBLESHOOTING" ->
          "如果实际遇到过较难定位的线上或测试环境问题，可以结合案例说明先看了哪些信息；"
              + "如果没有，请说明你面对这类问题时会如何排查。";
      case "RAG_DOCUMENT_PIPELINE" ->
          "如果你做过 RAG，请说明文档从上传到可检索依次经过哪些处理、失败后如何恢复；"
              + "如果没有，请按你的设计思路回答。";
      case "RAG_RETRIEVAL" ->
          "如果你做过 RAG 检索，请说明是自研还是基于框架、用了哪些召回方式、重排放在哪一步；"
              + "如果没有，请按你会采用的方案说明理由。";
      case "RAG_EVALUATION" ->
          "如果你做过 RAG 评测，请说明如何判断优化有效、用过哪些数据集和指标；"
              + "如果没有，请说明你会如何设计评测并分析坏例。";
      case "AGENT_ORCHESTRATION" ->
          "如果项目中做过 Agent，它具体解决什么业务问题？请按一次真实请求说明从接收任务到返回"
              + "结果的步骤；如果没有，请结合熟悉的场景说明设计。";
      case "LLM_APPLICATION_ENGINEERING" ->
          "如果项目中接入过模型，请说明返回内容不符合预期格式时如何校验、重试和降级；"
              + "如果没有，请按你的工程方案回答。";
      case "AI_APPLICATION_RELIABILITY" ->
          "如果项目中接入过模型或外部解析服务，请说明超时后请求和后台任务分别会变成什么状态；"
              + "如果没有，请说明你会如何设计。";
      default -> "项目中是否涉及“" + capabilityName
          + "”？如果有，请说一个具体实现；如果没有，请说明你的设计思路。";
    };
  }

  private static String engineeringQuestion(String atomId, String capabilityName) {
    return switch (atomId) {
      case "DATABASE_TRANSACTION" ->
          "如果主表更新成功、明细表更新失败，你会怎么保证两边数据一致？事务回滚由谁触发？";
      case "CACHE_DISTRIBUTED" ->
          "如果数据库更新成功但删除缓存失败，会出现什么问题？你会怎么让数据最终收敛？";
      case "MESSAGE_RELIABILITY" ->
          "如果消费者处理成功、还没确认消息就宕机，消息恢复后会发生什么？业务如何避免重复影响？";
      case "RAG_RETRIEVAL" ->
          "如果混合检索返回的结果互相冲突，或者没有足够证据，你们会怎么处理？";
      case "LLM_APPLICATION_ENGINEERING" ->
          "如果知识库文档里出现了要求模型忽略系统规则的内容，你们如何限制它影响工具调用？";
      case "AI_APPLICATION_RELIABILITY" ->
          "如果模型服务连续超时，怎样保证任务不重复处理、用户能看到真实状态，并且之后可以恢复？";
      case "BACKEND_SYSTEM_DESIGN" ->
          "如果这个核心接口的请求量突然增加十倍，你会先看哪些指标，再从哪里开始改？";
      default -> "如果“" + capabilityName
          + "”相关链路在线上失败，你会先看什么现象，如何定位并恢复？";
    };
  }

  private AlgorithmPreparationPort.Reservation reserveAlgorithm(
      PreparationRunEntity run,
      JobDescriptionEntity job,
      List<String> degraded,
      Map<String, String> dependencies
  ) {
    AlgorithmPreparationPort port = algorithmPortProvider.getIfAvailable();
    AlgorithmPreparationPort.Reservation reservation;
    try {
      reservation = port == null
          ? AlgorithmPreparationPort.Reservation.unavailable("JUDGE0_NOT_CONFIGURED")
          : port.reserve(run.getUserId(), job.getId(), run.getCodingLanguage());
    } catch (Exception e) {
      reservation = AlgorithmPreparationPort.Reservation.unavailable("ALGORITHM_SERVICE_UNAVAILABLE");
      log.warn("算法题预留失败，岗位实战保留代码草稿并标记事后补判: runId={}", run.getRunId(), e);
    }
    if (!reservation.available() || !reservation.judgeAvailable()) {
      if (reservation.degradedReason() != null && !reservation.degradedReason().isBlank()) {
        degraded.add(reservation.degradedReason());
      }
      dependencies.put("ALGORITHM_JUDGE", "DEGRADED");
    } else {
      dependencies.put("ALGORITHM_JUDGE", "READY");
    }
    return reservation;
  }

  private List<PlannedQuestion> finalizeQuestions(List<QuestionDraft> drafts) {
    Map<JobInterviewStage, Long> countByStage = drafts.stream()
        .collect(Collectors.groupingBy(QuestionDraft::stage, Collectors.counting()));
    List<PlannedQuestion> result = new ArrayList<>();
    int index = 0;
    for (QuestionDraft draft : drafts) {
      int count = Math.toIntExact(countByStage.get(draft.stage()));
      int budget = Math.toIntExact(draft.stage().budget().toSeconds()) / Math.max(count, 1);
      result.add(new PlannedQuestion(
          index, (index + 1) * 100, draft.stage(), draft.questionType(), draft.question(),
          draft.capabilityAtomId(), draft.capabilityAtomVersion(),
          draft.questionTemplateCode(), draft.questionTemplateVersion(), draft.rubricCode(),
          draft.rubricVersion(), draft.evidenceSnapshotId(), draft.evidenceIds(),
          draft.evidenceStatus(), budget));
      index++;
    }
    return List.copyOf(result);
  }

  private InterviewTopic toTopic(
      JobDescriptionEntity job,
      List<JobCapabilityMappingEntity> mappings
  ) {
    List<Category> categories = mappings.stream()
        .map(mapping -> new Category(
            mapping.getAtomId(), mapping.getCapabilityName(), "CORE", mapping.getAtomVersion()))
        .toList();
    return new InterviewTopic(
        InterviewTopicCatalog.CUSTOM_TOPIC_ID,
        job.getTitle(),
        "冻结 JD 的版本化能力范围",
        categories,
        false,
        job.getJdText(),
        job.getTemplateCode(),
        job.getTemplateVersion());
  }

  private String key(JobCapabilityMappingEntity mapping) {
    return mapping.getAtomId() + "@" + mapping.getAtomVersion();
  }

  private int value(Integer value) {
    return value == null ? 0 : value;
  }

  private String nullToEmpty(String value) {
    return value == null ? "" : value;
  }

  private String writeJson(Object value) {
    try {
      return objectMapper.writeValueAsString(value);
    } catch (Exception e) {
      throw new IllegalStateException("序列化岗位实战计划失败", e);
    }
  }

  private <T> T readJson(String json, TypeReference<T> type, T fallback) {
    if (json == null || json.isBlank()) {
      return fallback;
    }
    try {
      return objectMapper.readValue(json, type);
    } catch (Exception e) {
      return fallback;
    }
  }

  public record PreparedPlan(
      JobInterviewPlanSnapshot snapshot,
      InterviewPlan orchestrationPlan,
      Map<String, String> dependencyStatus
  ) {
  }

  private record QuestionDraft(
      JobInterviewStage stage,
      String questionType,
      String question,
      String capabilityAtomId,
      String capabilityAtomVersion,
      String questionTemplateCode,
      String questionTemplateVersion,
      String rubricCode,
      String rubricVersion,
      String evidenceSnapshotId,
      List<String> evidenceIds,
      EvidenceStatus evidenceStatus
  ) {
  }
}

