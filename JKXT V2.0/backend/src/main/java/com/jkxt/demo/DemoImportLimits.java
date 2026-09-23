package com.jkxt.demo;

import com.jkxt.common.config.DeployProperties;
import com.jkxt.common.tenant.TenantContext;
import com.jkxt.common.web.ApiException;
import com.jkxt.security.auth.AuthRequestAttributes;
import com.jkxt.security.auth.PortalPurpose;
import com.jkxt.security.auth.SessionEnvelope;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.Semaphore;
import java.util.function.Supplier;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import javax.xml.stream.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.multipart.MultipartFile;

/** DEMO import admission, bounded decoding, and the synchronous collection write boundary. */
@Component
public final class DemoImportLimits {
  public static final int INPUT_BYTES=10*1024*1024;
  public static final long EXPANDED_BYTES=64L*1024*1024;
  private static final Set<String> FORMULA_ELEMENTS=Set.of("f","formula","formula1","formula2",
      "calculatedColumnFormula","totalsRowFormula","definedName");
  private static final Set<String> RANGE_ATTRIBUTES=Set.of("ref","sqref","activeCell","topLeftCell");
  private static final Semaphore PARSING=new Semaphore(1);
  private final DeployProperties deploy;
  private final WorkspaceFence fence;
  private final DemoQuotaService quotas;
  public DemoImportLimits(DeployProperties deploy,WorkspaceFence fence,DemoQuotaService quotas) {
    this.deploy=Objects.requireNonNull(deploy);this.fence=Objects.requireNonNull(fence);this.quotas=Objects.requireNonNull(quotas);
  }

  /** Only the filter's immutable server identity is admitted. An internal call has no demo bypass. */
  public Optional<DemoLease> capture() {
    if(!deploy.isDemo())return Optional.empty();
    if(!(RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes))throw missingLease();
    var request=attributes.getRequest();
    if(!(request.getAttribute(AuthRequestAttributes.DEMO_LEASE) instanceof DemoLease lease)
        || !(request.getAttribute(AuthRequestAttributes.ENVELOPE) instanceof SessionEnvelope envelope)
        || envelope.audience()!=PortalPurpose.DEMO || !Objects.equals(envelope.workspaceId(),lease.workspaceId())
        || !Objects.equals(envelope.generation(),lease.generation()) || !envelope.principal().orgId().equals(lease.orgId())
        || !envelope.expiresAt().equals(lease.expiresAt()) || !TenantContext.getRequired().equals(lease.orgId()))throw missingLease();
    fence.assertReadable(lease);return Optional.of(lease);
  }
  private static com.jkxt.security.auth.AuthException missingLease() {
    return DemoWorkspaceRepository.error(HttpStatus.FORBIDDEN,"DEMO_LEASE_REQUIRED","此演示操作需要当前服务器会话，请重新进入演示工作区");
  }
  private void requireCaptured(Optional<DemoLease> captured) {
    if(captured==null || deploy.isDemo()!=captured.isPresent())throw missingLease();
    if(captured.isPresent() && !captured.get().orgId().equals(TenantContext.getRequired()))throw missingLease();
    if(captured.isPresent() && !captured.equals(capture()))throw missingLease();
  }
  public void readable(Optional<DemoLease> captured) {requireCaptured(captured);captured.ifPresent(fence::assertReadable);}
  public <T>T fenced(Optional<DemoLease> captured,Supplier<T> mutation) {
    requireCaptured(captured);return captured.isPresent()?fence.commit(captured.get(),mutation):mutation.get();
  }
  /** Called by the admitted importer/CRUD service while retaining the workspace write lock. */
  public <T>T capacity(Optional<DemoLease> captured,String collection,long newRows,Supplier<T> mutation) {
    requireCaptured(captured);
    if(captured.isEmpty())return mutation.get();
    try(var reservation=quotas.reserve(captured.get(),collection,newRows)) {
      T result=mutation.get();reservation.commit();return result;
    }
  }
  public <T>T mutate(Optional<DemoLease> captured,String collection,long newRows,Supplier<T> mutation) {
    return fenced(captured,()->collection==null?mutation.get():capacity(captured,collection,newRows,mutation));
  }
  public static final class ImportBusyException extends ApiException {
    public ImportBusyException(){super(HttpStatus.SERVICE_UNAVAILABLE,"DEMO_IMPORT_BUSY","演示文件正在解析，请等待 5 秒后重新上传");}
  }
  public static final class ParsePermit implements AutoCloseable {
    private final boolean held;private boolean closed;
    private ParsePermit(boolean held){this.held=held;}
    @Override public void close(){if(!closed){closed=true;if(held)PARSING.release();}}
  }
  public ParsePermit begin(Optional<DemoLease> captured) {
    requireCaptured(captured);
    if(captured.isPresent() && !PARSING.tryAcquire())throw new ImportBusyException();
    return new ParsePermit(captured.isPresent());
  }
  public static byte[] freeze(MultipartFile file,boolean demo) throws IOException {
    if(file==null)return new byte[0];
    if(demo && file.getSize()>INPUT_BYTES)throw problem("IMPORT_FILE_TOO_LARGE","演示文件不得超过 10MiB，请按模板拆分并检查工作区总容量");
    try(var input=file.getInputStream();var output=new ByteArrayOutputStream()) {
      byte[] buffer=new byte[8192];int count;long actual=0;
      while((count=input.read(buffer))!=-1) {
        cancelled();actual+=count;
        if(demo && actual>INPUT_BYTES)throw problem("IMPORT_FILE_TOO_LARGE","演示文件实际内容超过 10MiB");
        output.write(buffer,0,count);
      }
      return output.toByteArray();
    }
  }
  private static void cancelled(){if(Thread.currentThread().isInterrupted())throw problem("IMPORT_CANCELLED","文件解析已取消，请重新上传");}
  private static ApiException problem(String code,String message){return new ApiException(HttpStatus.BAD_REQUEST,code,message);}
  private static ApiException complex(String reason){return problem("IMPORT_COMPLEXITY_EXCEEDED",reason+"，请转换为系统提供的简洁模板后重试");}

  /** Streaming complete-package preflight; no POI, DOM, claimed ZIP size, or global POI settings. */
  public static void inspectPackage(byte[] bytes) {
    if(bytes.length>INPUT_BYTES)throw problem("IMPORT_FILE_TOO_LARGE","演示文件不得超过 10MiB");
    if(bytes.length<4 || bytes[0]!='P' || bytes[1]!='K' || bytes[2]!=3 || bytes[3]!=4)
      throw problem("IMPORT_WORKBOOK_UNSUPPORTED","演示仅支持未加密的 XLSX 工作簿，请将 XLS/XLSB 等格式转换后上传");
    var budget=new Budget();var names=new HashSet<String>();
    XMLInputFactory factory=XMLInputFactory.newFactory();
    factory.setProperty(XMLInputFactory.SUPPORT_DTD,false);
    factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES,false);
    factory.setXMLResolver((publicId,systemId,base,namespace)->{throw new XMLStreamException("External resolution forbidden");});
    try(var zip=new ZipInputStream(new ByteArrayInputStream(bytes))) {
      ZipEntry entry;int entries=0;
      while((entry=zip.getNextEntry())!=null) {
        cancelled();if(++entries>256)throw complex("ZIP 项目超过 256 个");
        String name=canonicalPart(entry.getName());
        if(!names.add(name))throw problem("IMPORT_PART_DUPLICATE","ZIP 包含重复或规范化后冲突的部件名称");
        if(name.contains("/embeddings/") || name.contains("/externallinks/") || name.contains("/activex/") || name.endsWith(".bin"))
          throw problem("IMPORT_OBJECT_FORBIDDEN","演示工作簿不允许嵌入对象、外部链接或二进制部件");
        var bounded=new FilterInputStream(zip) {
          @Override public int read() throws IOException {int value=in.read();if(value!=-1)budget.expanded(1);return value;}
          @Override public int read(byte[] b,int off,int length) throws IOException {int n=in.read(b,off,length);if(n>0)budget.expanded(n);return n;}
          @Override public void close() {} // The ZIP owner closes it after all parts have been counted.
        };
        if(!entry.isDirectory() && inspectedExtension(name)) {
          scanXml(factory,bounded,name,budget);budget.inspected.add(name);
        }
        byte[] buffer=new byte[8192];while(bounded.read(buffer)!=-1)cancelled();
        zip.closeEntry();
      }
      if(!names.contains("[content_types].xml") || !names.contains("xl/workbook.xml") || budget.worksheets==0 || !budget.xlsxType)
        throw problem("IMPORT_WORKBOOK_UNSUPPORTED","未找到完整 XLSX 工作簿，请使用系统模板");
      if(!budget.inspected.containsAll(budget.targets))
        throw problem("IMPORT_PART_INVALID","关系指向不存在或未经完整 XML 检查的部件，请转换为系统 XLSX 模板");
    } catch(ApiException failure){throw failure;}
    catch(IOException | XMLStreamException | IllegalArgumentException failure) {
      throw problem("IMPORT_FILE_UNREADABLE","XLSX 包或 XML 结构无效，请确认文件完整、未加密且不包含外部实体");
    }
  }
  private static String canonicalPart(String name) {
    String decoded=java.net.URLDecoder.decode(name.replace("+","%2B"),StandardCharsets.UTF_8);
    if(decoded.startsWith("/") || decoded.contains("\\") || decoded.contains(":") || decoded.indexOf(0)>=0)
      throw problem("IMPORT_PART_INVALID","工作簿包含不安全的部件路径");
    for(String part:decoded.split("/"))if(part.equals(".") || part.equals("..") || part.isEmpty())
      throw problem("IMPORT_PART_INVALID","工作簿包含不安全的部件路径");
    return decoded.toLowerCase(Locale.ROOT);
  }
  private static boolean inspectedExtension(String part){return part.endsWith(".xml") || part.endsWith(".rels") || part.endsWith(".vml");}
  /** POI selects XML readers by relationship type, not by the caller's MIME claim. */
  private static String inspectedTarget(String relationshipPart,String target) {
    if(target==null || target.isBlank())throw problem("IMPORT_PART_INVALID","工作簿关系缺少目标部件");
    String source;
    if(relationshipPart.equals("_rels/.rels"))source="";
    else {
      int marker=relationshipPart.lastIndexOf("/_rels/");
      if(marker<0 || !relationshipPart.endsWith(".rels"))throw problem("IMPORT_PART_INVALID","工作簿关系部件路径无效");
      source=relationshipPart.substring(0,marker+1)+relationshipPart.substring(marker+7,relationshipPart.length()-5);
    }
    var reference=java.net.URI.create(target);
    if(reference.isAbsolute() || reference.getRawAuthority()!=null || reference.getRawQuery()!=null || reference.getRawFragment()!=null)
      throw problem("IMPORT_PART_INVALID","工作簿关系必须指向本包内的标准 XML 部件");
    String resolved=java.net.URI.create("/"+source).resolve(reference).normalize().getRawPath();
    String part=canonicalPart(resolved.startsWith("/")?resolved.substring(1):resolved);
    if(!inspectedExtension(part))throw problem("IMPORT_PART_INVALID","演示关系仅支持已检查的 XML/关系/VML 部件，请使用系统模板移除其他资源");
    return part;
  }
  private static final class Budget {
    long expanded,text,elements,cells,strings,rows;int worksheets,sheets;boolean xlsxType;
    final Set<String> inspected=new HashSet<>(),targets=new HashSet<>();
    void expanded(int size){cancelled();expanded+=size;if(expanded>EXPANDED_BYTES)throw problem("IMPORT_EXPANDED_TOO_LARGE","工作簿实际累计展开内容超过 64MiB");}
    void characters(int length){text+=length;if(text>4000000)throw complex("XML 解码文本、属性或命名空间超过 4000000 字符");}
  }
  private static void scanXml(XMLInputFactory factory,InputStream input,String part,Budget budget) throws XMLStreamException {
    XMLStreamReader xml=factory.createXMLStreamReader(input);int depth=0,cellDepth=0,stringDepth=0,column=0,rowPosition=0;long cellText=0,stringText=0;
    boolean worksheet=false,sharedStrings=false;
    try {
      while(xml.hasNext()) {
        cancelled();int event=xml.next();
        if(event==XMLStreamConstants.DTD || event==XMLStreamConstants.ENTITY_REFERENCE)throw problem("IMPORT_XML_ENTITY_FORBIDDEN","工作簿不允许 DTD 或实体引用");
        if(event==XMLStreamConstants.START_ELEMENT) {
          if(++depth>64 || ++budget.elements>1000000)throw complex("XML 层数或元素数量超限");
          for(int i=0;i<xml.getAttributeCount();i++)budget.characters(xml.getAttributeValue(i).length());
          for(int i=0;i<xml.getNamespaceCount();i++) {
            String uri=xml.getNamespaceURI(i);if(uri!=null)budget.characters(uri.length());
          }
          String local=xml.getLocalName();
          // Named expressions, table/rule/chart formulas and hidden sheets are also forbidden.
          if(FORMULA_ELEMENTS.contains(local))throw problem("IMPORT_FORMULA_FORBIDDEN","演示文件不允许公式或命名表达式，请转换为简洁模板并粘贴为值后上传");
          if(depth==1) {
            worksheet=local.equals("worksheet");sharedStrings=local.equals("sst");
            if(worksheet && ++budget.worksheets>10)throw complex("工作表超过 10 个");
          }
          // MIME declarations supplement, but do not replace, relationship-target admission.
          if(part.equals("[content_types].xml") && Set.of("Override","Default").contains(local)) {
            String type=attribute(xml,"ContentType"),name=attribute(xml,"PartName"),extension=attribute(xml,"Extension");
            if(type!=null) {
              String lower=type.toLowerCase(Locale.ROOT);
              // POI's standard header comments include a VML XML drawing, not a binary object.
              boolean vml=lower.equals("application/vnd.openxmlformats-officedocument.vmldrawing");
              if(lower.contains("binary") || lower.contains("macroenabled") || lower.contains("oleobject"))throw problem("IMPORT_WORKBOOK_UNSUPPORTED","演示不支持二进制、宏或嵌入对象工作簿");
              if(type.equals("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"))budget.xlsxType=true;
              if(lower.contains("xml") && ((name!=null && !name.toLowerCase(Locale.ROOT).endsWith(".xml") && !name.toLowerCase(Locale.ROOT).endsWith(".rels")
                      && !(vml && name.toLowerCase(Locale.ROOT).endsWith(".vml")))
                  || (extension!=null && !Set.of("xml","rels").contains(extension.toLowerCase(Locale.ROOT))
                      && !(vml && extension.equalsIgnoreCase("vml")))))throw problem("IMPORT_PART_INVALID","XML 部件扩展名无效，请转换为标准 XLSX");
            }
          }
          if(local.equals("Relationship")) {
            String mode=attribute(xml,"TargetMode"),target=attribute(xml,"Target"),type=attribute(xml,"Type");
            if("external".equalsIgnoreCase(mode) || (target!=null && (target.contains(":") || target.startsWith("//"))))
              throw problem("IMPORT_EXTERNAL_RELATIONSHIP","演示不允许外部关系或外部资源，请移除后重新上传");
            if(type!=null && (type.endsWith("/oleObject") || type.endsWith("/package") || type.endsWith("/externalLink")))
              throw problem("IMPORT_OBJECT_FORBIDDEN","演示不允许嵌入对象或外部链接");
            if(part.endsWith(".rels")) {
              budget.targets.add(inspectedTarget(part,target));
              if(budget.targets.size()>256)throw complex("关系目标部件超过 256 个");
            }
          }
          if(Set.of("oleObject","oleObjects","externalLink","externalLinks").contains(local))throw problem("IMPORT_OBJECT_FORBIDDEN","演示不允许嵌入对象或外部链接");
          // Extended text-form ranges are not part of the supported template grammar.
          if(local.equals("sqref"))throw problem("IMPORT_PART_INVALID","演示暂不支持扩展文本范围，请转换为系统模板后重试");
          for(int i=0;i<xml.getAttributeCount();i++)if(RANGE_ATTRIBUTES.contains(xml.getAttributeLocalName(i)))
            ranges(xml.getAttributeValue(i));
          if(part.equals("xl/workbook.xml") && local.equals("sheet") && ++budget.sheets>10)throw complex("工作表超过 10 个");
          if(worksheet) {
            if(local.equals("col")){boundIndex(attribute(xml,"min"),64,"列");boundIndex(attribute(xml,"max"),64,"列");}
            if(local.equals("row")) {
              column=0;
              if(++budget.rows>10000)throw complex("所有工作表累计行数超过 10000");
              String reference=attribute(xml,"r");boundIndex(reference,10000,"行");
              rowPosition=reference==null?rowPosition+1:Integer.parseInt(reference);
              if(rowPosition>10000)throw complex("工作表隐式行索引超过 10000");
              String spans=attribute(xml,"spans");if(spans!=null)for(String span:spans.split("[ :]+"))boundIndex(span,64,"列");
            }
            if(local.equals("c")) {
              if(++budget.cells>200000)throw complex("所有工作表累计单元格超过 200000");
              String reference=attribute(xml,"r");
              column=reference==null?column+1:range(reference);
              if(column>64)throw complex("工作表隐式列索引超过 64");
              cellDepth=depth;cellText=0;
            }
          }
          if(sharedStrings && local.equals("si")){if(++budget.strings>200000)throw complex("共享字符串超过 200000 项");stringDepth=depth;stringText=0;}
        } else if(event==XMLStreamConstants.CHARACTERS || event==XMLStreamConstants.CDATA || event==XMLStreamConstants.SPACE) {
          int length=xml.getTextLength();budget.characters(length);
          if(cellDepth>0 && (cellText+=length)>32767)throw complex("单元格超过 32767 字符");
          if(stringDepth>0 && (stringText+=length)>32767)throw complex("共享字符串单元格超过 32767 字符");
        } else if(event==XMLStreamConstants.END_ELEMENT) {
          if(depth==cellDepth)cellDepth=0;if(depth==stringDepth)stringDepth=0;depth--;
        }
      }
    } finally {xml.close();}
  }
  private static String attribute(XMLStreamReader reader,String name){return reader.getAttributeValue(null,name);}
  private static void boundIndex(String raw,int maximum,String kind) {
    if(raw==null)return;
    try {long value=Long.parseLong(raw);if(value<1 || value>maximum)throw complex("工作表"+kind+"索引超限");}
    catch(NumberFormatException invalid){throw problem("IMPORT_FILE_UNREADABLE","工作表行列索引无效");}
  }
  private static int range(String reference) {
    if(reference==null)return 0;
    int column=0;
    for(String point:reference.split(":",-1)) {
      var matcher=java.util.regex.Pattern.compile("\\$?([A-Za-z]{1,3})\\$?([0-9]+)").matcher(point);
      if(!matcher.matches())throw problem("IMPORT_FILE_UNREADABLE","工作表单元格范围无效");
      column=0;for(char c:matcher.group(1).toUpperCase(Locale.ROOT).toCharArray())column=column*26+c-'A'+1;
      if(column>64)throw complex("工作表列索引超过 64");boundIndex(matcher.group(2),10000,"行");
    }
    return column;
  }
  private static void ranges(String references) {
    if(references.isBlank())throw problem("IMPORT_FILE_UNREADABLE","工作表声明范围为空");
    for(String reference:references.trim().split("\\s+"))range(reference);
  }
}
