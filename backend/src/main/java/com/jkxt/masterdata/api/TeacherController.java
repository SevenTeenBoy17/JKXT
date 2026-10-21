package com.jkxt.masterdata.api;

import com.jkxt.masterdata.api.MasterDataDtos.TeacherDto;
import com.jkxt.masterdata.api.MasterDataDtos.TeacherRequest;
import com.jkxt.masterdata.service.MasterDataService;
import com.jkxt.common.web.ApiException;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
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
      @RequestParam(required = false) String status,
      @RequestParam(required = false) Integer page,
      @RequestParam(required = false) Integer size,
      @RequestParam(required = false) String sort,
      HttpServletResponse response
  ) {
    List<TeacherDto> all = masterDataService.listTeachers(q, grade, subject, isHomeroom, status);
    if (page == null && size == null && sort == null) {
      return all; // unchanged legacy contract: the full, teacherNo-ordered list
    }
    // PB-11 (round 9, DATA-06): page/size are honoured instead of silently ignored. The order is total
    // (sort key, then teacherNo, then id) so equal sort values come back identically on every call.
    int pageNo = page == null ? 0 : page;
    int pageSize = size == null ? DEFAULT_PAGE_SIZE : size;
    if (pageNo < 0 || pageSize < 1 || pageSize > MAX_PAGE_SIZE) {
      throw new ApiException(HttpStatus.BAD_REQUEST, "PAGINATION_INVALID",
          "page must be >= 0 and size between 1 and " + MAX_PAGE_SIZE);
    }
    Comparator<TeacherDto> tieBreak = Comparator
        .comparing(TeacherDto::teacherNo, Comparator.nullsLast(Comparator.naturalOrder()))
        .thenComparing(dto -> String.valueOf(dto.uuid()));
    Comparator<TeacherDto> order = switch (sort == null ? "teacherNo" : sort) {
      case "teacherNo" -> tieBreak;
      case "name" -> Comparator.comparing(TeacherDto::name, Comparator.nullsLast(Comparator.naturalOrder()))
          .thenComparing(tieBreak);
      default -> throw new ApiException(HttpStatus.BAD_REQUEST, "PAGINATION_INVALID",
          "sort must be one of: teacherNo, name");
    };
    response.setHeader("X-Total-Count", String.valueOf(all.size()));
    long from = (long) pageNo * pageSize;
    return all.stream().sorted(order).skip(from).limit(pageSize).toList();
  }

  static final int DEFAULT_PAGE_SIZE = 50;
  static final int MAX_PAGE_SIZE = 500;

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
