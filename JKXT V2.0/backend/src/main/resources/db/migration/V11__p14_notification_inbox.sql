-- P14 in-app notification inbox: per-user read tracking for the org-level notification feed.
-- Notifications themselves already exist (written on schedule publish); this adds the read state
-- so the in-app 通知中心 can show unread counts and support mark-read / mark-all-read per user.
create table if not exists notification_read (
  notification_id uuid not null references notification (id) on delete cascade,
  user_id         uuid not null,
  org_id          uuid not null,
  read_at         timestamptz not null default now(),
  primary key (notification_id, user_id)
);

create index if not exists idx_notification_read_user on notification_read (org_id, user_id);
create index if not exists idx_notification_org_created on notification (org_id, created_at desc);
