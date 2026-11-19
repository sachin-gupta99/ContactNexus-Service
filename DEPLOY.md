# Deploying ContactNexus-Service to Render

This repo deploys to [Render](https://render.com) as a single Docker container. Render's own GitHub app handles the CI/CD - every push to `main` redeploys automatically, no `.github/workflows` entry needed.

Why Render: free tier covers one 512MB web service (no credit card required for signup), accepts the committed `Dockerfile`, and auto-deploys on push. The trade-off is scale-to-zero: after 15 minutes of no traffic the instance halts, and the next request eats a ~30-45 second cold start.

## One-time setup

### 1. Sign in to Render

Go to [render.com](https://render.com) and sign up **with GitHub**. Grant it access to the `ContactNexus-Service` repo when prompted (either all repos or just this one).

### 2. Create the service from the blueprint

The committed `render.yaml` is a Render Blueprint - it describes the service, so you don't have to fill in the dashboard form.

- **New +** -> **Blueprint**
- Connect `sachin-gupta99/ContactNexus-Service`
- Render reads `render.yaml`, previews the plan (one web service on the free tier), and asks you to apply it.
- Confirm.

The service is created but the first deploy will fail until secrets are set.

### 3. Set the four secrets

Service page -> **Environment** -> **Add Environment Variable**. All four are marked `sync: false` in the blueprint, meaning Render will not overwrite them from git; set them in the dashboard once and they persist.

| Key | Value |
|---|---|
| `JWT_SECRET` | `openssl rand -base64 48` output, at least 32 characters |
| `DB_URL` | `jdbc:postgresql://<neon-pooler-host>/<db>?sslmode=require` |
| `DB_USERNAME` | From Neon dashboard -> Connection Details -> Parameters only |
| `DB_PASSWORD` | Same place |

Use the **pooled** Neon host (ends in `-pooler`) so a scale-up doesn't exhaust Postgres connections. The `jdbc:` prefix is mandatory - Spring won't parse a bare `postgresql://` URL.

### 4. Trigger the first deploy

Save the env vars. Render auto-redeploys. Or hit **Manual Deploy** -> **Deploy latest commit**.

Logs stream live in the dashboard. First build takes ~5 minutes (Maven dependency download). Subsequent builds are faster thanks to Render's layer cache.

### 5. Confirm it's live

```bash
curl https://contactnexus-service.onrender.com/actuator/health
```

Expect `{"status":"UP"}`. If the instance has been idle, the first request takes ~30-45 seconds while the container spins back up.

## What's auto-wired after this

| Trigger | Result |
|---|---|
| Push to `main` | Production redeploys |
| Push to any other branch | Nothing (free plan doesn't include preview environments) |
| Open a PR | Nothing on Render; CI still runs if configured |
| Change `render.yaml` | Next deploy picks up the new config |

## Cross-origin cookies in production

Since the UI (`*.vercel.app`) and API (`*.onrender.com`) live on different registrable domains, the browser treats requests as cross-origin. That forces two things on the refresh cookie:

- `SameSite=None`
- `Secure=true`

Both are set in `render.yaml` via `JWT_REFRESH_COOKIE_SAME_SITE` and `JWT_REFRESH_COOKIE_SECURE`. Render terminates TLS automatically, so `Secure=true` is fine.

If you later route `/api/*` through a Vercel rewrite to Render (so the browser sees the API as same-origin), you can drop these back to `SameSite=Lax` + `Secure=true` in the dashboard - that's what the committed `vercel.json` does.

Lock `CORS_ALLOWED_ORIGINS` (dashboard) to the exact UI origin, no trailing slash, comma-separated for multiple: `https://your-ui.vercel.app,https://your-custom-domain.com`. Never use `*` with `allowCredentials(true)` - the browser will refuse the response.

## Memory tuning

The Dockerfile sets `JAVA_OPTS="-Xmx400m -XX:+UseSerialGC -XX:TieredStopAtLevel=1"` to keep Spring Boot inside the 512MB free-tier container. Signs you need more:

- `OOMKilled` entries in the deploy logs.
- Metaspace growth over hours.

Fix by moving to the Starter plan (~$7/mo, 512MB guaranteed, no spin-down) or trimming dependencies.

## Scale-to-zero caveat

Render's free tier halts the container after 15 minutes of no inbound traffic. The next request pays a ~30-45 second cold start while the JVM boots. For a live demo:

- Upgrade to Starter plan (eliminates the spin-down), or
- Keep it on the free tier and accept the cold start, or
- Pair with [UptimeRobot](https://uptimerobot.com) pinging `/actuator/health` every 5 minutes - but note that Render's TOS discourages keep-alive pings on free, so your app may get flagged.

## Operational

```bash
curl https://contactnexus-service.onrender.com/actuator/health   # health
# Logs:    dashboard -> Logs tab (live stream + 7-day retention)
# Shell:   dashboard -> Shell tab (free plan too, one session at a time)
# Rollback: dashboard -> Deploys -> any prior deploy -> Rollback
```

## When to leave Render

The free plan works for a portfolio/demo. If the app is going to see real traffic, outgrow it before spin-down or memory becomes annoying - $7/mo Starter removes spin-down, $25/mo Pro gives real resources. Or move the backend to Fly (now paid), AWS Elastic Beanstalk (12 months free), or Oracle Cloud Free Tier (always-free ARM VM, more hands-on).
