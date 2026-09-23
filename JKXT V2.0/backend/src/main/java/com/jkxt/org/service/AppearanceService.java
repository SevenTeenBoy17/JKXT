package com.jkxt.org.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jkxt.common.tenant.TenantContext;
import com.jkxt.common.web.ApiException;
import com.jkxt.org.api.AppearanceDtos.AppearanceResponse;
import com.jkxt.org.api.AppearanceDtos.UpdateAppearanceRequest;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AppearanceService {

  private static final List<String> ORDERED_PALETTES = List.of("indigo", "blue", "green", "rose", "cyan", "purple");
  private static final Set<String> PALETTES = Set.copyOf(ORDERED_PALETTES);

  private final JdbcTemplate jdbcTemplate;
  private final ObjectMapper objectMapper;

  public AppearanceService(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
    this.jdbcTemplate = jdbcTemplate;
    this.objectMapper = objectMapper;
  }

  @Transactional(readOnly = true)
  public AppearanceResponse get() {
    UUID orgId = TenantContext.getRequired();
    String raw = jdbcTemplate.query(
        "select coalesce(settings->'appearance','{}'::jsonb)::text from organization where id = ?",
        result -> result.next() ? result.getString(1) : null,
        orgId);
    if (raw == null) {
      throw new ApiException(HttpStatus.NOT_FOUND, "ORGANIZATION_NOT_FOUND", "Organization is unavailable");
    }
    try {
      JsonNode node = objectMapper.readTree(raw);
      int version = node.path("version").asInt(1);
      String palette = node.path("defaultPalette").asText("indigo");
      return response(version == 1 ? 1 : 1, PALETTES.contains(palette) ? palette : "indigo");
    } catch (Exception ignored) {
      return response(1, "indigo");
    }
  }

  @Transactional
  public AppearanceResponse update(UpdateAppearanceRequest request) {
    int version = request.version() == null ? 1 : request.version();
    String palette = request.defaultPalette().trim().toLowerCase();
    if (version != 1) {
      throw new ApiException(HttpStatus.BAD_REQUEST, "APPEARANCE_INVALID", "Only appearance version 1 is supported");
    }
    if (!PALETTES.contains(palette)) {
      throw new ApiException(HttpStatus.BAD_REQUEST, "APPEARANCE_INVALID", "Unsupported school palette");
    }
    ObjectNode appearance = objectMapper.createObjectNode();
    appearance.put("version", version);
    appearance.put("defaultPalette", palette);
    UUID orgId = TenantContext.getRequired();
    int changed = jdbcTemplate.update(
        "update organization set settings = jsonb_set(coalesce(settings,'{}'::jsonb), "
            + "'{appearance}', ?::jsonb, true), updated_at = now(), version = version + 1 where id = ?",
        appearance.toString(), orgId);
    if (changed != 1) {
      throw new ApiException(HttpStatus.NOT_FOUND, "ORGANIZATION_NOT_FOUND", "Organization is unavailable");
    }
    return response(version, palette);
  }

  private AppearanceResponse response(int version, String palette) {
    return new AppearanceResponse(version, palette, ORDERED_PALETTES);
  }
}
