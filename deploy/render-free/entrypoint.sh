#!/usr/bin/env bash
set -euo pipefail
umask 077

# Do not print environment values: connection secrets belong to runtime injection.
refuse() { printf 'JKXT Render validation refused: %s\n' "$1" >&2; exit 64; }
[[ $# -eq 0 ]] || refuse 'unexpected startup arguments'
[[ -z ${JAVA_TOOL_OPTIONS:-}${JDK_JAVA_OPTIONS:-}${_JAVA_OPTIONS:-} ]] \
  || refuse 'implicit JVM options are not permitted in this fixed validation image'
export PORT=${PORT:-10000}
[[ $PORT =~ ^[0-9]{4,5}$ ]] && ((10#$PORT >= 1024 && 10#$PORT <= 65535 && 10#$PORT != 8080)) \
  || refuse 'PORT must be an unprivileged TCP port other than 8080'

for key in SPRING_DATASOURCE_URL SPRING_DATASOURCE_USERNAME SPRING_DATASOURCE_PASSWORD SPRING_DATA_REDIS_HOST; do
  [[ -n ${!key:-} ]] || refuse "missing required runtime setting: $key"
done
[[ $SPRING_DATASOURCE_URL == jdbc:postgresql://* ]] || refuse 'a JDBC PostgreSQL URL is required'
[[ $SPRING_DATASOURCE_URL != *'@'* ]] || refuse 'database credentials must be supplied separately'
export APP_BASE_URL=${APP_BASE_URL:-${RENDER_EXTERNAL_URL:-}}
[[ $APP_BASE_URL =~ ^https://[a-zA-Z0-9]([a-zA-Z0-9.-]*[a-zA-Z0-9])?(:[0-9]{1,5})?$ ]] \
  || refuse 'APP_BASE_URL must be a single HTTPS origin without path or credentials'
export CORS_ALLOWED_ORIGINS=${CORS_ALLOWED_ORIGINS:-$APP_BASE_URL}
[[ $CORS_ALLOWED_ORIGINS == "$APP_BASE_URL" ]] || refuse 'CORS must equal the single HTTPS application origin'
export JKXT_FIXED_ORG_ID=${JKXT_FIXED_ORG_ID:-00000000-0000-0000-0000-000000000001}
[[ $JKXT_FIXED_ORG_ID =~ ^[a-fA-F0-9]{8}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{12}$ \
   && $JKXT_FIXED_ORG_ID != 00000000-0000-0000-0000-000000000000 ]] || refuse 'a canonical non-nil organization UUID is required'

# These invariants are also passed as command-line properties (highest Spring priority).
for key in JKXT_STRICT_PRODUCTION_CHECKS JKXT_DISABLE_DEMO_ACCOUNTS JKXT_DISABLE_DEMO_ROSTER JKXT_AUTH_COOKIE_SECURE; do
  [[ ${!key:-true} == true ]] || refuse "unsafe override: $key"
  export "$key=true"
done
for key in JKXT_AUTH_CAPTCHA_DEBUG JKXT_LLM_ENABLED JKXT_LLM_ACCEPTANCE_MODE JKXT_NOTIFY_EMAIL_ENABLED JKXT_NOTIFY_SMS_ENABLED JKXT_EXTERNAL_SMS_ENABLED; do
  [[ ${!key:-false} == false ]] || refuse "external or debug capability is not authorized: $key"
  export "$key=false"
done
[[ ${SPRING_PROFILES_ACTIVE:-private} == private ]] || refuse 'only the private business profile is supported'
export SPRING_PROFILES_ACTIVE=private JKXT_DEPLOY_MODE=private API_PUBLIC_BASE=/api/v1
# nginx overwrites external forwarding headers. Only this same-container hop is trusted.
export JKXT_AUTH_TRUSTED_PROXIES=127.0.0.1/32
export SERVER_ADDRESS=127.0.0.1 SERVER_PORT=8080

script_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
config=${TMPDIR:-/tmp}/jkxt-render-nginx.conf
envsubst '${PORT}' < "$script_dir/nginx.conf.template" > "$config"
nginx -t -c "$config"

children=()
shutdown() {
  local code=$1 pid alive i
  trap - EXIT TERM INT
  if ((${#children[@]})); then
    kill -TERM "${children[@]}" 2>/dev/null || true
    # Bound graceful shutdown. A failed sibling may not leave a healthy-looking container.
    for ((i=0; i<10; i++)); do
      alive=false
      for pid in "${children[@]}"; do kill -0 "$pid" 2>/dev/null && alive=true; done
      [[ $alive == false ]] && break
      sleep 1
    done
    kill -KILL "${children[@]}" 2>/dev/null || true
    for pid in "${children[@]}"; do wait "$pid" 2>/dev/null || true; done
  fi
  exit "$code"
}
trap 'shutdown $?' EXIT
trap 'shutdown 143' TERM
trap 'shutdown 130' INT

printf '%s\n' 'JKXT temporary validation: strict security; no external AI/email/SMS; no durable backup volume.'
# Conservative 512 MiB starting budget, NOT proof of business-load capacity.
java -Xms64m -Xmx192m -XX:MaxMetaspaceSize=128m -XX:ReservedCodeCacheSize=32m \
  -XX:MaxDirectMemorySize=24m -XX:+UseSerialGC -Xss512k -XX:+ExitOnOutOfMemoryError \
  -Djava.awt.headless=true -jar /app/jkxt-backend.jar \
  --server.address=127.0.0.1 --server.port=8080 --server.forward-headers-strategy=none \
  --server.tomcat.threads.max=24 --server.tomcat.threads.min-spare=2 \
  --spring.datasource.hikari.maximum-pool-size=6 --spring.datasource.hikari.minimum-idle=1 \
  --spring.profiles.active=private --jkxt.deploy.mode=private --jkxt.api-base=/api/v1 \
  --jkxt.deploy.fixed-org-id="$JKXT_FIXED_ORG_ID" \
  --jkxt.cors.allowed-origins="$APP_BASE_URL" \
  --jkxt.security.strict-production-checks=true --jkxt.security.disable-demo-accounts=true \
  --jkxt.security.disable-demo-roster=true --jkxt.auth.captcha-debug=false \
  --jkxt.auth.cookie-secure=true --jkxt.auth.cookie-same-site=Lax \
  --jkxt.auth.trusted-proxies=127.0.0.1/32 \
  --jkxt.llm.enabled=false --jkxt.llm.acceptance-mode=false --jkxt.llm.allow-test-mocks=false \
  --jkxt.notify.email.enabled=false --jkxt.notify.sms.enabled=false --jkxt.deploy.external-sms-enabled=false &
children+=("$!")
nginx -c "$config" -g 'daemon off;' &
children+=("$!")
set +e
wait -n "${children[@]}"
status=$?
set -e
((status != 0)) || status=1
printf '%s\n' 'JKXT child process exited; stopping the companion process.' >&2
exit "$status"
