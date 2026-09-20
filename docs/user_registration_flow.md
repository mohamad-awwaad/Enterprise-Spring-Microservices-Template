# User Self-Registration Flow

This document describes the user self-registration flow, including security design decisions and the complete technical implementation.

## Overview

The registration flow allows new users to create accounts without admin intervention. It uses a two-phase confirmation process to prevent spam accounts and ensure email ownership.

## Flow Diagram

```
┌─────────────┐     ┌─────────────┐     ┌─────────────┐     ┌──────────────────┐     ┌──────────┐
│   Angular   │     │     BFF     │     │   Gateway   │     │ Profile Service  │     │ Keycloak │
│     UI      │     │             │     │             │     │                  │     │          │
└──────┬──────┘     └──────┬──────┘     └──────┬──────┘     └────────┬─────────┘     └────┬─────┘
       │                   │                   │                     │                    │
       │ 1. POST /public/profile/register      │                     │                    │
       │──────────────────>│                   │                     │                    │
       │                   │ 2. POST /profile/public/register        │                    │
       │                   │──────────────────>│                     │                    │
       │                   │                   │  3. POST /api/public/register            │
       │                   │                   │────────────────────>│                    │
       │                   │                   │                     │                    │
       │                   │                   │     4. Create disabled profile           │
       │                   │                   │     + pending registration               │
       │                   │                   │     (no password collected)              │
       │                   │                   │                     │                    │
       │                   │                   │     5. Send confirmation email           │
       │                   │                   │                     │ ─ ─ ─ ─ ─ ─ ─ ─ >  │
       │                   │                   │                     │                   USER
       │                   │                   │<────────────────────│                    │
       │                   │<──────────────────│                     │                    │
       │<──────────────────│                   │                     │                    │
       │  "Check email"    │                   │                     │                    │
       │                   │                   │                     │                    │
══════════════════════════════════════════════════════════════════════════════════════════════════
                                    USER CLICKS EMAIL LINK
══════════════════════════════════════════════════════════════════════════════════════════════════
       │                   │                   │                     │                    │
       │ 6. GET /public/profile/confirm?token=xxx                    │                    │
       │──────────────────>│                   │                     │                    │
       │                   │ 7. GET /profile/public/confirm?token=xxx                     │
       │                   │──────────────────>│                     │                    │
       │                   │                   │  8. GET /api/public/confirm              │
       │                   │                   │────────────────────>│                    │
       │                   │                   │                     │                    │
       │                   │                   │                     │  9. Create user    │
       │                   │                   │                     │   (via admin API)  │
       │                   │                   │                     │───────────────────>│
       │                   │                   │                     │                    │
       │                   │                   │                     │  10. Send password │
       │                   │                   │                     │      email         │
       │                   │                   │                     │<───────────────────│
       │                   │                   │                     │                   USER
       │                   │                   │     11. Enable profile, delete pending   │
       │                   │                   │<────────────────────│                    │
       │                   │<──────────────────│                     │                    │
       │<──────────────────│                   │                     │                    │
       │  "Set password"   │                   │                     │                    │
       │                   │                   │                     │                    │
       │                   │                   │                     │                    │
```

Step 9 (`Create user (via admin API)`) is Profile Service calling keycloak-admin-service's
`/api/users/register`, which now requires a bearer token with the `INTERNAL_SERVICE` realm role.
Profile Service obtains this token itself via a client-credentials grant for the `internal`
client registration (`internal-client`) before making the call - see `KeycloakAdminClient`.

## Security Design Decisions

### 1. Keycloak User Created AFTER Email Confirmation

**Decision:** The Keycloak user is only created after the user confirms their email address.

**Rationale:**
- Prevents spam bots from creating accounts in the Identity Provider
- Keeps Keycloak user base clean (only verified users)
- Unconfirmed registrations are isolated to our Profile Service database
- Easy to implement cleanup jobs for expired pending registrations

### 2. No Password Collected at Registration

**Decision:** The registration form never asks for a password. The final password is set entirely
via Keycloak's own "Set Password" email, sent after email confirmation.

**Rationale:**
- Our services never see, store, or process the user's password at any point
- Earlier revisions of this flow collected a password on the form and BCrypt-hashed it into
  `PendingRegistrationEntity`, but nothing ever read that hash back - it was pure unused
  overhead (and a value worth protecting for no benefit). It has been removed entirely.
- Not collecting a password up front also means the user only ever sets it once, directly with
  the IdP, using Keycloak's own secure flow

### 3. No Email Enumeration on Registration

**Decision:** `POST /register` always returns the same `201 Created` response, whether the email
was free, already registered, or lost a race between a duplicate-check and a concurrent insert:

```json
{
  "message": "If this email is not registered yet, a confirmation link has been sent.",
  "email": "user@example.com"
}
```

**Rationale:**
- Whether a given email already has an account is itself sensitive information: confirming it
  (e.g. via a `409 Conflict`, as this endpoint used to return) lets an attacker build a list of
  valid addresses to target with credential stuffing or password-reset abuse.
- For an already-registered email, nothing is created and no confirmation email is sent -
  `RegistrationService#register` logs the attempt at INFO for operational visibility, but the
  HTTP response is indistinguishable from a fresh registration.
- The rare race (two concurrent requests for the same address both pass the initial existence
  check) is caught via the database's unique constraint on `user_profiles.email`, surfaced as a
  `DataIntegrityViolationException` from a dedicated `RegistrationPersistenceService` bean run in
  its own transaction - see the Javadoc on `RegistrationService#register` for why that split is
  necessary.

### 4. Confirmation Token Security

**Design:**
- Token: UUID v4 (122 bits of cryptographic randomness)
- Stored: Unique constraint in database
- Expiry: Configurable, default 24 hours
- Single-use: Deleted after successful confirmation

**Properties:**
- Unpredictable (cannot be guessed)
- Time-limited (prevents indefinite validity)
- Non-reusable (prevents replay attacks)

### 5. Profile Enabled Flag

**Design:** UserProfile has an `enabled` boolean field (default: false).

**Rationale:**
- Provides application-level control beyond Keycloak's user enabled status
- Can be used in business logic to restrict unconfirmed users
- Allows soft-disable of users without touching Keycloak

## API Endpoints

### Registration Endpoint

```
POST /bff/public/profile/register
Content-Type: application/json

{
  "email": "user@example.com",
  "firstName": "John",
  "lastName": "Doe",
  "mobileNumber": "+1234567890",
  "gender": "MALE",
  "age": 30
}

Response: 201 Created
{
  "message": "If this email is not registered yet, a confirmation link has been sent.",
  "email": "user@example.com"
}
```

Note: no `username` or `password` field - email is the username end to end, and the password is
set only through Keycloak's own "Set Password" email (see Security Design Decision #2 above).
The response above is returned identically whether or not `email` was already registered - see
Security Design Decision #3 (no email enumeration).

### Confirmation Endpoint

```
GET /bff/public/profile/confirm?token=<uuid>

Response: 200 OK
{
  "message": "Email confirmed. Please check your email to set your password.",
  "email": "user@example.com"
}
```

### URL Pattern

Public endpoints follow a consistent pattern (simplified routing):
- **BFF**: `/bff/public/{service}/{path}`
- **Gateway**: `/{service}/public/{path}`
- **Service**: `/api/public/{path}` (service name stripped by gateway)

Example flow for registration:
```
Angular → /bff/public/profile/register
BFF → /profile/public/register (to Gateway)
Gateway → /api/public/register (to Profile Service)
```

## Database Schema

### UserProfile (extended)

| Column | Type | Notes |
|--------|------|-------|
| id | BIGINT | Primary key |
| user_id | VARCHAR | Keycloak user ID (set after confirmation) |
| email | VARCHAR | Unique |
| mobile_number | VARCHAR | Optional |
| enabled | BOOLEAN | Default: false |
| ... | ... | Other profile fields |

### PendingRegistration

| Column | Type | Notes |
|--------|------|-------|
| id | BIGINT | PK, FK to UserProfile |
| confirmation_token | VARCHAR | Unique, UUID v4 |
| token_expiry | TIMESTAMP | When token becomes invalid |
| confirmation_type | VARCHAR | EMAIL or MOBILE |
| created_at | TIMESTAMP | For cleanup jobs |

No password column: the self-registration flow never collects one (see Security Design Decision
#2 above). The schema is managed by Flyway (`profile-service/src/main/resources/db/migration`);
migration `V2__drop_password_hash.sql` removes the leftover `password_hash` column from databases
created before this change.

## Configuration

### Profile Service (application.properties)

```properties
# Registration settings
app.registration.token-expiry-hours=24
app.registration.confirmation-base-url=http://localhost:4200/confirm
```

### Keycloak Admin Service

Requires admin client with the following realm roles:
- `manage-users` - Create users
- `view-users` - Query users (optional)

## Angular Components

| Component | Path | Purpose |
|-----------|------|---------|
| RegisterComponent | /register | Registration form |
| ConfirmComponent | /confirm | Token validation display |

## Production Considerations

See `docs/PRODUCTION_CHECKLIST.md` for:
- SMTP configuration for email delivery
- Keycloak email settings for password reset
- Rate limiting recommendations
- CAPTCHA integration suggestions

## Error Handling

| Error | HTTP Status | Description |
|-------|-------------|-------------|
| Email already registered | 201 Created (same generic response) | No longer a distinct error - see Security Design Decision #3 (no email enumeration). User should use "forgot password" if they don't receive a confirmation email they expect. |
| Invalid token | 400 Bad Request | Token not found |
| Token expired | 400 Bad Request | Must re-register |
| Keycloak error | 500 Internal | User creation failed |

## Future Enhancements

1. **SMS Confirmation:** Use `ConfirmationType.MOBILE` for phone verification
2. **Social Login:** Allow OAuth2 registration via Google, GitHub, etc.
3. **Admin Approval:** Optional admin approval step before confirmation
4. **Invitation-Only:** Disable public registration, require invite links
