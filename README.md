# ContactNexus-Service

Spring Boot 4 + Java 21 backend for [ContactNexus-UI](https://github.com/sachin-gupta99/ContactNexus-UI).
Live: [contactnexus-service.onrender.com](https://contactnexus-service.onrender.com) — try the full app at [contactnexus.vercel.app](https://contactnexus.vercel.app).

## Stack

- Java 21, Maven, Spring Boot 4.0.5 (webmvc, data-jpa, validation, actuator, security)
- PostgreSQL via [Neon](https://neon.tech), pooled connection + Hikari on the app side
- JJWT 0.12 for signed HS256 access tokens
- AWS SDK v2 — SSM Parameter Store (optional; direct env vars also supported)
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

A nightly cron (`RefreshTokenCleanup`, `0 15 3 * * *`) purges expired rows.

### Endpoints

| Method | Path | Auth | Notes |
| --- | --- | --- | --- |
| `POST` | `/api/v1/auth/signup` | public | returns `{ accessToken, expiresInSeconds, user }` + sets cookie |
| `POST` | `/api/v1/auth/login` | public | same shape |
| `POST` | `/api/v1/auth/refresh` | cookie | rotates cookie, returns new access token |
| `POST` | `/api/v1/auth/logout` | cookie | revokes server-side, clears cookie |
| `GET` | `/api/v1/auth/me` | bearer | current user |
| `PUT` | `/api/v1/users/me/avatar` | bearer, multipart | uploads profile image |
| `GET` | `/api/v1/contacts?q=&page=&size=` | bearer | list contacts (paged, searchable) |
| `GET` | `/api/v1/contacts/{id}` | bearer | fetch one |
| `POST` | `/api/v1/contacts` | bearer | create |
| `PUT` | `/api/v1/contacts/{id}` | bearer | update |
| `DELETE` | `/api/v1/contacts/{id}` | bearer | delete |

## Configuration

Values with a leading `/` are read from AWS SSM Parameter Store; anything else is treated as a literal env var. Lets the same JAR run on AWS (SSM) or Render / Fly / local Docker (plain env vars) without a config split.

| Key | Example | What |
| --- | --- | --- |
| `JWT_SECRET` | `my-random-base64...` | HS256 signing key (≥32 bytes). Alternatively set `jwt.secret-parameter` → `/ContactNexus/jwt/secret`. |
| `DB_URL` | `jdbc:postgresql://host/db?sslmode=require` | Spring `db.url`. Use the pooled Neon host. |
| `DB_USERNAME` / `DB_PASSWORD` | `neondb_owner` / `...` | Spring `db.username` / `db.password`. |
| `CORS_ALLOWED_ORIGINS` | `https://contactnexus.vercel.app` | Comma-separated. Never `*` — `allowCredentials=true`. |
| `JWT_REFRESH_COOKIE_SECURE` | `true` in prod | `Secure` flag on the cookie. |
| `JWT_REFRESH_COOKIE_SAME_SITE` | `None` cross-origin, `Lax` same-origin | SameSite attribute. |

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
mvn spring-boot:run
```

Health: `http://localhost:8080/actuator/health`.

## Project structure

```
src/main/java/com/sachingupta/contactnexus/
  ContactNexusServiceApplication.java       # @EnableScheduling root
  common/                                   # error handling
    GlobalExceptionHandler.java
    ConflictException.java, NotFoundException.java
  config/
    AwsSSMConfig.java                       # SsmClient bean (optional)
    PostgreDBConfig.java                    # DataSource, SSM-or-env resolver
    PostgreDBProperties.java
    StaticResourceConfig.java               # /uploads/** static mount
    WebConfig.java                          # CORS (CorsFilter at HIGHEST_PRECEDENCE)
  security/
    SecurityConfig.java                     # single SecurityFilterChain, STATELESS
    JwtConfig.java, JwtService.java         # HS256 signing + access-token issue/parse
    JwtAuthFilter.java                      # Authorization header → principal
    AppUserDetailsService.java, AuthUser.java
    RefreshToken.java, RefreshTokenRepository.java
    RefreshTokenService.java                # rotate + reuse detection
    RefreshCookie.java                      # HttpOnly cookie read/write/clear
    RefreshTokenCleanup.java                # daily purge of expired rows
  service/
    ParameterStoreService.java              # SSM GetParameter helper
  user/
    User.java, Profile.java, UserRepository.java
    UserService.java, AuthController.java, UserController.java
    AvatarStorage.java
    dto/ AuthResponse, LoginRequest, SignupRequest, UserResponse, ProfileDto
  contact/
    Contact.java, ContactRepository.java
    ContactService.java, ContactController.java
    dto/ ContactRequest, ContactResponse
```

## Deployment

Runs on **Render** as a Docker container, auto-deployed from `main` via Render's GitHub app. Blueprint (`render.yaml`) + Dockerfile are both committed. Full walkthrough: [DEPLOY.md](DEPLOY.md).

## Roadmap

- [x] Spring Security + JWT access tokens
- [x] Refresh tokens with rotation + reuse detection + HttpOnly cookie
- [x] User service + ownership checks on contact resources
- [x] Local filesystem avatar upload (`/uploads/**`)
- [x] Dockerfile + Render deploy
- [ ] Flyway migrations (drop `ddl-auto: update`)
- [ ] S3 avatar storage (`/ContactNexus/s3/*` already in SSM)
- [ ] Pagination + server-side search on `/contacts`
- [ ] Terraform module for the AWS-flavoured deployment (ECS Fargate + RDS + S3 + CloudFront)
- [ ] Testcontainers integration suite

## License

MIT — see [LICENSE](LICENSE).

<!-- heartbeat: 2026-11-19 -->
