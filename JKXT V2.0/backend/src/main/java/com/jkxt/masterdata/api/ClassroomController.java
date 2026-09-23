package com.jkxt.masterdata.api;

import com.jkxt.masterdata.api.MasterDataDtos.ClassroomDto;
import com.jkxt.masterdata.api.MasterDataDtos.ClassroomRequest;
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
@RequestMapping("/api/v1/classrooms")
public class ClassroomController {

  private final MasterDataService masterDataService;

  public ClassroomController(MasterDataService masterDataService) {
    this.masterDataService = masterDataService;
  }

  @GetMapping
  public List<ClassroomDto> list(
      @RequestParam(required = false) String q,
      @RequestParam(required = false) String status,
      @RequestParam(required = false) String roomType
  ) {
    return masterDataService.listClassrooms(q, status, roomType);
  }

  @GetMapping("/{id}")
  public ClassroomDto get(@PathVariable UUID id) {
    return masterDataService.getClassroom(id);
  }

  @PostMapping
  public ClassroomDto create(@Valid @RequestBody ClassroomRequest request) {
    return masterDataService.createClassroom(request);
  }

  @PutMapping("/{id}")
  public ClassroomDto update(@PathVariable UUID id, @Valid @RequestBody ClassroomRequest request) {
    return masterDataService.updateClassroom(id, request);
  }

  @DeleteMapping("/{id}")
  public ResponseEntity<Void> delete(@PathVariable UUID id) {
    masterDataService.deleteClassroom(id);
    return ResponseEntity.noContent().build();
  }
}
