package com.jkxt.security.auth;
public final class PortalSessionPolicy {
  private PortalSessionPolicy() {}
  public static boolean allows(PortalPurpose purpose,String role) {
    if(purpose==null || role==null) return false;
    return switch(purpose) {
      case SCHOOL -> java.util.Set.of("ADMIN","VIEWER","TEACHER_QUERY").contains(role);
      case PLATFORM -> "SUPER_ADMIN".equals(role);
      case DEMO -> "DEMO".equals(role);
    };
  }
}
