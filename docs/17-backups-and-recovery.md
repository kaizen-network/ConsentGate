# Backups and recovery

Keep the plugin JAR, configuration, messages, documents, and acceptance database from the same backup point. Acceptance records contain player UUIDs and decisions. Store backups privately. The remote cache is disposable and cannot replace a primary database backup.

## SQLite

1. Stop the proxy/server and wait for it to exit. Close any database editor using the file.
2. Copy the entire ConsentGate data folder to a new backup directory. Include the database at its configured path. If journal or WAL files remain, preserve them with the matching database.
3. Restore into a separate directory first. Never combine an old main database with newer journal files.
4. Check the restored database with SQLite's `PRAGMA quick_check` and `PRAGMA foreign_key_check`. Expect `ok` from the first and no rows from the second.
5. Start an isolated test installation with the restored documents and database. Validate configuration, inspect a known accepted UUID, and test a withdrawn UUID before using the backup for recovery.

For a backup while the database is open, use a tool built on SQLite's backup API or `VACUUM INTO`. A normal file copy during writes is not a consistent backup method. See [SQLite backup methods](https://www.sqlite.org/backup.html).

SQLite remains on schema version 1. Current builds add a lookup index without changing existing tables or history. Existing reset audit records, including resets before first acceptance, prevent an older save from restoring consent. Returning to an older plugin build would lose that protection, so keep the corrected build when restoring data.

## MySQL and MariaDB

Use the database vendor's dump/backup tool with a dedicated backup account, verified TLS, and credentials in a private client options file. For ConsentGate's InnoDB tables, a transaction-consistent dump avoids taking a global read lock. Do not run schema changes during the dump. See [MySQL mysqldump](https://dev.mysql.com/doc/refman/8.4/en/mysqldump.html) and [MariaDB mariadb-dump](https://mariadb.com/docs/server/clients-and-utilities/backup-restore-and-import-clients/mariadb-dump).

Restore into a new, empty database using a setup account. Check all six tables, schema version, document snapshots, event history, and current decisions. Give the runtime account access to that restored database and test an isolated plugin instance before changing other instances. Keep the original database available for investigation.

When restoring an older primary backup, stop every ConsentGate instance first and give each instance a new empty cache path. Old cached positives may describe decisions absent from the restored backup. Restore matching document versions and configuration, then restart the instances. Storage settings require restart.

## Failed installation or upgrade

An unknown newer schema is an error. Do not edit its version marker to force the plugin to start. Restore a matching backup into a separate location or use the compatible plugin version.

Remote schema version 1 is installed manually in an empty dedicated database. There are no remote upgrade scripts yet. If installation stopped partway through, leave that database intact, create another empty database, apply the full supplied schema there, and verify startup. Do not rerun the installation script over acceptance data.

No automatic acceptance-history deletion or erasure command is included. Cache cleanup does not erase primary acceptance history. A reset preserves history and requests consent again; it is not a backup, restore, or deletion operation.
