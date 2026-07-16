package com.linrun.interview.modules.capability;

import com.linrun.interview.common.result.Result;
import com.linrun.interview.common.security.UserContext;
import com.linrun.interview.modules.capability.dto.CapabilityTemplateDTO;
import com.linrun.interview.modules.capability.model.JobTrack;
import com.linrun.interview.modules.capability.service.CapabilityCatalogService;
import com.linrun.interview.modules.capability.service.ContentImportService;
import com.linrun.interview.modules.capability.service.ContentImportService.ImportReport;
import com.linrun.interview.modules.capability.service.CapabilityContentValidator.ValidationReport;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/capability-catalog")
@RequiredArgsConstructor
public class CapabilityCatalogController {

  private final CapabilityCatalogService catalogService;
  private final ContentImportService contentImportService;

  @GetMapping("/templates")
  public Result<List<CapabilityTemplateDTO>> listTemplates() {
    UserContext.requireUserId();
    return Result.success(catalogService.listPublishedTemplates());
  }

  @GetMapping("/templates/{jobTrack}")
  public Result<CapabilityTemplateDTO> getTemplate(@PathVariable JobTrack jobTrack) {
    UserContext.requireUserId();
    return Result.success(catalogService.getPublishedTemplate(jobTrack));
  }

  @PostMapping("/admin/validate")
  public Result<ValidationReport> validateContent() {
    UserContext.requireAdmin();
    return Result.success(contentImportService.validateClasspathCatalog());
  }

  @PostMapping("/admin/dry-run")
  public Result<ImportReport> dryRun() {
    UserContext.requireAdmin();
    return Result.success(contentImportService.dryRunClasspathCatalog());
  }

  @PostMapping("/admin/import")
  public Result<ImportReport> importContent() {
    UserContext.requireAdmin();
    return Result.success(contentImportService.importClasspathCatalog());
  }
}
