# ContactNexus-Service

Spring Boot 4 + Java 21 backend for [ContactNexus-UI](https://github.com/sachin-gupta99/ContactNexus-UI).

## Stack

- Java 21, Maven
- Spring Boot 4.0.5 (web, data-jpa, validation, actuator)
- H2 (dev) / PostgreSQL (prod) via env-driven datasource
- Lombok, Jakarta Bean Validation

## Run locally

```bash
./mvnw spring-boot:run
```

App starts on `http://localhost:8080`. H2 console at `/h2-console`, actuator at `/actuator/health`.

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
  contact/
    Contact.java            # JPA entity
    ContactRepository.java  # Spring Data
    ContactService.java
    ContactController.java
    dto/
      ContactRequest.java   # record, validated
      ContactResponse.java  # record
```

## Config

Switch to PostgreSQL by setting env vars:

```bash
DB_URL=jdbc:postgresql://localhost:5432/contactnexus
DB_USERNAME=postgres
DB_PASSWORD=postgres
DB_DRIVER=org.postgresql.Driver
```

## Roadmap

- [ ] Spring Security + JWT (replace `X-User-Id` header)
- [ ] User service and ownership checks
- [ ] Image upload (S3 or local)
- [ ] Dockerfile + Compose with Postgres
- [ ] Terraform-on-AWS deployment
