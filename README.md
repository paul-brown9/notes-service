# Team Notes Service

This project is a Spring Boot REST backend for a multi-team note-taking service backed by MongoDB.

## Project layout

- `NotesController` maps HTTP requests to the service and enforces access rules.
- `ApiInput` validates and normalizes values from request bodies.
- `NotesService` contains application logic and MongoDB queries.
- `User`, `Team`, `Membership`, and `Note` are MongoDB documents.
- `Role` defines team permissions; `ApiExceptionHandler` formats API errors.
- `src/test` contains controller tests that run without a MongoDB server.

Quick start:

    docker compose up -d mongo
    mvn spring-boot:run

Optional env vars:

- PORT=8080
- MONGODB_URI=mongodb://localhost:27017/notes-service
- ADMIN_TOKEN=optional-secret

API summary:

- GET /health
- POST /api/users {"name": "Alice"} -> returns id and apiKey
- GET /api/me (Authorization: Bearer <apiKey>)
- GET /api/teams
- POST /api/teams {"name": "Platform"}
- GET /api/teams/{teamId}/members
- POST /api/teams/{teamId}/members {"userId": "...", "role": "OWNER|EDITOR|VIEWER"}
- DELETE /api/teams/{teamId}/members/{userId}
- GET /api/teams/{teamId}/notes?q=&tag=&limit=&offset=
- POST /api/teams/{teamId}/notes {"title": "...", "body": "...", "tags": ["meeting", "weekly"]}
- GET /api/teams/{teamId}/notes/{noteId}
- PUT /api/teams/{teamId}/notes/{noteId} {"title": ..., "body": ..., "tags": [...], "version": 2}
- DELETE /api/teams/{teamId}/notes/{noteId}

Notes:

- Non-members get 404 for a team to avoid leaking existence.
- Viewers cannot modify notes or memberships.
- Version numbers are used for optimistic concurrency checks.
- API keys are returned once; only a SHA-256 hash is stored.
