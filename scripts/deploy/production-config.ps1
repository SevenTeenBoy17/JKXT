# Pure validation of resolved Compose values, including shell overrides. Never print secrets.
function Get-JkxtProductionViolations {
  param([Parameter(Mandatory)]$Config)
  $issues = [System.Collections.Generic.List[string]]::new()
  $appEnv = $Config.services.app.environment
  $mode = [string]$appEnv.JKXT_DEPLOY_MODE
  if ($mode -cnotin @('private', 'saas')) { $issues.Add('JKXT_DEPLOY_MODE must be private or saas in production') }
  if ($mode -ceq 'private') {
    $org = [Guid]::Empty
    if (-not [Guid]::TryParseExact([string]$appEnv.JKXT_FIXED_ORG_ID, 'D', [ref]$org) -or $org -eq [Guid]::Empty) {
      $issues.Add('JKXT_FIXED_ORG_ID must be an explicit nonempty organization UUID')
    }
  }
  foreach ($entry in @{
    JKXT_DISABLE_DEMO_ACCOUNTS = 'true'
    JKXT_DISABLE_DEMO_ROSTER = 'true'
    JKXT_STRICT_PRODUCTION_CHECKS = 'true'
    JKXT_AUTH_CAPTCHA_DEBUG = 'false'
    JKXT_AUTH_COOKIE_SECURE = 'true'
  }.GetEnumerator()) {
    if ([string]$appEnv.($entry.Key) -cne $entry.Value) { $issues.Add("Unsafe or missing setting: $($entry.Key)") }
  }
  $password = [string]$Config.services.postgres.environment.POSTGRES_PASSWORD
  if ($password.Length -lt 20 -or $password -match 'change_me|replace_with|password123|example') {
    $issues.Add('A unique database password of at least 20 characters is required')
  }
  if ([string]$appEnv.SPRING_DATASOURCE_PASSWORD -cne $password) { $issues.Add('Application and database credentials do not match') }
  $baseUri = $null
  if (-not [Uri]::TryCreate([string]$appEnv.APP_BASE_URL, [UriKind]::Absolute, [ref]$baseUri) -or
      $baseUri.Scheme -ne 'https' -or $baseUri.IsLoopback -or $baseUri.UserInfo -or $baseUri.Host -match 'example' -or
      $baseUri.AbsolutePath -ne '/' -or $baseUri.Query -or $baseUri.Fragment) {
    $issues.Add('APP_BASE_URL must be the actual HTTPS delivery origin, not a local/example address')
  }
  $origins = @(([string]$appEnv.CORS_ALLOWED_ORIGINS).Split(',') | ForEach-Object { $_.Trim() } | Where-Object { $_ })
  if ($origins.Count -eq 0) { $issues.Add('CORS_ALLOWED_ORIGINS must be explicit') }
  foreach ($origin in $origins) {
    $uri = $null
    if (-not [Uri]::TryCreate($origin, [UriKind]::Absolute, [ref]$uri) -or $uri.Scheme -ne 'https' -or
        $uri.UserInfo -or $uri.IsLoopback -or $origin.Contains('*') -or $uri.Host -match 'example' -or
        $uri.AbsolutePath -ne '/' -or $uri.Query -or $uri.Fragment) {
      $issues.Add('Every CORS origin must be an explicit HTTPS origin without path, wildcard or credentials')
    }
  }
  if ($baseUri -and $origins -notcontains $baseUri.GetLeftPart([UriPartial]::Authority)) { $issues.Add('APP_BASE_URL origin is missing from CORS_ALLOWED_ORIGINS') }
  if ($null -ne $Config.services.caddy) {
    # This topology publishes fixed 80/443. Refuse Caddy address syntax that can disable TLS,
    # enable wildcard/multiple sites or disagree with the origin used by secure cookies/CORS.
    $domain = [string]$Config.services.caddy.environment.PUBLIC_DOMAIN
    $dnsPattern = '^(?=.{1,253}$)(?:[a-zA-Z0-9](?:[a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?\.)+[a-zA-Z]{2,63}$'
    if ($domain -cnotmatch $dnsPattern -or -not $baseUri -or
        $domain -ine $baseUri.IdnHost -or $baseUri.Port -ne 443) {
      $issues.Add('PUBLIC_DOMAIN must be one TLS DNS hostname matching the APP_BASE_URL origin on port 443')
    }
  }
  # The app trusts the internal proxy. A public app binding would bypass TLS
  # and the ingress's forwarding-header normalization, even when DBs are private.
  $protectedServices = @('app', 'postgres', 'redis')
  # Public nginx trusts the Caddy hop; exposing it would bypass the TLS ingress.
  # Private deployments without Caddy retain their documented nginx ingress.
  if ($null -ne $Config.services.caddy) { $protectedServices += 'nginx' }
  foreach ($name in $protectedServices) {
    if ([string]$Config.services.$name.network_mode -ceq 'host') {
      $issues.Add("$name must not use host networking in this isolated proxy topology")
    }
    foreach ($port in @($Config.services.$name.ports)) {
      if ($null -ne $port -and $port.host_ip -notin @('127.0.0.1', '::1')) { $issues.Add("$name must not publish a backend port on a public interface") }
    }
  }
  return $issues.ToArray()
}
