# ContactNexus-Service

Spring Boot 4 + Java 21 backend for [ContactNexus-UI](https://github.com/sachin-gupta99/ContactNexus-UI).
Config is sourced from **AWS Systems Manager Parameter Store** — same pattern as
[FitTrack-tracking-service](https://github.com/sachin-gupta99/FitTrack-tracking-service) and
[CascadeAI-Orchestrator](https://github.com/sachin-gupta99/CascadeAI-Orchestrator).

## Stack

- Java 21, Maven
- Spring Boot 4.0.5 (webmvc, data-jpa, validation, actuator)
- PostgreSQL (Neon)
- AWS SDK v2 — SSM Parameter Store for secrets/config
- Lombok, Jakarta Bean Validation

## Parameter Store setup

Create three parameters in AWS SSM (region `ap-south-1`), using **SecureString** for the password:

| Parameter | Type | Value |
| --- | --- | --- |
| `/ContactNexus/Service/postgres-db/url` | String | `jdbc:postgresql://<host>/<db>?sslmode=require&channelBinding=require` |
| `/ContactNexus/Service/postgres-db/username` | String | `neondb_owner` |
| `/ContactNexus/Service/postgres-db/password` | SecureString | `<neon-password>` |

Quick CLI:

```bash
aws ssm put-parameter --name /ContactNexus/Service/postgres-db/url      --type String       --value 'jdbc:postgresql://ep-...neon.tech/contact-nexus?sslmode=require&channelBinding=require'
aws ssm put-parameter --name /ContactNexus/Service/postgres-db/username --type String       --value 'neondb_owner'
aws ssm put-parameter --name /ContactNexus/Service/postgres-db/password --type SecureString  --value '<secret>'
```

## Credentials

The app uses `DefaultCredentialsProvider`, so any of these works:

- `~/.aws/credentials` + `~/.aws/config` (local dev via `aws configure`)
- `AWS_ACCESS_KEY_ID` / `AWS_SECRET_ACCESS_KEY` env vars
- EC2/ECS task role (production) — preferred, no keys on the box

The IAM identity needs `ssm:GetParameter` on the three paths above (plus `kms:Decrypt` on the key used for the SecureString).

## Run locally

```bash
./mvnw spring-boot:run
```

Actuator health: `http://localhost:8080/actuator/health`.

## API

All endpoints require an `X-User-Id` header (placeholder until auth is wired).

| Method | Path | Description |
| --- | --- | --- |
| `GET` | `/api/v1/contacts?q=&page=&size=` | List contacts (paged, searchable) |
| `GET` | `/api/v1/contacts/{id}` | Fetch one |
| `POST` | `/api/v1/contacts` | Create |
| `PUT` | `/api/v1/contacts/{id}` | Update |
| `DELETE` | `/api/v1/contacts/{id}` | Delete |

### Example

```bash
curl -X POST http://localhost:8080/api/v1/contacts \
  -H 'Content-Type: application/json' \
  -H 'X-User-Id: 1' \
  -d '{"firstName":"Ada","email":"ada@example.com"}'
```

## Project structure

```
src/main/java/com/sachingupta/contactnexus/
  ContactNexusServiceApplication.java
  common/
    GlobalExceptionHandler.java
    NotFoundException.java
  config/
    AwsSSMConfig.java         # SsmClient bean
    PostgreDBConfig.java      # DataSource bean, resolves SSM values
    PostgreDBProperties.java  # Maps db.* paths from application.yml
  service/
    ParameterStoreService.java
  contact/
    Contact.java              # JPA entity
    ContactRepository.java    # Spring Data
    ContactService.java
    ContactController.java
    dto/
      ContactRequest.java     # record, validated
      ContactResponse.java    # record
```

## Roadmap

- [ ] Spring Security + JWT (replace `X-User-Id` header)
- [ ] User service and ownership checks
- [ ] Image upload (S3)
- [ ] Dockerfile + ECS Fargate task definition
- [ ] Terraform module for SSM parameters + ECS deployment
