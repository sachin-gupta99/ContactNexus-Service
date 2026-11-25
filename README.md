# ContactNexus-Service

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

### Password reset

- `POST /auth/forgot-password { email }` — always returns 200 (enumeration-safe). On a hit, issues a 32-byte random token, SHA-256-stores it, and emails a 30-minute link to `${app.base-url}/auth?mode=reset&token=<raw>`.
- `POST /auth/reset-password { token, newPassword }` — validates (not used, not expired, hash match), bcrypts the new password, marks the row `used_at = now` (single-use), **revokes every refresh-token family for that user**, and sends a "password changed" confirmation email. 200 on success, 400 on bad/expired token.

The revoke-all step closes the "attacker has a stolen refresh cookie" window the moment the real user resets.

### Rate limits

Per-IP token-bucket limiter (`RateLimitFilter`) in front of the auth chain, Bucket4j in-memory:

| Endpoint | Limit | Why |
|---|---|---|
| `/login` | 10 per 5 min | Allows mistyping, slows brute force |
| `/signup` | 5 per hour | Caps mass account creation |
| `/forgot-password` | 3 per hour | Protects email-provider quota |
| `/reset-password` | 10 per hour | Defence against token brute force |
| `POST /change-password` + `DELETE /users/me` (shared) | 10 per 15 min | Both re-check the password; stops a stolen access token being used to brute-force it |

Exceeding the limit returns 429 with an RFC 6585 `Retry-After` header.

### Endpoints

| Method | Path | Auth | Notes |
| --- | --- | --- | --- |
| `POST` | `/api/v1/auth/signup` | public | returns `{ accessToken, expiresInSeconds, user }` + sets cookie |
| `POST` | `/api/v1/auth/login` | public | same shape |
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
| `GET` | `/api/v1/contacts?q=&favorite=&page=&size=&sort=` | bearer | list (paged, searchable, `favorite=true` for starred only) — returns `PageResponse<ContactResponse>` |
| `GET` | `/api/v1/contacts/stats` | bearer | `{ total, favorites, addedThisWeek }` |
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

### SSM mode (optional)

If `db.url` is `/ContactNexus/database/directUrl` and the app has AWS credentials in scope (local `~/.aws/credentials`, env vars, or an EC2/ECS role), it resolves these three parameters at startup:

| Parameter | Type |
| --- | --- |
| `/ContactNexus/database/directUrl` | String |
| `/ContactNexus/database/username` | String |
| `/ContactNexus/database/password` | SecureString |

Needs `ssm:GetParameter` on `/ContactNexus/database/*` + `kms:Decrypt` on the key.

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
