package com.jkxt.tasks.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import com.jkxt.common.tenant.TenantContext;
import com.jkxt.common.web.ApiException;
import com.jkxt.importer.repo.ImportBatchRepository;
import com.jkxt.importer.repo.UploadedFileRepository;
import com.jkxt.importer.service.ImportCommitService;
import com.jkxt.importer.service.ImportValidationService;
import com.jkxt.masterdata.repo.ClassroomRepository;
import com.jkxt.masterdata.repo.TeacherRepository;
import com.jkxt.scheduling.repo.ScheduleAssignmentRepository;
import com.jkxt.scheduling.repo.ScheduleRunRepository;
import com.jkxt.scheduling.service.ScheduleJobService;
import com.jkxt.tasks.domain.ExamTask;
import com.jkxt.tasks.repo.ExamRepository;
import com.jkxt.tasks.repo.ExamTaskRepository;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.jdbc.core.JdbcTemplate;

class ExamTaskMutationGuardTest {
  @AfterEach void clearTenant() { TenantContext.clear(); }

  @ParameterizedTest
  @CsvSource({"published,validate", "scheduling,validate", "published,upload", "scheduling,upload"})
  void mutationsRejectPublishedAndRunningTasksBeforeReadingOrWritingInputs(String status, String operation) {
    UUID org = UUID.randomUUID(), id = UUID.randomUUID();
    TenantContext.set(org);
    ExamTask task = new ExamTask();
    task.setId(id); task.setOrgId(org); task.setStatus(status);
    Instant publication = "published".equals(status) ? Instant.parse("2026-09-01T00:00:00Z") : null;
    task.setPublishedAt(publication);
    ExamTaskRepository tasks = mock(ExamTaskRepository.class);
    when(tasks.findByOrgIdAndId(org, id)).thenReturn(Optional.of(task));
    when(tasks.findByOrgIdAndIdForUpdate(org, id)).thenReturn(Optional.of(task));
    UploadedFileRepository files = mock(UploadedFileRepository.class);
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    ExamTaskService service = new ExamTaskService(tasks, files, mock(ImportBatchRepository.class),
        mock(ScheduleRunRepository.class), mock(ScheduleAssignmentRepository.class), mock(ExamRepository.class),
        mock(TeacherRepository.class), mock(ClassroomRepository.class), mock(ImportValidationService.class),
        mock(ImportCommitService.class), mock(ScheduleJobService.class), jdbc);
    assertThatThrownBy(() -> {
      if ("upload".equals(operation)) service.upload(id, "teacher", null, true);
      else service.validate(id);
    }).isInstanceOf(ApiException.class);
    assertThat(task.getStatus()).isEqualTo(status);
    assertThat(task.getPublishedAt()).isEqualTo(publication);
    verify(tasks, never()).save(any());
    verify(tasks).findByOrgIdAndIdForUpdate(org, id);
    verifyNoInteractions(files, jdbc);
  }
}
