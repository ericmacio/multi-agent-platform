# ADMIN.md — Bootstrap admin password (Flyway seed)

How to produce the BCrypt hash that `V002__seed_admin.sql` inserts into `users.password_hash`,
and how to feed it (plus the admin email) to the backend at startup.

---

## 1. What the migration expects

`backend/src/main/resources/db/migration/V002__seed_admin.sql`:

```sql
insert into users (email, password_hash, role, disabled, must_change_password)
values ('${app_bootstrap_admin_email}', '${app_bootstrap_admin_password_hash}', 'ADMIN', false, true);
```

Flyway substitutes the two `${...}` placeholders. In `application.yaml` they are wired to
environment variables (fail-fast if missing on a non-test profile):

```yaml
spring:
  flyway:
    placeholders:
      app_bootstrap_admin_email:         ${APP_BOOTSTRAP_ADMIN_EMAIL}
      app_bootstrap_admin_password_hash: ${APP_BOOTSTRAP_ADMIN_PASSWORD_HASH}
```

So you must provide **two** environment variables before starting the backend:

| Variable                            | Value                                              |
|-------------------------------------|----------------------------------------------------|
| `APP_BOOTSTRAP_ADMIN_EMAIL`         | The admin's email, lowercase (e.g. `admin@local`). |
| `APP_BOOTSTRAP_ADMIN_PASSWORD_HASH` | The **BCrypt hash** of the admin's password.       |

The cleartext password is **never** stored, logged, or passed to the JVM. Only the hash is.

---

## 2. Password policy (must be respected)

The cleartext you hash MUST satisfy the platform policy (`REQ-SEC-001`) — the `Password` value
object rejects anything else at first login:

- Minimum length: **10 characters**
- At least **one uppercase letter** (`A`–`Z`)
- At least **one special character** (non-alphanumeric)

The hash string itself must be a standard BCrypt string matching `^\$2[aby]\$.{56}$` — that is
what `BCryptPasswordEncoder` produces (cost factor 10 by default, matching
`BcryptPasswordHasherAdapter`).

---

## 3. How to compute the BCrypt hash

Do **not** use online BCrypt generators — you would be pasting a production credential into a
third-party website. Use one of the local options below. All of them use the same
`org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder` already on the project
classpath, so the produced hash is byte-compatible with what the runtime verifies at login.

### Option A — Ad-hoc JUnit test (recommended)

Simplest, no admin rights required. Add a temporary test, run it, copy the printed hash,
delete the test.

Create `backend/src/test/java/tools/HashAdminPassword.java`:

```java
package tools;

import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

class HashAdminPassword {

    @Test
    void print_hash() {
        String cleartext = "ChangeMe!42"; // <-- replace with your chosen password
        String hash = new BCryptPasswordEncoder().encode(cleartext);
        System.out.println("BCRYPT_HASH=" + hash);
    }
}
```

Run only that test to keep output focused:

```powershell
cd backend
.\mvnw.cmd -Dtest=HashAdminPassword test
```

Grep the line `BCRYPT_HASH=$2a$10$...` in the Surefire output. That is the value to feed as
`APP_BOOTSTRAP_ADMIN_PASSWORD_HASH`.

Delete the file once you have the hash — do not commit cleartext passwords.

### Option B — `jshell` one-liner

Requires the Spring Security jars on the JShell classpath. From the project root, after at
least one `.\mvnw.cmd package` (which populates `~\.m2\repository`):

```powershell
$cp = (.\mvnw.cmd -q dependency:build-classpath -Dmdep.outputFile=cp.txt "-f" backend/pom.xml; Get-Content backend/cp.txt)
jshell --class-path $cp
```

Then inside JShell:

```java
new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder().encode("ChangeMe!42")
```

JShell prints the hash literal.

### Option C — Python (`bcrypt` package)

If Python is installed and `pip install bcrypt` is allowed:

```powershell
python -c "import bcrypt; print(bcrypt.hashpw(b'ChangeMe!42', bcrypt.gensalt(rounds=10)).decode())"
```

Cost factor `10` matches Spring's default. The `$2b$` prefix is accepted by Spring's verifier.

---

## 4. Setting the env vars (Windows PowerShell)

For the **current session only** (recommended when running from a terminal):

```powershell
$env:APP_BOOTSTRAP_ADMIN_EMAIL = "admin@local"
$env:APP_BOOTSTRAP_ADMIN_PASSWORD_HASH = '$2a$10$xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx'
.\mvnw.cmd spring-boot:run
```

Notes:
- Use **single quotes** around the hash. The `$` characters in a BCrypt string are literal, not
  PowerShell variables — double quotes would try to expand them and mangle the value.
- No trailing spaces.
- For the **current user, persistent across sessions**:
  ```powershell
  [Environment]::SetEnvironmentVariable("APP_BOOTSTRAP_ADMIN_EMAIL", "admin@local", "User")
  [Environment]::SetEnvironmentVariable("APP_BOOTSTRAP_ADMIN_PASSWORD_HASH", '$2a$10$...', "User")
  ```
  Open a new terminal for the change to take effect.

If either variable is missing at startup, Spring fails fast — the seed cannot be silently
skipped.

---

## 5. First-login flow

The seed row is inserted with `must_change_password = true`. Per `REQ-USR-007`, the seeded admin
must change their password before any other operation is permitted:

1. `POST /auth/login` with the seed email + cleartext password → returns a JWT with
   `mustChangePassword: true`.
2. Any protected endpoint other than `PUT /auth/password` and `POST /auth/logout` is rejected
   with `403 MUST_CHANGE_PASSWORD` (`ForcedPasswordChangeFilter`).
3. `PUT /auth/password` with the new (policy-compliant) password → clears the flag.
4. The current JWT remains valid until natural expiry; subsequent logins produce tokens with
   `mustChangePassword: false`.

Once the admin has changed the password, the env-var-provided hash is no longer authoritative —
the DB row is. Rotating `APP_BOOTSTRAP_ADMIN_PASSWORD_HASH` after the fact has **no effect**;
Flyway will not re-run `V002` on an already-migrated schema.

---

## 6. Resetting the admin password (dev / recovery)

If the admin password is lost before the forced change has been performed, the cleanest recovery
in a local dev environment is:

```sql
-- Connect as the emk database owner
delete from users where email = 'admin@local';
-- Then remove the V002 row from flyway_schema_history so Flyway re-applies it on next boot:
delete from flyway_schema_history where script = 'V002__seed_admin.sql';
```

Restart the backend with fresh `APP_BOOTSTRAP_ADMIN_EMAIL` / `APP_BOOTSTRAP_ADMIN_PASSWORD_HASH`
values. **Do not** do this in a shared or production environment — it bypasses Flyway's
immutability guarantee. In production, add a proper `V0nn__reset_admin.sql` migration instead.

---

## 7. Do / Don't summary

- **Do** generate the hash locally (Options A/B/C).
- **Do** keep the cleartext password out of shell history and out of files that get committed.
- **Do** log in immediately and change the password; the seed value is intentionally short-lived.
- **Don't** paste the cleartext password into any online BCrypt tool.
- **Don't** hard-code the hash in `application.yaml` — keep it in env vars.
- **Don't** reuse the same seed hash across environments.
