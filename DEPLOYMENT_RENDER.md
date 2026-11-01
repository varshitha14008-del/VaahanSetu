# VaahanSetu deployment (Render + existing Neon database)

This setup serves the static frontend and Spring Boot API from one Render web service.
The existing Neon database is reused; deployment must not reset, recreate, or seed it.

## Render service
- New -> Web Service -> connect the VaahanSetu GitHub repository.
- Runtime/environment: Docker (Render detects the root `Dockerfile`).
- Root directory: leave blank (repository root).
- Health check path: `/health`.
- Add environment variables `DB_URL`, `DB_USER`, and `DB_PASSWORD` using the existing Neon connection details.
- `DB_URL` must be a JDBC URL beginning `jdbc:postgresql://` and include `sslmode=require`.
- Deploy only after the code is pushed to the selected branch.

## Important database safety
Do not run `database/setup/apply_to_cloud.ps1`, `database/setup/apply_to_cloud.sh`, or `database/setup/setup.sh` against the existing Neon database. They may drop or recreate database objects. The application is configured with `ddl-auto=none` and expects the existing schema to be present.

## Frontend
The frontend files are copied into `backend/src/main/resources/static`, so Spring Boot serves `index.html`, `app.js`, and `styles.css`. The browser uses the relative `/api` path; no separate frontend host or CORS configuration is needed.
