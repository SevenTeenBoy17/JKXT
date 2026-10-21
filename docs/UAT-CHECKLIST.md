# JKXT V2.0 UAT Checklist

## Private Delivery Startup

| Check | Expected Result | Status |
| --- | --- | --- |
| Run `scripts/deploy/init-private.ps1` | Compose services start without errors | Pending |
| Open `http://127.0.0.1:18180/Dashboard.html` | Login page loads through Nginx | Pending |
| Open `http://127.0.0.1:18180/Dashboard.html?portal=super` | Separate super admin login page loads through Nginx | Pending |
| Call `/api/v1/health` | Returns `UP` | Pending |
| Review `.env.private` | Secrets changed, captcha debug plan decided | Pending |

## Login And Tenant Mode

| Check | Expected Result | Status |
| --- | --- | --- |
| Open normal tenant URL | `tenant.test / Tenant001!!!` and captcha debug answer are prefilled | Pending |
| Login as `tenant.test / Tenant001!!!` | Tenant admin lands in normal app shell | Pending |
| Login as `admin / Super001!!!` from `?portal=super` | Super admin can view platform surfaces | Pending |
| Login as `admin / Super001!!!` from the normal URL | Login is rejected with the separate-entry warning | Pending |
| Login as `jwc.demo / Demo@2025` | Legacy school admin still lands in normal app shell | Pending |
| Login as `ops.demo / Demo@2025` from `?portal=super` | Legacy local operator / super admin can view platform surfaces | Pending |
| Private mode tenant list | Only fixed demo school is visible | Pending |
| Create second tenant in private mode | Request is rejected | Pending |
| Browser storage audit | No LLM key or long-lived token is stored | Pending |

## Quick One-Click Scheduling

| Check | Expected Result | Status |
| --- | --- | --- |
| Create Quick task | Task id is returned | Pending |
| Upload/sample teacher workbook | Upload has sha256 and validation shape | Pending |
| Upload/sample classroom workbook | Upload has sha256 and validation shape | Pending |
| Upload/sample exam workbook | Upload has sha256 and validation shape | Pending |
| Validate imports | Status is `ready` | Pending |
| Start schedule job | Job id and events path returned | Pending |
| Poll schedule job | Ends as `succeeded` | Pending |
| Result page | Shows assignments, fairness 5, AI targets teacher/room/slot | Pending |
| Export XLSX | Download is non-empty | Pending |
| Export PDF | Download starts with `%PDF-` | Pending |

## SaaS Readiness

| Check | Expected Result | Status |
| --- | --- | --- |
| `.env.saas.example` reviewed | No real secrets committed | Pending |
| `DEPLOY_MODE=saas` config path | Backend starts with SaaS profile | Pending |
| SaaS LLM gateway | Uses `https://api.llm-token.cn/v1` when provider-backed AI is enabled; no key appears in frontend assets | Pending |
| Tenant context | Session org is default; `SUPER_ADMIN` can switch tenant with `X-Tenant-Id`; normal admins cannot | Pending |
| Super admin tenant table | Uses `/api/v1/admin/tenants` | Pending |

## Backup And Restore

| Check | Expected Result | Status |
| --- | --- | --- |
| Run `backup-private.ps1` | SQL backup and `.sha256` exist in `/backups` | Pending |
| Run `restore-drill-private.ps1` | Scratch DB restores successfully | Pending |
| Verify row counts | organization/app_user/exam_task/schedule_assignment counts are non-zero | Pending |
| Record RPO/RTO | RPO <= 1h, RTO <= 4h plan accepted | Pending |

## Security Gates

| Check | Expected Result | Status |
| --- | --- | --- |
| Unauthenticated API access | Returns `AUTH_UNAUTHENTICATED` | Pending |
| Private second-tenant creation | Returns 403 | Pending |
| Upload size/MIME policy | Large or invalid uploads are rejected or documented blocker | Pending |
| High-risk admin mutation | Audit log entry is written | Pending |
| Captcha debug | Disabled before production go-live | Pending |

## Evidence To Attach

- Terminal output from `docker compose -f docker-compose.private.yml --env-file .env.private config --quiet`
- Terminal output from `scripts/deploy/private-smoke.ps1`
- Screenshot of login page through `http://127.0.0.1:18180/Dashboard.html`
- Screenshot of Quick result page
- Backup checksum line
- Restore drill row-count output
