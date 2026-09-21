# Keycloak

Keycloak and its PostgreSQL database are defined in the root `compose.yaml`, not in this
directory. This folder only holds `realm-config/` (the realm export Keycloak imports on
startup).

## Usage

From the repo root:

```bash
docker compose up -d
```

## Access Keycloak

Open your browser and navigate to `http://localhost:8080`.

## Admin Console

* Username: `admin`
* Password: `admin`

## Realm Configuration

`compose.yaml` mounts this directory's `realm-config/` folder into the Keycloak container and
starts it with `start-dev --import-realm`, which imports `realm-config/realm-export.json` on
every startup.
