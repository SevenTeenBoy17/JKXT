package com.jkxt.masterdata.api;

import com.jkxt.masterdata.api.MasterDataDtos.SchoolClassDto;
import com.jkxt.masterdata.api.MasterDataDtos.SchoolClassRequest;
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
@RequestMapping("/api/v1/classes")
public class SchoolClassController {

  private final MasterDataService masterDataService;

  public SchoolClassController(MasterDataService masterDataService) {
    this.masterDataService = masterDataService;
  }

  @GetMapping
  public List<SchoolClassDto> list(
      @RequestParam(required = false) String q,
      @RequestParam(required = false) String grade,
      @RequestParam(required = false) String status
  ) {
    return masterDataService.listClasses(q, grade, status);
  }

  @GetMapping("/{id}")
  public SchoolClassDto get(@PathVariable UUID id) {
    return masterDataService.getClass(id);
  }

  @PostMapping
  public SchoolClassDto create(@Valid @RequestBody SchoolClassRequest request) {
    return masterDataService.createClass(request);
  }

  @PutMapping("/{id}")
  public SchoolClassDto update(@PathVariable UUID id, @Valid @RequestBody SchoolClassRequest request) {
    return masterDataService.updateClass(id, request);
  }

  @DeleteMapping("/{id}")
  public ResponseEntity<Void> delete(@PathVariable UUID id) {
    masterDataService.deleteClass(id);
    return ResponseEntity.noContent().build();
  }
}
