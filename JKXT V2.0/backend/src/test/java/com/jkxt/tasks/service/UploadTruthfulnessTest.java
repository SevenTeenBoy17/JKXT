package com.jkxt.tasks.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import com.jkxt.common.tenant.TenantContext;
import com.jkxt.common.web.ApiException;
import com.jkxt.importer.domain.UploadedFile;
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
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;

class UploadTruthfulnessTest {
  private static final UUID DEMO_ORG = UUID.fromString("00000000-0000-0000-0000-000000000001");
  private final ExamTaskRepository tasks = mock(ExamTaskRepository.class);
  private final UploadedFileRepository files = mock(UploadedFileRepository.class);
  private final ImportBatchRepository batches = mock(ImportBatchRepository.class);
  private final ImportValidationService validation = spy(new ImportValidationService());
  private final ImportCommitService commits = mock(ImportCommitService.class);
  private final ExamTask task = new ExamTask();
  private final ExamTaskService service = new ExamTaskService(tasks, files, batches,
      mock(ScheduleRunRepository.class), mock(ScheduleAssignmentRepository.class), mock(ExamRepository.class),
      mock(TeacherRepository.class), mock(ClassroomRepository.class), validation, commits,
      mock(ScheduleJobService.class), mock(JdbcTemplate.class),
      new com.jkxt.demo.DemoImportLimits(new com.jkxt.common.config.DeployProperties(),
          mock(com.jkxt.demo.WorkspaceFence.class),mock(com.jkxt.demo.DemoQuotaService.class)),
      mock(com.jkxt.demo.DemoScheduleExportSnapshotService.class),mock(com.jkxt.demo.DemoWorkspaceRepository.class));

  @AfterEach void clearTenant() { TenantContext.clear(); }

  @Test
  void missingAndEmptyFilesCannotGenerateOrCommitSamples() {
    for (UUID org : new UUID[] { UUID.randomUUID(), DEMO_ORG }) {
      prepare(org);
      for (MultipartFile file : new MultipartFile[] { null,
          new MockMultipartFile("file", "empty.xlsx", "application/octet-stream", new byte[0]) }) {
        assertThatThrownBy(() -> service.upload(task.getId(), "teacher", file, false))
            .isInstanceOfSatisfying(ApiException.class, error -> {
              assertThat(error.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
              assertThat(error.getCode()).isEqualTo("UPLOAD_FILE_REQUIRED");
            });
      }
    }
    verifyNoInteractions(files, batches, commits);
    verify(validation, never()).template(any());
    verify(tasks, never()).save(any());
    assertThat(task.getStatus()).isEqualTo("draft");
  }

  @Test
  void ordinaryTenantCannotSubmitExplicitSamples() {
    prepare(UUID.randomUUID());
    assertThatThrownBy(() -> service.upload(task.getId(), "teacher", null, true))
        .isInstanceOfSatisfying(ApiException.class, error -> {
          assertThat(error.getStatus()).isEqualTo(HttpStatus.FORBIDDEN);
          assertThat(error.getCode()).isEqualTo("SAMPLE_UPLOAD_FORBIDDEN");
        });
    verifyNoInteractions(files, batches, commits);
    verify(validation, never()).template(any());
    verify(tasks, never()).save(any());
  }

  @Test
  void explicitDemoSampleRemainsAvailableAndOrdinaryRealFilesStillWork() {
    prepare(DEMO_ORG);
    stubFileSave();
    assertThat(service.upload(task.getId(), "teacher", null, true).originalName()).contains("sample");
    verify(commits).commit(eq("teachers"), eq(null), any());

    prepare(UUID.randomUUID());
    byte[] workbook = new ImportValidationService().template("teachers");
    service.upload(task.getId(), "teacher",
        new MockMultipartFile("file", "actual.xlsx", "application/octet-stream", workbook), false);
    verify(commits, times(2)).commit(eq("teachers"), eq(null), any());
    verify(validation, times(1)).template("teachers");
  }

  private void prepare(UUID org) {
    TenantContext.set(org);
    task.setId(UUID.randomUUID());
    task.setOrgId(org);
    task.setStatus("draft");
    when(tasks.findByOrgIdAndIdForUpdate(org, task.getId())).thenReturn(Optional.of(task));
  }

  private void stubFileSave() {
    when(files.saveAndFlush(any())).thenAnswer(invocation -> {
      UploadedFile uploaded = invocation.getArgument(0);
      uploaded.setId(UUID.randomUUID());
      return uploaded;
    });
  }
}
