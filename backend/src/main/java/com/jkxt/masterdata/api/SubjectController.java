package com.jkxt.masterdata.api;

import com.jkxt.masterdata.api.MasterDataDtos.SubjectDto;
import com.jkxt.masterdata.api.MasterDataDtos.SubjectRequest;
import com.jkxt.masterdata.service.MasterDataService;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@RequestMapping("/api/v1/subjects")
public class SubjectController {

  private final MasterDataService masterDataService;

  public SubjectController(MasterDataService masterDataService) {
    this.masterDataService = masterDataService;
  }

  @GetMapping
  public List<SubjectDto> list(
      @RequestParam(required = false) String q,
      @RequestParam(required = false) String category,
      @RequestParam(required = false) String status
  ) {
    return masterDataService.listSubjects(q, category, status);
  }

  @GetMapping("/{id}")
  public SubjectDto get(@PathVariable UUID id) {
    return masterDataService.getSubject(id);
  }

  @PostMapping
  public SubjectDto create(@Valid @RequestBody SubjectRequest request) {
    return masterDataService.createSubject(request);
  }

  @PutMapping("/{id}")
  public SubjectDto update(@PathVariable UUID id, @Valid @RequestBody SubjectRequest request) {
    return masterDataService.updateSubject(id, request);
  }

  @DeleteMapping("/{id}")
  public ResponseEntity<Void> delete(@PathVariable UUID id) {
    masterDataService.deleteSubject(id);
    return ResponseEntity.noContent().build();
  }
}
