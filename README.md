# ContactNexus-Service

[![CI](https://github.com/sachin-gupta99/ContactNexus-Service/actions/workflows/ci.yml/badge.svg)](https://github.com/sachin-gupta99/ContactNexus-Service/actions/workflows/ci.yml)

Spring Boot 4 + Java 21 backend for [ContactNexus-UI](https://github.com/sachin-gupta99/ContactNexus-UI).
Live: [contactnexus-service.onrender.com](https://contactnexus-service.onrender.com) — try the full app at [contactnexus.vercel.app](https://contactnexus.vercel.app).

## Stack

- Java 21, Maven, Spring Boot 4.0.5 (webmvc, data-jpa, validation, actuator, security, mail, flyway)
- PostgreSQL via [Neon](https://neon.tech), pooled connection + Hikari on the app side
- Flyway for schema migrations (`ddl-auto: validate`)
- JJWT 0.12 for signed HS256 access tokens
- Bucket4j for per-IP rate limiting
- Brevo Transactional Email HTTPS API (swap-able via `EmailSender` abstraction)
- AWS SDK v2 — S3 for avatars (private bucket, presigned URLs); SSM Parameter Store (optional; direct env vars also supported)
- Lombok, Jakarta Bean Validation

## Authentication

Two-token setup designed around leak-resistance:

- **Access token** — HS256 JWT, **15-minute TTL**, carried in `Authorization: Bearer ...`. Stateless; verified by signature alone. Clients keep it in memory (never `localStorage`).
- **Refresh token** — opaque 48-byte random value, **7-day TTL**, SHA-256 hashed in Postgres, returned as an HttpOnly, path-scoped cookie (`cn_refresh`, `Path=/api/v1/auth`). The raw value never persists server-side.

### Rotation + reuse detection

Every call to `POST /api/v1/auth/refresh`:
1. Validates the incoming cookie's hash against `refresh_tokens`.
2. Marks the row `revoked_at = now`, issues a new refresh token in the same `family_id`, and returns a fresh access token.
3. If the incoming hash matches an **already-revoked** row → treats it as replay → revokes the entire family. Both the real user and the attacker are forced to re-authenticate.

A nightly cron (`RefreshTokenCleanup`, `0 15 3 * * *`) purges expired refresh and reset tokens.

### Email verification

- `POST /auth/signup` creates the account **unverified** and emails a 24-hour, single-use confirmation link (`${app.base-url}/auth?mode=verify&token=<raw>`, SHA-256 stored). It returns a 15-minute access token but **no refresh cookie**, so the client can finish profile setup (avatar upload) and nothing more.
- `POST /auth/login` with the right password on an unverified account returns **403** `{"code":"EMAIL_NOT_VERIFIED"}`. A wrong password still gets the generic 401, so verification state is only revealed to someone who knows the password.
- `POST /auth/verify-email { token }` spends the token, marks the address verified and signs the user in (sets the cookie).
- `POST /auth/resend-verification { email }` is enumeration-safe: always 200, sends only for an unverified account.
- Completing a password reset also marks the address verified: using the emailed link proves inbox ownership. It is also how the real owner reclaims an address someone else signed up with.
- Accounts that existed before V6 are treated as verified.

### Per-account lockout

- 5 wrong passwords in a row lock the account for 15 minutes, **even for the right password**. Response: 429 `{"code":"ACCOUNT_LOCKED","retryAfterSeconds":...}` with `Retry-After`. The owner gets an email.
- Emails with no account are counted in memory with the same thresholds and messages, so responses can't reveal which addresses are registered. Unknown emails also go through a bcrypt comparison, so timing doesn't reveal it either.
- A successful sign-in resets the counter; a password reset lifts the lock.
- Trade-off: anyone who knows an address can lock its owner out for 15 minutes. The lock is short, the owner is told, and a reset lifts it.

### Signed-in devices

Each refresh-token family is one device. `GET /auth/sessions` lists live families (user agent and IP from the newest token, when the family started, when it last refreshed, and which one is the caller's). `DELETE /auth/sessions/{id}` revokes one family; it is scoped to the caller, so another user's id returns 404. Both live under `/auth` so the path-scoped refresh cookie can identify "this device".

### Password reset

- `POST /auth/forgot-password { email }` — always returns 200 (enumeration-safe). On a hit, issues a 32-byte random token, SHA-256-stores it, and emails a 30-minute link to `${app.base-url}/auth?mode=reset&token=<raw>`.
- `POST /auth/reset-password { token, newPassword }` — validates (not used, not expired, hash match), bcrypts the new password, marks the row `used_at = now` (single-use), **revokes every refresh-token family for that user**, and sends a "password changed" confirmation email. 200 on success, 400 on bad/expired token.

The revoke-all step closes the "attacker has a stolen refresh cookie" window the moment the real user resets.

### Client IP (for rate limits and the devices list)

`X-Forwarded-For` alone can't be trusted: Render [appends to whatever chain the caller sent](https://render.com/articles/how-to-proxy-analytics-through-your-own-domain), so anyone calling the Render URL directly controls its left-most value. Vercel, by contrast, [overwrites it with the real visitor IP](https://vercel.com/docs/headers/request-headers). `ClientIpResolver` therefore:

1. trusts the left-most `X-Forwarded-For` only on requests carrying the shared secret Vercel adds on the `/api` rewrite (`X-Origin-Secret`, compared in constant time; see the UI's `vercel.json`);
2. otherwise uses the edge-set header (`CLIENT_IP_EDGE_HEADER`, `CF-Connecting-IP` on Render), never `X-Forwarded-For`;
3. otherwise the socket address (local dev).

Without `PROXY_SECRET` the old behaviour is kept (with a startup warning), so a half-finished rollout can't put every user behind one Vercel IP.

**Rollout order:** set `ORIGIN_SECRET` on Vercel and deploy the UI first, then set the same value as `PROXY_SECRET` on Render.

### Rate limits

Per-IP token-bucket limiter (`RateLimitFilter`) in front of the auth chain, Bucket4j in-memory:

| Endpoint | Limit | Why |
|---|---|---|
| `/login` | 10 per 5 min | Allows mistyping, slows brute force |
| `/signup` | 5 per hour | Caps mass account creation |
| `/forgot-password` | 3 per hour | Protects email-provider quota |
| `/reset-password` | 10 per hour | Defence against token brute force |
| `/verify-email` | 10 per hour | Defence against token brute force |
| `/resend-verification` | 3 per hour | Protects email-provider quota |
| `POST /change-password` + `DELETE /users/me` (shared) | 10 per 15 min | Both re-check the password; stops a stolen access token being used to brute-force it |
| `/contacts/import` | 20 per hour | Each call can insert up to 500 rows |
| `GET /public/**` | 60 per minute | Shared-card links; blunts token guessing |
| `POST /internal/jobs/**` | 10 per hour | Job trigger (also token-guarded) |

Exceeding the limit returns 429 with an RFC 6585 `Retry-After` header.

### Endpoints

| Method | Path | Auth | Notes |
| --- | --- | --- | --- |
| `POST` | `/api/v1/auth/signup` | public | creates an unverified account, emails a confirmation link; returns a 15-min access token, **no** cookie |
| `POST` | `/api/v1/auth/verify-email` | public | `{ token }`; verifies and signs in (sets cookie) |
| `POST` | `/api/v1/auth/resend-verification` | public | `{ email }`; always 200 |
| `GET` | `/api/v1/auth/sessions` | bearer + cookie | signed-in devices, with `current` |
| `DELETE` | `/api/v1/auth/sessions/{id}` | bearer | signs one device out; clears the cookie if it was this one |
| `POST` | `/api/v1/auth/login` | public | `{ accessToken, expiresInSeconds, user }` + cookie; 403 if unverified, 429 if locked |
| `POST` | `/api/v1/auth/refresh` | cookie | rotates cookie, returns new access token |
| `POST` | `/api/v1/auth/logout` | cookie | revokes server-side, clears cookie |
| `POST` | `/api/v1/auth/forgot-password` | public | always 200 (enumeration-safe) |
| `POST` | `/api/v1/auth/reset-password` | public | consumes a reset token, revokes all sessions |
| `POST` | `/api/v1/auth/change-password` | bearer | `{ currentPassword, newPassword }`; revokes every session, returns fresh tokens for this one, emails a confirmation. 400 if the current password is wrong |
| `POST` | `/api/v1/auth/logout-all` | bearer | revokes every refresh token for the user, clears cookie, 204 |
| `GET` | `/api/v1/auth/me` | bearer | current user |
| `PUT` | `/api/v1/users/me` | bearer | update name + profile fields |
| `PUT` | `/api/v1/users/me/avatar` | bearer, multipart | uploads avatar to S3, returns `{ imageUrl }` (presigned) |
| `DELETE` | `/api/v1/users/me` | bearer | `{ password }`; deletes the account, its contacts, tokens and avatar. 400 if the password is wrong |
| `GET` | `/api/v1/contacts?q=&favorite=&tag=&page=&size=&sort=` | bearer | list (paged, searchable, `favorite=true` for starred only, `tag=` case-insensitive). `sort` is whitelisted (`firstName`, `lastName`, `email`, `createdAt`, `updatedAt`, `favorite`, `lastContactedAt`, the last with nulls last) and repeatable, e.g. `sort=favorite,desc&sort=firstName,asc`. From 3 characters `q` is also typo-tolerant (pg_trgm `strict_word_similarity` on the full name: "rvi" finds Ravi) — returns `PageResponse<ContactResponse>` |
| `GET` | `/api/v1/contacts/{id}/interactions` | bearer | timeline, newest first (CALL / MEETING / EMAIL / MESSAGE / OTHER) |
| `POST` | `/api/v1/contacts/{id}/interactions` | bearer | `{ type, note?, occurredAt? }` (not in the future); updates `lastContactedAt` |
| `DELETE` | `/api/v1/contacts/{id}/interactions/{iid}` | bearer | recomputes `lastContactedAt` |
| `GET` | `/api/v1/contacts/birthdays?days=30` | bearer | `[{ contact, date, inDays, turning }]` soonest first |
| `POST` | `/api/v1/contacts/bulk` | bearer | `{ ids (≤500), action: FAVORITE\|UNFAVORITE\|ADD_TAGS\|REMOVE_TAGS\|DELETE, tags? }` → `{ affected }`; other users' ids ignored |
| `POST` | `/api/v1/contacts/merge` | bearer | `{ keepId, mergeIds, values, photoFromId? }`: chosen values, tags combined (unless given), timeline moved, favourite if any; others deleted |
| `PUT` | `/api/v1/users/me/preferences` | bearer | `{ birthdayReminders }` |
| `GET` / `PUT` / `DELETE` | `/api/v1/users/me/card-share` | bearer | shared-card settings: chosen fields (`EMAIL PHONE WORK ADDRESS SOCIAL BIO PHOTO`); PUT turns it on |
| `POST` | `/api/v1/users/me/card-share/rotate` | bearer | new link; the old one stops working |
| `GET` | `/api/v1/public/cards/{token}` | **public** | only the chosen fields (`Cache-Control: no-store`) |
| `POST` | `/api/v1/internal/jobs/birthday-reminders` | `X-Jobs-Token` | runs tomorrow's reminders; 404 unless `JOBS_TOKEN` is set |
| `GET` | `/api/v1/contacts/tags` | bearer | `[{ name, count }]`, alphabetical |
| `DELETE` | `/api/v1/contacts/tags/{name}` | bearer | removes the tag from every contact |
| `GET` | `/api/v1/contacts/duplicates` | bearer | groups sharing an email (ignoring case) or phone (last 10 digits) |
| `PUT` | `/api/v1/contacts/{id}/photo` | bearer, multipart | uploads to S3 under `avatars/{owner}/contacts/`; returns the contact with a presigned `imageUrl` and `hasPhoto: true` |
| `DELETE` | `/api/v1/contacts/{id}/photo` | bearer | removes the photo (S3 object deleted after commit) |
| `GET` | `/api/v1/contacts/stats` | bearer | `{ total, favorites, addedThisWeek, tags }` |
| `POST` | `/api/v1/contacts/import` | bearer | `{ contacts: [...] }`, max 500; skips emails you already have (case-insensitive) and returns `{ imported, skippedDuplicates }` |
| `GET` | `/api/v1/contacts/{id}` | bearer | fetch one |
| `POST` | `/api/v1/contacts` | bearer | create |
| `PUT` | `/api/v1/contacts/{id}` | bearer | update |
| `PUT` | `/api/v1/contacts/{id}/favorite` | bearer | `{ favorite: true\|false }` |
| `DELETE` | `/api/v1/contacts/{id}` | bearer | delete |

Password re-checks return **400, not 401**: the session is valid, only the typed password is wrong, and a 401 would make clients try to refresh the session.

## Schema

Flyway owns the schema (`ddl-auto: validate`). Migrations in `src/main/resources/db/migration/`:

| Version | What |
| --- | --- |
| `V1__init.sql` | `users`, `profiles`, `contacts`, `refresh_tokens` (+ indexes) baseline |
| `V2__password_reset_tokens.sql` | `password_reset_tokens` table |
| `V3__contacts_owner_id_index.sql` | `idx_contacts_owner_id` for paginated list queries |
| `V4__profile_avatar_s3_key.sql` | `profiles.image_key` for S3 avatars; clears dead local `/uploads` links |
| `V5__contacts_favorite.sql` | `contacts.favorite` boolean (default `false`) |
| `V6__email_verification_and_lockout.sql` | `users.email_verified` (existing rows set to true), `failed_login_attempts`, `locked_until`; `email_verification_tokens` |
| `V7__contact_tags_and_photos.sql` | `tags` (unique per owner ignoring case), `contact_tags` (cascading), `contacts.image_key` |
| `V8__notes_timeline_birthdays.sql` | `contacts.notes`, `last_contacted_at`, birthday month/day/year (+ check, partial index); `interactions` (cascading); `users.birthday_reminders`; `reminder_log` |
| `V9__card_shares.sql` | `card_shares` (token, chosen fields; cascades with the user) |
| `V10__trigram_search.sql` | `CREATE EXTENSION pg_trgm` (Neon supports it; the DB owner can create it) |

`baseline-on-migrate: true` + `baseline-version: 1` means an existing database (e.g. prod Neon populated from the old `ddl-auto: update` era) is stamped at V1 without re-running `V1__init.sql`. Fresh databases execute every migration in full.

## Configuration

Values with a leading `/` are read from AWS SSM Parameter Store; anything else is treated as a literal env var. Lets the same JAR run on AWS (SSM) or Render / local Docker (plain env vars) without a config split.

| Key | Example | What |
| --- | --- | --- |
| `JWT_SECRET` | `my-random-base64...` | HS256 signing key (≥32 bytes). Alternatively set `jwt.secret-parameter` → `/ContactNexus/jwt/secret`. |
| `DB_URL` | `jdbc:postgresql://host/db?sslmode=require` | Spring `db.url`. Use the pooled Neon host. |
| `DB_USERNAME` / `DB_PASSWORD` | `neondb_owner` / `...` | Spring `db.username` / `db.password`. |
| `CORS_ALLOWED_ORIGINS` | `https://contactnexus.vercel.app` | Comma-separated. Never `*` — `allowCredentials=true`. |
| `JWT_REFRESH_COOKIE_SECURE` | `true` in prod | `Secure` flag on the cookie. |
| `JWT_REFRESH_COOKIE_SAME_SITE` | `None` cross-origin, `Lax` same-origin | SameSite attribute. |
| `APP_BASE_URL` | `https://contactnexus.vercel.app` | UI origin; used to build password-reset links. |
| `APP_EMAIL_MODE` | `brevo` / `smtp` / `console` | Picks the `EmailSender` impl. Dev default is `console` (logs the email). |
| `APP_EMAIL_FROM` | `your-verified@gmail.com` | From address (must be verified with the chosen provider). |
| `BREVO_API_KEY` | `xkeysib-…` | Scoped to Transactional Email. Only needed when `APP_EMAIL_MODE=brevo`. |
| `MAIL_USERNAME` / `MAIL_PASSWORD` | Gmail + app password | Only needed when `APP_EMAIL_MODE=smtp` (does not work on Render free — SMTP egress blocked). |
| `S3_BUCKET` / `S3_REGION` | `contact-nexus` / `ap-south-1` | Avatar bucket (defaults shown). Keep all public access blocked. |
| `AWS_ACCESS_KEY_ID` / `AWS_SECRET_ACCESS_KEY` | IAM user keys | Least-privilege user scoped to `avatars/*` (see DEPLOY.md). Not needed on AWS with a task role. |
| `PROXY_SECRET` | `openssl rand -base64 32` | Must equal `ORIGIN_SECRET` on Vercel. Marks requests that really came through the Vercel rewrite. |
| `CLIENT_IP_EDGE_HEADER` | `CF-Connecting-IP` | Header the edge in front of the app sets itself; used for requests that bypass Vercel. Empty locally. |
| `JOBS_TOKEN` | `openssl rand -base64 32` | Enables `POST /internal/jobs/birthday-reminders`; same value as the `JOBS_TOKEN` GitHub secret used by `.github/workflows/daily-jobs.yml`. |
| `REMINDERS_ZONE` | `Asia/Kolkata` (default) | Birthdays are matched by calendar date in this zone; reminders go out at 08:00. |

### SSM mode (optional)

If `db.url` is `/ContactNexus/database/directUrl` and the app has AWS credentials in scope (local `~/.aws/credentials`, env vars, or an EC2/ECS role), it resolves these three parameters at startup:

| Parameter | Type |
| --- | --- |
| `/ContactNexus/database/directUrl` | String |
| `/ContactNexus/database/username` | String |
| `/ContactNexus/database/password` | SecureString |

Needs `ssm:GetParameter` on `/ContactNexus/database/*` + `kms:Decrypt` on the key.

### Birthday reminders

Every day the app emails each user (once) about contacts whose birthday is **tomorrow**; 29 February birthdays are reminded on the 28th in non-leap years. Render's free instance sleeps, so the in-app `@Scheduled` job (08:00 `REMINDERS_ZONE`) can't be relied on; `.github/workflows/daily-jobs.yml` calls the token-guarded job endpoint at 02:30 UTC instead, which also wakes the service. Each reminder is claimed in `reminder_log` (`insert ... on conflict do nothing`) before it is sent, so the two never double-send. Users can opt out in Settings.

## Run locally

```bash
export JWT_SECRET='a-random-base64-secret-at-least-32-bytes'
export DB_URL='jdbc:postgresql://...-pooler.ap-south-1.aws.neon.tech/contactnexus?sslmode=require'
export DB_USERNAME='...'
export DB_PASSWORD='...'
# APP_EMAIL_MODE defaults to `console` so reset links are logged to stdout.
mvn spring-boot:run
```

Health: `http://localhost:8080/actuator/health`. Flyway log on first boot shows every applied migration.

Tests:

- `mvn test`: unit tests (`*Test`, no database).
- `mvn verify`: unit tests plus integration tests (`*IT`). The integration tests boot the whole app with MockMvc against a **real PostgreSQL 16** started in-process (zonky embedded binaries, so no Docker locally or in CI), and Flyway runs every migration. They cover search escaping, tag filters and sorting, cascades, cross-user isolation (404s), import de-duplication, the auth flow (verification, refresh-token reuse, lockout) and the devices list. S3 and email are mocked.

## CI

[`.github/workflows/ci.yml`](.github/workflows/ci.yml) runs on every pull request and push to `main`:

- **Build and test**: `mvn verify` (unit + integration tests against embedded PostgreSQL 16) on Temurin 21 with the Maven cache. The Docker build skips tests, so this is the only place they run.
- **Docker image builds**: `docker build .`, so a Dockerfile change that would break the Render deploy fails here first.

## Project structure

```
src/main/java/com/sachingupta/contactnexus/
  ContactNexusServiceApplication.java       # @EnableScheduling root
  common/
    GlobalExceptionHandler.java             # @RestControllerAdvice
    ConflictException.java, NotFoundException.java
    PageResponse.java                       # stable JSON wrapper around Spring's Page
  config/
    AwsSSMConfig.java                       # SsmClient bean (optional)
    AwsS3Config.java                        # S3Client + S3Presigner beans
    PostgreDBConfig.java                    # DataSource, SSM-or-env resolver
    PostgreDBProperties.java
    WebConfig.java                          # CORS (CorsFilter at HIGHEST_PRECEDENCE)
  email/
    EmailSender.java                        # interface - swap transport without caller changes
    BrevoEmailSender.java                   # prod: Brevo Transactional Email HTTPS API
    SmtpEmailSender.java                    # Gmail etc. (requires outbound SMTP egress)
    ConsoleEmailSender.java                 # dev: logs the email to stdout
  security/
    SecurityConfig.java                     # single SecurityFilterChain, STATELESS
    RateLimitFilter.java                    # per-IP token-bucket on auth endpoints
    JwtConfig.java, JwtService.java         # HS256 signing + access-token issue/parse
    JwtAuthFilter.java                      # Authorization header -> principal
    AppUserDetailsService.java, AuthUser.java
    RefreshToken.java, RefreshTokenRepository.java
    RefreshTokenService.java                # rotate + reuse detection
    RefreshCookie.java                      # HttpOnly cookie read/write/clear
    RefreshTokenCleanup.java                # daily purge of expired rows (refresh + reset)
    PasswordResetToken.java, PasswordResetTokenRepository.java
    PasswordResetService.java               # issue, validate, consume + revoke-all
  service/
    ParameterStoreService.java              # SSM GetParameter helper
  user/
    User.java, Profile.java, UserRepository.java
    UserService.java, AuthController.java, UserController.java
    AvatarStorage.java                      # S3 put / presign / delete for avatars
    dto/ AuthResponse, LoginRequest, SignupRequest, UserResponse, ProfileDto,
         ForgotPasswordRequest, ResetPasswordRequest
  contact/
    Contact.java, ContactRepository.java
    ContactService.java, ContactController.java
    dto/ ContactRequest, ContactResponse

src/main/resources/
  application.yml
  db/migration/                             # Flyway V1..V3
```

## Deployment

Runs on **Render** as a Docker container, auto-deployed from `main` via Render's GitHub app. Blueprint (`render.yaml`) + Dockerfile are both committed. Full walkthrough: [DEPLOY.md](DEPLOY.md).

## Roadmap

- [x] Spring Security + JWT access tokens
- [x] Refresh tokens with rotation + reuse detection + HttpOnly cookie
- [x] User service + ownership checks on contact resources
- [x] Avatar upload to a private S3 bucket, served via presigned URLs
- [x] Dockerfile + Render deploy
- [x] Flyway migrations
- [x] Forgot / reset password with revoke-all-families on success
- [x] Brevo HTTPS email transport (SMTP egress blocked on Render free)
- [x] Server-side pagination + search on `/contacts`
- [x] Per-IP rate limiting on all unauthenticated auth endpoints
- [ ] Account-level login lockout (track attempts per user, progressive backoff)
- [ ] Caffeine-cache for the rate-limit buckets (TTL eviction)
- [ ] CAPTCHA on repeated forgot-password attempts
- [ ] Trigram index on `contacts(first_name, last_name, email)` once any user crosses a few thousand contacts
- [ ] Testcontainers integration suite covering the auth + reset flow
- [ ] Terraform module for the AWS-flavoured deployment (ECS Fargate + RDS + S3 + CloudFront)

## License

MIT — see [LICENSE](LICENSE).

<!-- heartbeat: 2026-11-20 -->
