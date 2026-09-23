package com.jkxt.security.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jkxt.common.web.ApiException;
import com.jkxt.security.api.UserPreferencesDtos.PatchUserPreferencesRequest;
import com.jkxt.security.api.UserPreferencesDtos.ThemePatch;
import com.jkxt.security.api.UserPreferencesDtos.ThemePreferences;
import com.jkxt.security.api.UserPreferencesDtos.UserPreferencesResponse;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class UserPreferencesService {

  private static final Set<String> MODES = Set.of("system", "light", "dark");
  private static final Set<String> SOURCES = Set.of("school", "user");
  private static final Set<String> PALETTES = Set.of("indigo", "blue", "green", "rose", "cyan", "purple");

  private final JdbcTemplate jdbcTemplate;
  private final ObjectMapper objectMapper;

  public UserPreferencesService(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
    this.jdbcTemplate = jdbcTemplate;
    this.objectMapper = objectMapper;
  }

  @Transactional(readOnly = true)
  public UserPreferencesResponse get(UUID orgId, UUID userId) {
    String raw = jdbcTemplate.query(
        "select preferences::text from app_user where org_id = ? and id = ?",
        result -> result.next() ? result.getString(1) : null,
        orgId, userId);
    if (raw == null) {
      throw new ApiException(HttpStatus.NOT_FOUND, "USER_NOT_FOUND", "User is unavailable");
    }
    return response(parse(raw));
  }

  @Transactional
  public UserPreferencesResponse patch(UUID orgId, UUID userId, PatchUserPreferencesRequest request) {
    UserPreferencesResponse current = get(orgId, userId);
    ThemePatch patch = request.theme();
    int version = patch.version() == null ? current.theme().version() : patch.version();
    String mode = valueOr(patch.mode(), current.theme().mode());
    String source = valueOr(patch.paletteSource(), current.theme().paletteSource());
    String palette = valueOr(patch.palette(), current.theme().palette());
    validate(version, mode, source, palette);

    ObjectNode theme = objectMapper.createObjectNode();
    theme.put("version", version);
    theme.put("mode", mode);
    theme.put("paletteSource", source);
    theme.put("palette", palette);
    int changed = jdbcTemplate.update(
        "update app_user set preferences = jsonb_set(coalesce(preferences,'{}'::jsonb), "
            + "'{theme}', ?::jsonb, true), updated_at = now(), version = version + 1 "
            + "where org_id = ? and id = ?",
        theme.toString(), orgId, userId);
    if (changed != 1) {
      throw new ApiException(HttpStatus.NOT_FOUND, "USER_NOT_FOUND", "User is unavailable");
    }
    return new UserPreferencesResponse(new ThemePreferences(version, mode, source, palette));
  }

  private JsonNode parse(String raw) {
    try {
      return objectMapper.readTree(raw);
    } catch (Exception ignored) {
      return objectMapper.createObjectNode();
    }
  }

  private UserPreferencesResponse response(JsonNode root) {
    JsonNode theme = root.path("theme");
    int version = theme.path("version").asInt(1);
    String mode = allowed(theme.path("mode").asText(), MODES, "system");
    String source = allowed(theme.path("paletteSource").asText(), SOURCES, "school");
    String palette = allowed(theme.path("palette").asText(), PALETTES, "indigo");
    return new UserPreferencesResponse(new ThemePreferences(version, mode, source, palette));
  }

  private void validate(int version, String mode, String source, String palette) {
    if (version != 1) {
      throw invalid("theme.version", "Only theme preference version 1 is supported");
    }
    if (!MODES.contains(mode)) {
      throw invalid("theme.mode", "mode must be system, light, or dark");
    }
    if (!SOURCES.contains(source)) {
      throw invalid("theme.paletteSource", "paletteSource must be school or user");
    }
    if (!PALETTES.contains(palette)) {
      throw invalid("theme.palette", "palette must be indigo, blue, green, rose, cyan, or purple");
    }
  }

  private ApiException invalid(String field, String message) {
    return new ApiException(HttpStatus.BAD_REQUEST, "PREFERENCE_INVALID", field + ": " + message);
  }

  private String valueOr(String value, String fallback) {
    return value == null || value.isBlank() ? fallback : value.trim().toLowerCase();
  }

  private String allowed(String value, Set<String> allowed, String fallback) {
    return allowed.contains(value) ? value : fallback;
  }
}
