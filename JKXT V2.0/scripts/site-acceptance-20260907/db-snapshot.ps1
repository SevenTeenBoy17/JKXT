param(
  [Parameter(Mandatory)][ValidateSet('jkxt-test-migration-20260907-pg','jkxt-private-postgres')][string]$Container,
  [Parameter(Mandatory)][ValidatePattern('^jkxt(_test_[a-z0-9_]+|_private)?$')][string]$Database,
  [Parameter(Mandatory)][ValidateSet('jkxt','jkxt_test')][string]$DbUser,
  [Parameter(Mandatory)][string]$Output,
  [string]$Baseline
)
$ErrorActionPreference = 'Stop'
$docker = 'C:\Program Files\Docker\Docker\resources\bin\docker.exe'
function Query([string]$sql) {
  $value = & $docker exec $Container psql -X -v ON_ERROR_STOP=1 -U $DbUser -d $Database -Atc $sql
  if ($LASTEXITCODE -ne 0) { throw 'Database snapshot query failed' }
  return ($value -join "`n")
}
if ($Baseline) { $tables = (Get-Content -LiteralPath $Baseline -Raw | ConvertFrom-Json).tables }
else {
  $tables = Query "select json_agg(x) from (select table_name, json_agg(column_name order by ordinal_position) as columns from information_schema.columns where table_schema='public' and table_name <> 'flyway_schema_history' group by table_name order by table_name) x" | ConvertFrom-Json
}
$rows = foreach ($table in $tables) {
  if ($table.table_name -notmatch '^[a-z_][a-z0-9_]*$') { throw 'Unsafe table name' }
  $fields = @($table.columns | Where-Object { -not ($table.table_name -in @('teacher','organization') -and $_ -eq 'updated_at') })
  if (@($fields | Where-Object { $_ -notmatch '^[a-z_][a-z0-9_]*$' }).Count) { throw 'Unsafe column name' }
  $expressions = foreach ($field in $fields) {
    # Only V19's documented appearance default is removed; all other organization settings remain compared.
    $expression = if ($table.table_name -eq 'organization' -and $field -eq 'settings') { "t.settings - 'appearance'" }
      elseif ($table.table_name -eq 'role' -and $field -eq 'updated_at') { "case when t.code='TEACHER_QUERY' then null else t.updated_at end" }
      elseif ($table.table_name -eq 'role' -and $field -eq 'permissions') { "case when t.code='TEACHER_QUERY' then (select jsonb_agg(p order by p) from (select distinct p from jsonb_array_elements_text((t.permissions - 'tenant:read') || '[`"duty:self`"]'::jsonb) p) q) else t.permissions end" }
      else { "t.`"$field`"" }
    "'$field', $expression"
  }
  $projection = 'jsonb_build_object(' + ($expressions -join ',') + ')'
  $result = Query ("select json_build_object('count',count(*),'digest',md5(coalesce(string_agg(row_hash,',' order by row_hash),''))) from (select md5(($projection)::text) row_hash from public.`"" + $table.table_name + '" t) x') | ConvertFrom-Json
  [ordered]@{table_name=$table.table_name; columns=@($table.columns); count=$result.count; digest=$result.digest}
}
$record = [ordered]@{capturedAt=(Get-Date).ToUniversalTime().ToString('o'); container=$Container; database=$Database; migration=(Query "select max(version::integer) from flyway_schema_history where success"); tables=@($rows)}
$record | ConvertTo-Json -Depth 10 | Set-Content -LiteralPath $Output -Encoding utf8
[ordered]@{output=$Output; migration=$record.migration; tableCount=@($rows).Count; sha256=(Get-FileHash -LiteralPath $Output -Algorithm SHA256).Hash} | ConvertTo-Json
