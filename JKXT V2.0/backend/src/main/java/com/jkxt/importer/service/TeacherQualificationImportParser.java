package com.jkxt.importer.service;

import java.time.LocalDate;
import java.util.LinkedHashSet;
import java.util.List;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.ss.usermodel.Workbook;

/** Pure spreadsheet interpretation shared by preview and the frozen import rows. */
public final class TeacherQualificationImportParser {
  private TeacherQualificationImportParser() {}
  public static final class Invalid extends IllegalArgumentException {
    private final String code;
    Invalid(String code,String message){super(message);this.code=code;}
    public String code(){return code;}
  }
  public static List<String> qualifications(Cell cell) {
    if(cell==null || cell.getCellType()==CellType.BLANK)return List.of();
    if(cell.getCellType()!=CellType.STRING)throw invalidQualifications();
    String raw=cell.getStringCellValue().trim();
    if(raw.isEmpty())return List.of();
    var values=new LinkedHashSet<String>();
    for(String token:raw.split("[,，]",-1)) {
      String value=switch(token.trim()) {
        case "INVIGILATOR","监考员" -> "INVIGILATOR";
        case "CHIEF","主监考" -> "CHIEF";
        default -> throw invalidQualifications();
      };
      if(!values.add(value))throw new Invalid("IMPORT_QUALIFICATION_DUPLICATE","监考资格重复，请每项仅填写一次");
    }
    return List.copyOf(values);
  }
  public static LocalDate expiry(Cell cell,Workbook workbook) {
    if(cell==null || cell.getCellType()==CellType.BLANK)return null;
    if(cell.getCellType()==CellType.STRING) {
      String text=cell.getStringCellValue().trim();if(text.isEmpty())return null;
      if(!text.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}"))throw invalidExpiry();
      try {LocalDate date=LocalDate.parse(text);if(date.getYear()<1)throw invalidExpiry();return date;}
      catch(java.time.DateTimeException invalid){throw invalidExpiry();}
    }
    if(cell.getCellType()!=CellType.NUMERIC || !DateUtil.isCellDateFormatted(cell))throw invalidExpiry();
    double serial=cell.getNumericCellValue();
    boolean date1904=workbook instanceof org.apache.poi.xssf.usermodel.XSSFWorkbook xssf?xssf.isDate1904():
        workbook instanceof org.apache.poi.hssf.usermodel.HSSFWorkbook hssf && hssf.getWorkbook().isUsing1904DateWindowing();
    if(!Double.isFinite(serial) || serial!=Math.rint(serial) || serial<0 || (!date1904 && serial==60)
        || serial>(date1904?2957003:2958465))throw invalidExpiry();
    try {return DateUtil.getLocalDateTime(serial,date1904).toLocalDate();}
    catch(RuntimeException invalid){throw invalidExpiry();}
  }
  private static Invalid invalidQualifications(){return new Invalid("IMPORT_QUALIFICATION_INVALID","仅接受 INVIGILATOR/监考员、CHIEF/主监考，以英文或中文逗号分隔；CHIEF 不自动包含 INVIGILATOR");}
  private static Invalid invalidExpiry(){return new Invalid("IMPORT_QUALIFICATION_EXPIRY_INVALID","请填写真实的 yyyy-MM-dd 日期或无时间部分的 Excel 日期单元格；空白表示未提供有效期，不代表已验证永久有效");}
}
