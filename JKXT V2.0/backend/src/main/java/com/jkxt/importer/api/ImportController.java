package com.jkxt.importer.api;

import com.jkxt.importer.api.ImportDtos.ValidationReport;
import com.jkxt.importer.service.ImportValidationService;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/v1/import")
public class ImportController {

  private static final MediaType XLSX = MediaType.parseMediaType(
      "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
  );

  private final ImportValidationService importValidationService;

  public ImportController(ImportValidationService importValidationService) {
    this.importValidationService = importValidationService;
  }

  @GetMapping("/templates/{kind}")
  public ResponseEntity<byte[]> template(@PathVariable String kind) {
    return templateResponse(kind);
  }

  @GetMapping("/{kind}/template")
  public ResponseEntity<byte[]> templateAlias(@PathVariable String kind) {
    return templateResponse(kind);
  }

  @PostMapping(value = "/validate", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  public ValidationReport validate(
      @RequestParam(defaultValue = "teachers") String kind,
      @RequestParam("file") MultipartFile file
  ) {
    return importValidationService.validate(kind, file);
  }

  @PostMapping(value = "/{kind}", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  public ValidationReport uploadAndValidate(@PathVariable String kind, @RequestParam("file") MultipartFile file) {
    return importValidationService.validate(kind, file);
  }

  @PostMapping(value = "/{kind}/validate", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  public ValidationReport validateKind(@PathVariable String kind, @RequestParam("file") MultipartFile file) {
    return importValidationService.validate(kind, file);
  }

  private ResponseEntity<byte[]> templateResponse(String kind) {
    byte[] body = importValidationService.template(kind);
    return ResponseEntity.ok()
        .contentType(XLSX)
        .header(
            HttpHeaders.CONTENT_DISPOSITION,
            ContentDisposition.attachment()
                .filename(importValidationService.templateFilename(kind))
                .build()
                .toString()
        )
        .body(body);
  }
}
