package com.jkxt.masterdata.api;

import com.jkxt.masterdata.api.MasterDataDtos.TeacherDto;
import com.jkxt.masterdata.api.MasterDataDtos.TeacherRequest;
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
@RequestMapping("/api/v1/teachers")
public class TeacherController {

  private final MasterDataService masterDataService;

  public TeacherController(MasterDataService masterDataService) {
    this.masterDataService = masterDataService;
  }

  @GetMapping
  public List<TeacherDto> list(
      @RequestParam(required = false) String q,
      @RequestParam(required = false) String grade,
      @RequestParam(required = false) String subject,
      @RequestParam(required = false) Boolean isHomeroom,
      @RequestParam(required = false) String status
  ) {
    return masterDataService.listTeachers(q, grade, subject, isHomeroom, status);
  }

  @GetMapping("/{id}")
  public TeacherDto get(@PathVariable UUID id) {
    return masterDataService.getTeacher(id);
  }

  @PostMapping
  public TeacherDto create(@Valid @RequestBody TeacherRequest request) {
    return masterDataService.createTeacher(request);
  }

  @PutMapping("/{id}")
  public TeacherDto update(@PathVariable UUID id, @Valid @RequestBody TeacherRequest request) {
    return masterDataService.updateTeacher(id, request);
  }

  @DeleteMapping("/{id}")
  public ResponseEntity<Void> delete(@PathVariable UUID id) {
    masterDataService.deleteTeacher(id);
    return ResponseEntity.noContent().build();
  }
}
