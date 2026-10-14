# Team Notes Service

Zero-dependency Java 17+ REST backend (JDK `HttpServer`, virtual threads, JSON file persistence).

    bash run.sh            # compiles to ./out and starts on :8080
    bash smoke.sh          # end-to-end check against a running server

Env: `PORT` (8080), `DATA_FILE` (data/notes.json), `ADMIN_TOKEN` (if set, creating users requires header `X-Admin-Token`).

Auth: `Authorization: Bearer <apiKey>` (key returned once by `POST /api/users`; only its SHA-256 is stored).

| Method | Path | Notes |
|---|---|---|
| GET | /health | |
| POST | /api/users | `{name}` -> returns `apiKey` |
| GET | /api/me | |
| GET/POST | /api/teams | creator becomes OWNER |
| GET/POST | /api/teams/{t}/members | POST `{userId, role}` owner-only; roles OWNER/EDITOR/VIEWER |
| DELETE | /api/teams/{t}/members/{userId} | owner-only; last owner protected |
| GET | /api/teams/{t}/notes?q=&tag=&limit=&offset= | search + paging |
| POST | /api/teams/{t}/notes | `{title, body, tags[]}` (EDITOR+) |
| GET/PUT/DELETE | /api/teams/{t}/notes/{id} | PUT accepts `version` -> 409 on conflict |

Non-members get 404 for a team (existence isn't leaked); viewers get 403 on writes.
