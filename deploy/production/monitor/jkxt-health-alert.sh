#!/bin/sh
# JKXT health monitor + alert rule. Requires POSIX sh/awk/od/mktemp + curl.
# Run as a sidecar/service, or set MAX_PROBES for a bounded cron/systemd invocation.
# Rule: ALERT when /actuator/health/readiness is not UP for FAIL_THRESHOLD consecutive probes;
#       RESOLVED on the first UP probe after an alert. State changes are appended to ALERT_LOG and,
#       only if ALERT_WEBHOOK_URL is set, POSTed as JSON (configure a real channel at go-live).
# Failed delivery is logged without URLs, credentials or response bodies. Monitoring continues;
# a bounded invocation exits 1 if any delivery failed. No redirect following or automatic retry.
set -uf
LC_ALL=C
export LC_ALL
TARGET=${TARGET:-https://127.0.0.1}
INTERVAL=${INTERVAL:-10}
FAIL_THRESHOLD=${FAIL_THRESHOLD:-3}
ALERT_LOG=${ALERT_LOG:-/var/log/jkxt-alerts.log}
CURL_OPTS=${CURL_OPTS:-}
MAX_PROBES=${MAX_PROBES:-0}   # 0 = run forever
PROBE_TIMEOUT_SECONDS=${PROBE_TIMEOUT_SECONDS:-5}
WEBHOOK_TIMEOUT_SECONDS=${WEBHOOK_TIMEOUT_SECONDS:-5}
for count in "$FAIL_THRESHOLD" "$MAX_PROBES" "$PROBE_TIMEOUT_SECONDS" "$WEBHOOK_TIMEOUT_SECONDS"; do
  case "$count" in ''|*[!0-9]*) printf '%s\n' 'Invalid monitor count or timeout' >&2; exit 2 ;; esac
done
if [ "$FAIL_THRESHOLD" -lt 1 ] || [ "$PROBE_TIMEOUT_SECONDS" -lt 1 ] || [ "$WEBHOOK_TIMEOUT_SECONDS" -lt 1 ]; then
  printf '%s\n' 'Threshold and timeouts must be positive' >&2; exit 2
fi
probe_body=$(mktemp "${TMPDIR:-/tmp}/jkxt-health.XXXXXX") || exit 2
payload_file=$(mktemp "${TMPDIR:-/tmp}/jkxt-alert.XXXXXX") || { rm -f "$probe_body"; exit 2; }
trap 'rm -f "$probe_body" "$payload_file"' 0
trap 'exit 130' INT
trap 'exit 143' TERM
fails=0; state=OK; n=0; delivery_failed=0

# Encode bytes, not shell text: quotes, backslashes, all JSON control bytes (including NUL),
# Unicode UTF-8 and trailing newlines survive exactly. No jq/Python runtime is required.
json_string() {
  od -An -v -tu1 | awk '
    BEGIN { printf "\"" }
    { for (i=1;i<=NF;i++) {
        b=$i+0
        if (b==34) printf "\\\""
        else if (b==92) printf "\\\\"
        else if (b<32) printf "\\u%04x",b
        else printf "%c",b
    } }
    END { printf "\"" }'
}

# Parse a complete JSON object and inspect only its unique root status. Substring matching
# accepts DOWN responses with a nested UP, malformed JSON, and duplicate status properties.
readiness_up() {
  awk '
    function ws() { while (substr(text,p,1) ~ /^[ \t\r\n]$/) p++ }
    function str( c,e,h,j,v,out) {
      if (substr(text,p++,1)!="\"") return 0
      out=""
      while (p<=length(text)) {
        c=substr(text,p++,1)
        if (c=="\"") { decoded=out; return 1 }
        if (c ~ /[[:cntrl:]]/) return 0
        if (c=="\\") {
          e=substr(text,p++,1)
          if (e=="\"" || e=="\\" || e=="/") c=e
          else if (e=="b") c=sprintf("%c",8)
          else if (e=="f") c=sprintf("%c",12)
          else if (e=="n") c="\n"
          else if (e=="r") c="\r"
          else if (e=="t") c="\t"
          else if (e=="u") {
            h=substr(text,p,4); if (length(h)!=4 || h ~ /[^0-9a-fA-F]/) return 0
            v=0; for (j=1;j<=4;j++) v=v*16+index("0123456789abcdef",tolower(substr(h,j,1)))-1
            # Only ASCII participates in the status key/value; other Unicode cannot match it.
            c=(v<128?sprintf("%c",v):"?"); p+=4
          } else return 0
        }
        out=out c
      }
      return 0
    }
    function value(depth, c,key,rootStatus,token) {
      if (depth>64) return 0
      ws(); c=substr(text,p,1)
      if (c=="\"") return str()
      if (c=="{") {
        p++; ws(); if (substr(text,p,1)=="}") { p++; return 1 }
        while (1) {
          ws(); if (!str()) return 0
          key=decoded; ws(); if (substr(text,p++,1)!=":") return 0
          rootStatus=(depth==0 && key=="status"); ws()
          if (rootStatus) {
            if (++statuses!=1 || !str()) return 0
            rootValue=decoded
          } else if (!value(depth+1)) return 0
          ws(); c=substr(text,p++,1)
          if (c=="}") return 1
          if (c!=",") return 0
        }
      }
      if (c=="[") {
        p++; ws(); if (substr(text,p,1)=="]") { p++; return 1 }
        while (1) {
          if (!value(depth+1)) return 0
          ws(); c=substr(text,p++,1)
          if (c=="]") return 1
          if (c!=",") return 0
        }
      }
      token=substr(text,p)
      if (match(token,/^(true|false|null)/) || match(token,/^-?(0|[1-9][0-9]*)(\.[0-9]+)?([eE][+-]?[0-9]+)?/)) {
        p+=RLENGTH; return 1
      }
      return 0
    }
    { text=text (NR>1?"\n":"") $0 }
    END {
      p=1; ws()
      if (substr(text,p,1)!="{" || !value(0)) exit 1
      ws(); if (p<=length(text) || statuses!=1 || rootValue!="UP") exit 1
    }'
}

record() {
  # Only generated status/count/HTTP metadata reaches local logs, never raw response bodies.
  printf '%s %s\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)" "$1" | tee -a "$ALERT_LOG" || delivery_failed=1
}
emit() {
  record "$1 jkxt readiness failed_probes=$fails"
  if [ -n "${ALERT_WEBHOOK_URL:-}" ]; then
    # Send a file: native curl on Windows can otherwise transcode UTF-8 command-line bytes.
    {
      printf '{"status":"%s","detail":' "$1"
      { printf '%s' "$2"; if [ "$3" = body ]; then cat "$probe_body"; fi; } | json_string
      printf '}'
    } > "$payload_file"
    hook_http=$(curl -q -s -m "$WEBHOOK_TIMEOUT_SECONDS" -o /dev/null -w '%{http_code}' \
      -H 'Content-Type: application/json' --data-binary "@$payload_file" -- "$ALERT_WEBHOOK_URL" 2>/dev/null)
    hook_exit=$?
    case "$hook_exit:$hook_http" in
      0:2[0-9][0-9]) ;;
      *) delivery_failed=1; record "WEBHOOK_FAILED event=$1 curl=$hook_exit http=$hook_http" ;;
    esac
  fi
}
while :; do
  : > "$probe_body" || exit 2
  probe_http=$(curl -q -s -m "$PROBE_TIMEOUT_SECONDS" $CURL_OPTS -o "$probe_body" -w '%{http_code}' \
    -- "$TARGET/actuator/health/readiness" 2>/dev/null)
  probe_exit=$?
  ok=0
  case "$probe_exit:$probe_http" in
    0:2[0-9][0-9]) if readiness_up < "$probe_body"; then ok=1; fi ;;
  esac
  if [ $ok -eq 1 ]; then
    if [ "$state" = FIRING ]; then emit RESOLVED "UP again after $fails failed probes" none; fi
    fails=0; state=OK
  else
    fails=$((fails+1))
    if [ "$state" = OK ] && [ $fails -ge $FAIL_THRESHOLD ]; then
      state=FIRING
      if [ -s "$probe_body" ]; then emit FIRING "not UP for $fails consecutive probes: " body
      else emit FIRING "not UP for $fails consecutive probes: no response" none
      fi
    fi
  fi
  n=$((n+1)); [ "$MAX_PROBES" -gt 0 ] && [ $n -ge "$MAX_PROBES" ] && exit "$delivery_failed"
  sleep "$INTERVAL" || exit 2
done
