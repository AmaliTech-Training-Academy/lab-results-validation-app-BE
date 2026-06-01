# labresultsvalidator — Local Postgres setup

This README explains how to run a local PostgreSQL database for the labresultsvalidator project using Docker Compose, how the `.env` is used, how persistence works, and common troubleshooting steps.

Prerequisites
- Docker Desktop for Windows (or Docker Engine + Compose). Ensure the Docker service is running.
- PowerShell (the commands below are for PowerShell on Windows).

Files
- `docker-compose.yml` — defines a `postgres` service that reads DB settings from `./.env` and exposes port 5432.
- `.env` — environment variables used by both your app and Docker Compose (kept in `.gitignore`).

Quick start (PowerShell)
1. From the project root where `docker-compose.yml` and `.env` live:

    cd C:\Users\MiracleAdams\phase2\project\lab-results-validation-app-BE\labresultsvalidator

2. Start Postgres in the background:

    `docker compose up -d`

3. Check service status:

    `docker compose ps`

4. View recent logs (200 lines):

    `docker compose logs --no-log-prefix --tail=200 postgres`

5. To follow logs live:

    `docker compose logs --no-log-prefix -f postgres`

Stopping and cleaning up
- Stop containers but keep the database volume (preserves data):

    `docker compose down`

- Stop and remove containers and the named volume (deletes data):

    `docker compose down -v`

Database access examples
- Exec a `psql` shell inside the container (requires container running):

    `docker compose exec postgres psql -U $env:DB_USER -d $env:DB_NAME`

  If you prefer a direct psql command from the host (requires `psql` client installed):

    psql -h localhost -p 5432 -U labresults_user -d lab_results_db

  (You'll be prompted for the password from `.env` — `DB_PASSWORD`)

JDBC / Spring Boot connection
- When running the Spring Boot app on your host, use these values (match `.env`):

    spring.datasource.url=jdbc:postgresql://localhost:5432/${DB_NAME}
    spring.datasource.username=${DB_USER}
    spring.datasource.password=${DB_PASSWORD}


Why the volume exists (persistence)
- The compose file mounts a named Docker volume to `/var/lib/postgresql/data` inside the container. This ensures your database files survive container restarts and recreates.
- If you want an ephemeral database (fresh on every start), run `docker compose down -v` to remove the data volume, or remove the `volumes:` entry in `docker-compose.yml` (data will be lost when container is removed).
- If you need the DB files on the host filesystem, you can switch to a bind mount (see the change examples below), but on Windows this can cause permission and performance issues — named volumes are recommended.

Change examples
- Use a bind mount (store DB files in `./pgdata`):

```yaml
services:
  postgres:
    # ...existing code...
    volumes:
      - ./pgdata:/var/lib/postgresql/data
```

- Make the DB ephemeral (no persistence):

```yaml
services:
  postgres:
    # ...existing code...
    tmpfs:
      - /var/lib/postgresql/data
```

Common issues & troubleshooting
- Docker not installed or not running: start Docker Desktop and ensure WSL2 backend (Windows) is enabled.
- Port 5432 already in use: change the host port mapping in `docker-compose.yml`, e.g. `"5433:5432"`, and update `DB_PORT` in `.env` if needed.
- Slow or permission errors with bind mounts on Windows: use the default named volume instead.
- DB initialization errors: check `docker compose logs postgres` and ensure `.env` values are valid.

Resetting the DB
- To reset everything (containers + data):

    docker compose down -v

- To manually remove only the named volume:

    docker volume rm labresultsvalidator-db-data

Security & git
- `.env` is added to `.gitignore` to avoid committing credentials. If you need to commit a template, create `.env.example` with placeholder values (no real passwords).

Next steps / optional improvements
- Add a `docker-compose.override.yml` for development to add a bind-mount for backups or to include the Spring Boot app as a service.
- Add an init SQL script under `docker-entrypoint-initdb.d/` if you want the DB pre-populated the first time it is created.
- Add a `Makefile` or PowerShell script for common commands (start/stop/reset/psql) to simplify developer setup.

If you'd like, I can:
- Start the Postgres container for you now and show the logs.
- Modify `docker-compose.yml` to use a bind mount or to add the Spring Boot service.
- Add a `.env.example` file with placeholders.


