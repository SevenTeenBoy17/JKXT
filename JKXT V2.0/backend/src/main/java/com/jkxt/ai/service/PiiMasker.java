package com.jkxt.ai.service;

import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

@Component
public class PiiMasker {

  private static final Pattern EMAIL = Pattern.compile("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}");
  private static final Pattern PHONE = Pattern.compile("(?<!\\d)1[3-9]\\d{9}(?!\\d)");
  private static final Pattern ID_CARD = Pattern.compile("(?<!\\d)(\\d{6})(\\d{8,11})([0-9Xx])(?!\\d)");
  private static final Pattern CHINESE_TEACHER_NAME = Pattern.compile("([\\u4e00-\\u9fa5])([\\u4e00-\\u9fa5]{1,3})(老师|主任|同学)");

  public String maskText(String raw) {
    if (raw == null || raw.isBlank()) {
      return "";
    }
    String masked = EMAIL.matcher(raw).replaceAll("***@***");
    masked = PHONE.matcher(masked).replaceAll("1**********");
    masked = ID_CARD.matcher(masked).replaceAll("$1********$3");
    masked = CHINESE_TEACHER_NAME.matcher(masked).replaceAll("$1**$3");
    return masked;
  }
}
