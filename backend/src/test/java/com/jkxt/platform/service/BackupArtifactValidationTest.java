package com.jkxt.platform.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

class BackupArtifactValidationTest {
  @TempDir Path directory;

  private String verify(Path dump) {
    return ReflectionTestUtils.invokeMethod(mock(AdminPlatformService.class, CALLS_REAL_METHODS),
        "readChecksum", dump.toFile());
  }

  private Path dump(String text) throws Exception {
    return Files.writeString(directory.resolve("jkxt-test.sql"), text, StandardCharsets.UTF_8);
  }

  private String manifest(Path dump, String filename) throws Exception {
    String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(dump)));
    Files.writeString(Path.of(dump + ".sha256"), hash + "  " + filename + "\n");
    return hash;
  }

  @Test void validNonemptyBackupReturnsRecomputedHash() throws Exception {
    Path dump = dump("-- PostgreSQL database dump\nSELECT '中文';\n");
    assertEquals(manifest(dump, dump.getFileName().toString()), verify(dump));
  }

  @Test void missingChecksumIsNotACompletedBackup() throws Exception {
    assertNull(verify(dump("SELECT 1;")));
  }

  @Test void emptyBackupCannotBeRecordedAsSucceeded() throws Exception {
    Path dump = dump(""); manifest(dump, dump.getFileName().toString());
    assertNull(verify(dump));
  }

  @Test void changedContentIsRejected() throws Exception {
    Path dump = dump("SELECT 1;"); manifest(dump, dump.getFileName().toString());
    Files.writeString(dump, "SELECT 2;");
    assertNull(verify(dump));
  }

  @Test void anotherFilesManifestCannotVerifyThisArtifact() throws Exception {
    Path dump = dump("SELECT 1;"); manifest(dump, "other.sql");
    assertNull(verify(dump));
  }

  @Test void malformedOrMultipleManifestEntriesAreRejected() throws Exception {
    Path dump = dump("SELECT 1;");
    Files.writeString(Path.of(dump + ".sha256"), "not-a-checksum\n");
    assertNull(verify(dump));
    String hash = manifest(dump, dump.getFileName().toString());
    Files.writeString(Path.of(dump + ".sha256"), hash + "  jkxt-test.sql\n" + hash + "  other.sql\n");
    assertNull(verify(dump));
  }

  @Test void directoryMasqueradingAsSqlIsRejected() throws Exception {
    Path dump = Files.createDirectory(directory.resolve("directory.sql"));
    Files.writeString(Path.of(dump + ".sha256"), "0".repeat(64) + "  directory.sql\n");
    assertNull(verify(dump));
  }
}
