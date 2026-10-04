# Neraca Lab - Login, Users, Roles and Permissions (v1)

Every page and every API needs a login, except the login itself and the health check. Users
log in with **username and password**; what they may open comes from the **permissions** of their
**roles**.

```text
user ──< user_roles >── role ──< role_permissions (ADMIN | INGESTION | COMPANIES | SCREENING)
```

- A user has **one or more roles**; a role has **one or more permissions**; a user has the union
  of the permissions of all their roles.
- Permissions are checked by the backend on **every API request**, read from the database each
  time, so a role change applies to sessions that are already open.

## 1. Permissions

| Permission  | Pages                                          | APIs                                                                                         |
|-------------|------------------------------------------------|----------------------------------------------------------------------------------------------|
| `ADMIN`     | Admin Center: User Management, Role Management | `/api/v1/admin/**` (users, roles, permissions, user avatars)                                 |
| `INGESTION` | Ingestion                                      | `/api/v1/financial-statements/**`, `/api/v1/prices/ingestions/**`, `/api/v1/ingestions/**`, `/api/v1/fundamentals/**`, and the company **list** (ticker dropdown) |
| `COMPANIES` | Companies, company detail                      | `/api/v1/companies` (list), `/api/v1/companies/{exchange}/{ticker}` (detail)                  |
| `SCREENING` | Screening, screening report                   | `/api/v1/screenings` (runs, reports, PDF), `/api/v1/fundamentals/status`                      |
| any login   | Profile                                        | `/api/v1/auth/me`, `/api/v1/profile/**`, `/api/v1/exchanges`                                 |
| public      | Login                                          | `POST /api/v1/auth/login`, `POST /api/v1/auth/logout`, `GET /api/v1/health`                  |

Answers: **401** without a valid session (no / unknown / expired token, deactivated user, header
`WWW-Authenticate: Bearer`), **403** logged in but without one of the required permissions. Both
are RFC 9457 ProblemDetail bodies like every other error.

Each controller endpoint declares its rule with `@PublicAccess`, `@RequiresLogin` or
`@RequiresPermission(...)`; `AuthInterceptor` checks it before the controller runs. Deny by
default: an endpoint without a rule is refused (and `EndpointAccessRulesTest` fails).

## 2. Root user and the built-in role

At every start `AuthBootstrap` makes sure that

| What                  | Value                                                                                                  |
|-----------------------|--------------------------------------------------------------------------------------------------------|
| built-in role         | **Administrator**, every permission; its name and permissions cannot be changed, it cannot be deleted |
| root user             | username **`admin`**, password **`admin`** when first created (`AUTH_ROOT_PASSWORD`), role Administrator |
| root profile (dummy)  | email `admin@neracalab.local`, full name "Neraca Lab Administrator", address Jl. Jenderal Sudirman Kav. 52-53, Jakarta Selatan 12190, phone +62 21 5150 1000, date of birth 1990-01-01 |

An existing root user is never changed (its password, once changed, stays changed). The root user
cannot be deleted, deactivated or lose the Administrator role. **Change the root password** after
the first login (User Management, edit `admin`, "New password").

## 3. Login and sessions

```bash
# log in, keep the token
TOKEN=$(curl -s -H "Content-Type: application/json" -d '{"username":"admin","password":"admin"}' \
        http://localhost:8080/api/v1/auth/login | python -c "import sys,json;print(json.load(sys.stdin)['token'])")
AUTH="Authorization: Bearer $TOKEN"

curl -H "$AUTH" http://localhost:8080/api/v1/auth/me        # the logged-in user
curl -X POST -H "$AUTH" http://localhost:8080/api/v1/auth/logout
```

| Endpoint                     | Access | Result                                                                                      |
|------------------------------|--------|---------------------------------------------------------------------------------------------|
| `POST /api/v1/auth/login`    | public | `{username, password}` -> 200 `{token, expiresAt, user}`; 401 `Invalid username or password` / deactivated |
| `POST /api/v1/auth/logout`   | public | 204; ends the session of the bearer token (also when it is already invalid)                |
| `GET /api/v1/auth/me`        | login  | the user with roles and permissions                                                         |
| `GET /api/v1/health`         | public | `{"status":"UP"}` (container health check, no data)                                        |

- The username is case-insensitive; passwords are stored as BCrypt hashes (cost 12).
- A session token is 32 random bytes (base64url), valid for `neracalab.auth.session-ttl`
  (default 12 h, env `AUTH_SESSION_TTL`). Only its SHA-256 is stored (`user_sessions`), so the
  database never holds a usable token. Expired sessions are cleaned up at the next login.
- A new password set by an administrator, a deactivation and a deletion end the user's sessions
  (an administrator changing their own password keeps the current session).

### Frontend

The browser never sees the token: `POST /api/auth/login` (Next.js route handler) logs in at the
backend and stores the token in an **httpOnly, SameSite=Lax** cookie (`neraca_session`, `Secure`
over HTTPS, expiring with the session). Server components and route handlers send it to the backend
as `Authorization: Bearer`. `src/proxy.ts` redirects pages without the cookie to `/login?next=...`;
the dashboard layout checks the session with `GET /api/v1/auth/me` and sends an expired one to the
login page; every page checks its permission and shows "No access" without it.

## 4. User management (`ADMIN`)

| Endpoint                                | Result                                                                                   |
|-----------------------------------------|------------------------------------------------------------------------------------------|
| `GET /api/v1/admin/users`               | every user (root first, then by username) with roles and permissions                      |
| `GET /api/v1/admin/users/{id}`          | one user; 404 unknown                                                                     |
| `POST /api/v1/admin/users`              | 201 + `Location`; body below                                                              |
| `PUT /api/v1/admin/users/{id}`          | replaces every editable field; `password` optional (empty / missing keeps it)             |
| `DELETE /api/v1/admin/users/{id}`       | 204                                                                                       |
| `GET /api/v1/admin/users/{id}/avatar`   | the user's picture; 404 without one                                                       |

```json
{ "username": "rina.analyst", "email": "rina@example.com", "password": "at-least-8-chars",
  "fullName": "Rina Wijaya", "phone": "+62 812 1111 2222", "dob": "1994-08-17",
  "address": "Jl. Gatot Subroto 10, Jakarta", "active": true, "roleIds": [2] }
```

| Rule                              | Answer                                                                                 |
|-----------------------------------|----------------------------------------------------------------------------------------|
| username                          | 3-50 letters, digits, `.`, `_`, `-`; stored lower case; **unique**; cannot be changed (400) |
| email                             | valid address, stored lower case; **unique** (409)                                     |
| password                          | 8-72 characters (BCrypt limit: at most 72 bytes)                                       |
| phone / date of birth             | 6-30 digits, spaces, `( ) . / -`, optional leading `+` / a date in the past            |
| roles                             | at least one; every id must exist (400)                                                |
| root user                         | cannot be deleted, deactivated or lose the Administrator role (400)                    |
| yourself                          | cannot delete or deactivate yourself or remove your own ADMIN permission (400)         |

Validation errors are 400 with the message per field in `errors` (`{"errors": {"email": "..."}}`);
text fields are trimmed (passwords never).

## 5. Role management (`ADMIN`)

| Endpoint                           | Result                                                                                         |
|------------------------------------|------------------------------------------------------------------------------------------------|
| `GET /api/v1/admin/permissions`    | the three permissions with label and description                                              |
| `GET /api/v1/admin/roles`          | every role (built-in first) with permissions and `userCount`                                   |
| `GET /api/v1/admin/roles/{id}`     | one role                                                                                       |
| `POST /api/v1/admin/roles`         | `{name, description, permissions: ["INGESTION","COMPANIES"]}` -> 201                           |
| `PUT /api/v1/admin/roles/{id}`     | same body; the built-in role accepts only a new description                                   |
| `DELETE /api/v1/admin/roles/{id}`  | 204; 409 while the role is assigned to users; 400 for the built-in role                       |

Role names are unique ignoring case (409) and a role needs at least one permission. An
administrator cannot remove the ADMIN permission from the role that gives them admin access
(another role still granting ADMIN allows it).

## 6. Profile (any logged-in user)

| Endpoint                          | Result                                                                                       |
|-----------------------------------|----------------------------------------------------------------------------------------------|
| `GET /api/v1/profile`             | the own user                                                                                 |
| `PUT /api/v1/profile`             | `{address, phone, dob}`; a **different username or email is rejected** (400)                |
| `GET /api/v1/profile/avatar`      | the own picture (`Cache-Control: private, no-cache`, `X-Content-Type-Options: nosniff`)      |
| `PUT /api/v1/profile/avatar`      | multipart `file`: PNG, JPEG, WebP or GIF, at most 2 MB; 415 other types, 413 too large       |
| `DELETE /api/v1/profile/avatar`   | removes the picture                                                                          |

The picture type is detected from the file content (magic bytes), never from the name or the
Content-Type; SVG is refused (it can carry scripts). Users change only address, phone, date of
birth and picture; username, email and name are managed in User Management.

## 7. Tables (`V1.0.8__schema_auth.sql`)

| Table              | Content                                                                                                   |
|--------------------|-----------------------------------------------------------------------------------------------------------|
| `users`            | `username` / `email` unique and lower case, `password_hash` (BCrypt), `full_name`, `address`, `phone`, `dob`, `avatar` (BYTEA) + `avatar_content_type` + `avatar_updated_at`, `active`, `root` (at most one) |
| `roles`            | `name` (unique ignoring case), `description`, `system` (the built-in role, at most one)                   |
| `role_permissions` | `(role_id, permission)`, permission `ADMIN`, `INGESTION`, `COMPANIES` or `SCREENING`                                   |
| `user_roles`       | `(user_id, role_id)`                                                                                      |
| `user_sessions`    | `token_hash` (SHA-256, unique), `user_id`, `created_at`, `expires_at`                                     |

Deleting a user deletes its role assignments and sessions (`ON DELETE CASCADE`). Details:
[DB_SCHEMA_DOCS.md](DB_SCHEMA_DOCS.md), section 4.9.

## 8. Code

| Class (`backend/src/main/java/com/neracalab/backend/`) | Role                                                              |
|--------------------------------------------------------|-------------------------------------------------------------------|
| `auth/AuthInterceptor`                                 | access check of every `/api/**` request                           |
| `auth/RequiresPermission`, `RequiresLogin`, `PublicAccess` | access rules of the endpoints                                 |
| `auth/AuthService`, `SessionRepository`                | login, session tokens, logout                                     |
| `auth/AuthBootstrap`                                   | built-in role and root user at startup                            |
| `auth/controller/AuthController`, `HealthController`   | login / logout / me, health                                       |
| `user/UserService`, `RoleService`                      | rules of user and role management and of the profile              |
| `user/controller/AdminUserController`, `AdminRoleController`, `ProfileController` | the APIs above             |
| `web/ApiExceptionHandler`                              | ProblemDetail for 400 (validation) / 401 / 403 / 404 / 409 / 413 / 415 |

## 9. Tests

| Test                       | What it proves                                                                                               |
|----------------------------|--------------------------------------------------------------------------------------------------------------|
| `AuthControllerTest`       | login (case-insensitive username, token only stored hashed), wrong password / unknown / deactivated user, logout, expired session, 401 on every protected API, root user |
| `AccessControlTest`        | each permission opens exactly its APIs; permissions of several roles add up; a user without roles reaches only the own profile; a permission change applies to a running session |
| `EndpointAccessRulesTest`  | every `/api/**` endpoint has an access rule; only login, logout and health are public                        |
| `AdminUserControllerTest`  | create / read / update / delete, case-insensitive uniqueness, validation, root and self protections, sessions ended by a new password or a deactivation |
| `AdminRoleControllerTest`  | create / read / update / delete, validation, built-in role, delete of an assigned role, self-lockout         |
| `ProfileControllerTest`    | address / phone / dob update, username and email fixed, avatar upload / download / delete, SVG and oversized pictures refused |

The tests create users and roles with random names and delete them afterwards.
