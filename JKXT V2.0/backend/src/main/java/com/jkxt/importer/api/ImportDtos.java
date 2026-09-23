package com.jkxt.importer.api;

import java.util.List;
import java.util.Map;

public final class ImportDtos {

  private ImportDtos() {
  }

  public record ValidationReport(
      String severity,
      String category,
      Overview overview,
      Counts counts,
      Map<String, Counts> cats,
      List<ValidationItem> items
  ) {
  }

  /** Result of persisting a validated import into the database. */
  public record CommitResult(
      String kind,
      int rows,
      int inserted,
      int updated,
      int skipped,
      List<String> warnings
  ) {
  }

  public record Overview(
      int completeness,
      int teachers,
      int rooms,
      int exams,
      int supervisions
  ) {
  }

  public record Counts(
      int pass,
      int suggest,
      int warn,
      int error
  ) {
  }

  public record ValidationItem(
      String id,
      String severity,
      String category,
      String source,
      String ref,
      Integer row,
      String column,
      String field,
      String status,
      String problem,
      String suggest,
      String rawValue,
      String code
  ) {
  }
}
