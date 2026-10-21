package com.jkxt.importer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jkxt.importer.service.ImportValidationService;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.Arrays;
import java.util.zip.Deflater;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.server.ResponseStatusException;

/**
 * Round 9, PB-12 (IMPORT-02): an inflate-ratio "zip bomb" xlsx (the system template plus a 120 MB
 * all-spaces part compressed to ~100 KB, the same fixture as the scenario matrix) is refused as an
 * unreadable upload - a blocking IMPORT_FILE_UNREADABLE on validate and a 400 on parse - never a 500.
 */
@SpringBootTest
@ActiveProfiles("test")
class HostileWorkbookRefusalTest {

  @Autowired
  private ImportValidationService validationService;

  @Test
  void inflateRatioBombIsAnUnreadableUploadNotAServerError() throws Exception {
    byte[] bomb = bomb(validationService.template("teachers"), 120);
    assertThat(bomb.length).isLessThan(2 * 1024 * 1024);
    MockMultipartFile file = new MockMultipartFile("file", "teachers.xlsx",
        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", bomb);

    var report = validationService.validate("teachers", file);
    assertThat(report.counts().error()).isGreaterThan(0);
    assertThat(report.items()).anySatisfy(item -> assertThat(item.code()).isEqualTo("IMPORT_FILE_UNREADABLE"));

    assertThatThrownBy(() -> validationService.parseRows("teachers", file))
        .isInstanceOfSatisfying(ResponseStatusException.class,
            e -> assertThat(e.getStatusCode().value()).isEqualTo(400));
  }

  @Test
  void ordinaryTemplateStillParses() {
    MockMultipartFile file = new MockMultipartFile("file", "teachers.xlsx",
        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", validationService.template("teachers"));
    assertThat(validationService.parseRows("teachers", file)).isNotNull();
  }

  private static byte[] bomb(byte[] template, int megabytes) throws Exception {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(template));
         ZipOutputStream zip = new ZipOutputStream(out)) {
      zip.setLevel(Deflater.BEST_COMPRESSION);
      for (ZipEntry entry; (entry = in.getNextEntry()) != null; ) {
        zip.putNextEntry(new ZipEntry(entry.getName()));
        in.transferTo(zip);
        zip.closeEntry();
      }
      zip.putNextEntry(new ZipEntry("xl/worksheets/sheet2.xml"));
      byte[] chunk = new byte[1024 * 1024];
      Arrays.fill(chunk, (byte) 0x20);
      for (int i = 0; i < megabytes; i++) zip.write(chunk);
      zip.closeEntry();
    }
    return out.toByteArray();
  }
}
