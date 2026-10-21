-- Round 9, PB-18 (OPS-01): V9 seeded a demo backup_record 'BAK-P10-SEED' with status 'succeeded',
-- a fake checksum ('p10-seed-checksum'), 4200 MB and storage 'local-snapshot', but no artifact
-- ever existed. A backup list must never show a "succeeded" backup that cannot be restored.
-- V9 is not edited (Flyway checksum); this forward migration removes exactly that fabricated row.
-- The match is on every seeded value, so a real backup can never be affected, and on databases where
-- the row was already removed or changed this is a no-op. Nothing references backup_record.
DELETE FROM backup_record
WHERE id = '00000000-0000-0000-0000-000000093001'
  AND backup_no = 'BAK-P10-SEED'
  AND checksum = 'p10-seed-checksum'
  AND storage = 'local-snapshot'
  AND size_mb = 4200.00
  AND payload ->> 'source' = 'p10-seed';
