# Deploying ContactNexus-Service to Fly.io

This repo deploys to [Fly.io](https://fly.io) as a single Docker container. Pushes to `main` auto-deploy via GitHub Actions.

## One-time setup

### 1. Install flyctl locally and sign up

```bash
# Windows (PowerShell): iwr https://fly.io/install.ps1 -useb | iex
# macOS/Linux:          curl -L https://fly.io/install.sh | sh
fly auth signup   # or `fly auth login` if you already have an account
```

Fly's free allowance covers up to three `shared-cpu-1x` machines with 256MB each. This app needs 512MB (bumped in `fly.toml`), so expect to run one machine on the free plan.

### 2. Create the app

From the repo root:

```bash
fly launch --no-deploy --copy-config --name <your-app-name>
```

- `--no-deploy` skips the first build so we can wire up secrets first.
- `--copy-config` uses the checked-in `fly.toml` instead of regenerating it. Edit the `app` name there to match.

### 3. Set production secrets

```bash
# JWT signing key - at least 32 characters, random. Generate one with:
#   openssl rand -base64 48
fly secrets set JWT_SECRET='...'

# Neon Postgres connection. Use the pooled URL from Neon's dashboard.
# Password-in-URL works; we read these as direct values when they do not
# start with a slash (SSM paths start with /).
fly secrets set \
  DB_URL='jdbc:postgresql://ep-xxx-pooler.ap-south-1.aws.neon.tech/contactnexus?sslmode=require' \
  DB_USERNAME='<neon-user>' \
  DB_PASSWORD='<neon-password>'

# Lock CORS to the UI's production origin. Comma-separated for multiple.
fly secrets set CORS_ALLOWED_ORIGINS='https://<your-ui>.vercel.app'
```

Secrets land in `/.fly/secrets.env` inside the machine as env vars. Spring picks them up because every placeholder in `application.yml` resolves from `${ENV_VAR_NAME:default}`.

### 4. First deploy

```bash
fly deploy --remote-only
```

`--remote-only` runs the Docker build on Fly's builders instead of your laptop. First build takes ~3 minutes (Maven dependency download); subsequent builds ~45 seconds.

### 5. Wire up GitHub Actions auto-deploy

```bash
fly tokens create deploy -a <your-app-name>
```

Copy the token into the repo's GitHub Settings -> Secrets and variables -> Actions -> New repository secret:

- Name: `FLY_API_TOKEN`
- Value: (the token)

Every push to `main` now triggers `.github/workflows/fly-deploy.yml`.

## Cross-origin cookies in production

The refresh cookie is `HttpOnly`, path-scoped, and defaults in `application.yml` to `SameSite=Lax` + `Secure=false` for localhost. `fly.toml` overrides those to `SameSite=None` + `Secure=true`, which is required when the UI (`*.vercel.app`) and API (`*.fly.dev`) are on different registrable domains.

If you later route `/api/*` through a Vercel rewrite to Fly (so the browser sees same-origin), you can drop `SameSite=None` back to `Lax` by overriding `JWT_REFRESH_COOKIE_SAME_SITE=Lax` + `JWT_REFRESH_COOKIE_SECURE=true` as Fly secrets.

## Memory tuning

The Dockerfile sets `JAVA_OPTS="-Xmx400m -XX:+UseSerialGC -XX:TieredStopAtLevel=1"` to keep Spring Boot inside a 512MB machine. Signs you need more:

- OOMKilled restarts in `fly logs`.
- `Metaspace` growth over hours.

Bump the machine up to 1GB via `fly.toml` `[[vm]]` -> `memory = "1gb"` (no longer free) or trim dependencies.

## Scale-to-zero caveat

`auto_stop_machines = "stop"` means the VM halts after a minute of idle. The next request pays a ~20-30 second cold start while the JVM boots. For a live demo, set `min_machines_running = 1` instead - still inside free allowance with one machine.

## Operational commands

```bash
fly logs                  # tail logs
fly status                # machine state, deploy history
fly ssh console           # shell into the running container
fly scale count 1         # pin one always-on machine
fly releases              # deploy history + rollback targets
fly releases rollback N   # roll back to release N
```


