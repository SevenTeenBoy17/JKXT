package com.jkxt.importer;

import static org.assertj.core.api.Assertions.assertThat;

import com.jkxt.importer.api.ImportDtos.ValidationItem;
import com.jkxt.importer.api.ImportDtos.ValidationReport;
import com.jkxt.importer.service.ImportValidationService;
import java.io.ByteArrayOutputStream;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

/**
 * K12 全学段测试发现的 P1 回归：**同一考场在重叠时段被多场考试占用**此前完全不被校验。
 *
 * <p>实测缺陷（jkxt-private 实盘）：一份「A101 同日 08:30-10:00 同时排三年级语文与五年级英语」的考试表
 * 上传后返回 {@code severity=pass, error=0, completeness=100}，落库后求解器把每个 (exam,room) 当独立槽位
 * 各派 2 名监考 → 排出"一个教室同一时刻坐两批学生考两门课"的**物理上不可执行**方案，且状态是 succeeded。
 *
 * <p>逐行校验天然看不到其它行，故修复在 {@code validate()} 里累积 (考场,日期,时段) 占用表后统一判定；
 * 判为 error 而非 warn，因为两条落库路径（ImportCommitService.commit / ExamTaskService.upload）都只以
 * {@code counts().error() == 0} 为闸门。
 *
 * <p>这些用例同时锁住**不得误挡合法数据**：同考场相邻不重叠的连续场次（上午语文、下午数学）是真实学校常态。
 */
class ExamRoomOccupancyValidationTest {

  private static final String[] HEADER = {
      "考试科目", "年级", "考试日期", "开始时间", "结束时间", "考场编号", "人数", "监考人数"
  };

  private final ImportValidationService service = new ImportValidationService();

  @Test
  void sameRoomOverlappingSlotsAreRejected() throws Exception {
    // 缺陷复现：A101 在同一天同一时段被两场不同考试占用。
    ValidationReport report = validate(new String[][] {
        {"语文", "三年级", "2026-06-25", "08:30", "10:00", "A101", "40", "2"},
        {"英语", "五年级", "2026-06-25", "08:30", "10:00", "A101", "40", "2"},
    });

    assertThat(report.counts().error()).isGreaterThan(0);
    assertThat(report.severity()).isEqualTo("error");
    assertThat(codes(report)).contains("EXAM_ROOM_TIME_CONFLICT");
  }

  @Test
  void partiallyOverlappingSlotsInSameRoomAreRejected() throws Exception {
    // 部分重叠同样不可执行（第二场 09:30 开始时第一场还在进行）。
    ValidationReport report = validate(new String[][] {
        {"语文", "三年级", "2026-06-25", "08:30", "10:00", "A101", "40", "2"},
        {"数学", "四年级", "2026-06-25", "09:30", "11:00", "A101", "40", "2"},
    });

    assertThat(codes(report)).contains("EXAM_ROOM_TIME_CONFLICT");
  }

  @Test
  void duplicateRowsInSameRoomAndSlotAreRejectedAsDuplicate() throws Exception {
    // 同科目同年级同考场同时段 = 重复行（落库会变成两个 examId 被重复配监考）。
    ValidationReport report = validate(new String[][] {
        {"语文", "三年级", "2026-06-25", "08:30", "10:00", "A101", "40", "2"},
        {"语文", "三年级", "2026-06-25", "08:30", "10:00", "A101", "40", "2"},
    });

    assertThat(codes(report)).contains("EXAM_ROOM_ROW_DUPLICATE");
  }

  @Test
  void backToBackSlotsInSameRoomArePermitted() throws Exception {
    // **不得误挡**：同一考场上午语文、下午数学是真实学校常态（半开区间不重叠）。
    ValidationReport report = validate(new String[][] {
        {"语文", "三年级", "2026-06-25", "08:30", "10:00", "A101", "40", "2"},
        {"数学", "三年级", "2026-06-25", "10:00", "11:30", "A101", "40", "2"},
        {"英语", "三年级", "2026-06-25", "14:30", "15:40", "A101", "40", "2"},
    });

    assertThat(codes(report)).doesNotContain("EXAM_ROOM_TIME_CONFLICT", "EXAM_ROOM_ROW_DUPLICATE");
    assertThat(report.counts().error()).isZero();
  }

  @Test
  void sameSlotInDifferentRoomsIsPermitted() throws Exception {
    // **不得误挡**：同一时段多个考场并行是排考的正常形态（新高考选科走班尤其如此）。
    ValidationReport report = validate(new String[][] {
        {"物理", "高二", "2026-01-13", "15:00", "16:15", "K101", "30", "2"},
        {"历史", "高二", "2026-01-13", "15:00", "16:15", "K102", "30", "2"},
    });

    assertThat(codes(report)).doesNotContain("EXAM_ROOM_TIME_CONFLICT");
    assertThat(report.counts().error()).isZero();
  }

  @Test
  void sameRoomSameSlotOnDifferentDatesIsPermitted() throws Exception {
    // **不得误挡**：跨天同考场同时段互不影响。
    ValidationReport report = validate(new String[][] {
        {"语文", "三年级", "2026-06-25", "08:30", "10:00", "A101", "40", "2"},
        {"数学", "三年级", "2026-06-26", "08:30", "10:00", "A101", "40", "2"},
    });

    assertThat(codes(report)).doesNotContain("EXAM_ROOM_TIME_CONFLICT");
    assertThat(report.counts().error()).isZero();
  }

  // ---------- helpers ----------

  private ValidationReport validate(String[][] rows) throws Exception {
    return service.validate("exams", new MockMultipartFile(
        "file", "exams.xlsx",
        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
        workbook(rows)));
  }

  private static java.util.List<String> codes(ValidationReport report) {
    return report.items().stream().map(ValidationItem::code).toList();
  }

  private static byte[] workbook(String[][] rows) throws Exception {
    try (Workbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
      Sheet sheet = wb.createSheet("考试表");
      Row header = sheet.createRow(0);
      for (int i = 0; i < HEADER.length; i++) {
        header.createCell(i).setCellValue(HEADER[i]);
      }
      for (int r = 0; r < rows.length; r++) {
        Row row = sheet.createRow(r + 1);
        for (int c = 0; c < rows[r].length; c++) {
          row.createCell(c).setCellValue(rows[r][c]);
        }
      }
      wb.write(out);
      return out.toByteArray();
    }
  }
}
