# The status cache is a separate, disposable database

Cached Nagios status lives in its own Room database, `status.db`, built with a destructive migration fallback. It is not a set of tables in `profiles.db`.

The two hold opposite kinds of data. `profiles.db` holds the user's saved connections and their encrypted credentials: irreplaceable, so it has no destructive fallback and every schema change needs a migration and a test. The cache holds only what Nagios will hand over again in seconds, and its shape will keep changing as detail screens, notifications and the widget arrive. Putting cache tables in `profiles.db` would have forced every cache tweak through the strict migration process, on the one database where a mistake deletes people's profiles.

## Considered options

- **Cache tables in `profiles.db`.** One file and real foreign keys with cascade delete, but each cache change becomes a risky migration of the credential store.

## Consequences

- A schema change to `status.db` simply wipes it; the next refresh refills it. Raise `StatusDatabase.VERSION` and move on. Never add anything to it that cannot be re-fetched.
- There is no foreign key between the databases. Deleting a profile clears its cache in code (`ProfileCleanup`), and `CacheJanitor` sweeps for orphans at startup in case the app died between the two steps.
- Anything the user creates and would mind losing (settings, in future) belongs elsewhere, not here.
