---
title: Backups and recovery
description: Back up and restore SQLite or MySQL/MariaDB, and recover from a failed installation.
order: 9
---

# Backups and recovery

Back up the plugin JAR, config, messages, documents, and database together, from the same moment. Acceptance records contain player UUIDs and decisions, so store backups privately. The remote cache file is not a backup.

## SQLite

1. Stop the server or proxy and wait for it to exit. Close any database tool using the file.
2. Copy the whole ConsentGate folder to a new backup folder, including any `-journal` or `-wal` files next to the database.
3. To restore, copy into a separate folder first. Never mix an old database file with newer journal files.
4. Check the restored database with `PRAGMA quick_check` (expect `ok`) and `PRAGMA foreign_key_check` (expect no rows).
5. Start a test copy with the restored files. Run `consentgate validate`, and check a player you know accepted and one you know was reset.

To back up while the server is running, use SQLite's backup API or `VACUUM INTO`. A plain file copy while the database is being written is not safe. See [SQLite backups](https://www.sqlite.org/backup.html).

Stay on the same or a newer ConsentGate version when restoring. Older versions may not protect reset records the same way.

## MySQL and MariaDB

Use your database's own dump tool ([mysqldump](https://dev.mysql.com/doc/refman/8.4/en/mysqldump.html) or [mariadb-dump](https://mariadb.com/docs/server/clients-and-utilities/backup-restore-and-import-clients/mariadb-dump)) with a backup account and TLS. ConsentGate's tables are InnoDB, so a single-transaction dump works without locking the whole database. Do not change the schema during a dump.

To restore:

1. Stop every ConsentGate instance.
2. Restore into a new, empty database. Check all six tables, the schema version, and a few players' decisions.
3. Give every instance a new, empty cache file path. Old cache entries can describe decisions that are not in the restored backup, and ConsentGate cannot detect a restore on the same address.
4. Restore matching document files and config, then start the instances.

Keep the original database until you are sure the restore is good.

## Failed installation or upgrade

- **Unknown newer schema:** do not edit the version marker to force startup. Use the matching plugin version, or restore a matching backup somewhere else.
- **First setup stopped partway:** keep that database for inspection, fix the reported problem, and point the plugin at a new empty database.
- **Existing installation with records:** restore a matching backup instead of starting over. Existing tables are never overwritten to repair them.

## Reset is not deletion

A reset asks the player to accept again and keeps their history. It is not a backup, restore, or deletion. ConsentGate has no command to erase history, and cache cleanup does not touch the main database.
