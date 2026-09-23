-- Read-only business-row fingerprint; excludes session registries and Flyway history.
SELECT format('select %L || %L || md5(coalesce(jsonb_agg(to_jsonb(t) order by to_jsonb(t)::text)::text,%L)) from public.%I t',
  tablename, ':', '[]', tablename)
FROM pg_tables WHERE schemaname='public' AND tablename NOT LIKE 'demo_%'
  AND tablename NOT LIKE 'portal_session_%' AND tablename <> 'flyway_schema_history'
ORDER BY tablename
\gexec
