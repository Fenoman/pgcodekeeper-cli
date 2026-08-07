# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](http://keepachangelog.com/)
and this project adheres to [Semantic Versioning](http://semver.org/).

## [Unreleased]

### Added

### Changed

### Fixed

## [15.3.0-neo1] - 2026-08-31

### Added

- Added `--mode BATCH` with `--batch-manifest`: a single JSON file names the
  arguments shared by every output and the outputs themselves, and one run loads
  the project and the database once and writes every migration script from that
  one loaded comparison. Each script is byte-identical to a standalone run with
  the same arguments, a failing output does not stop the remaining ones, and the
  run reports the outcome of each output.
- The batch manifest is a JSON object with two keys: `common`, the argument list
  every output shares - the comparison sources and every load option - and
  `outputs`, a non-empty list of `{"name": ..., "args": [...]}` entries. An
  output must name its own file with `-o`, no two outputs may share a name or
  write to the same file, and an output's `args` may hold only options that act
  after loading (`--ignore-list`, `--allowed-object`, `--safe-mode`,
  `--pre-script`, `--post-script` and the rest of the script generation flags);
  anything that changes the load belongs in `common`. `--mode`,
  `--batch-manifest`, `--run-on`, `--run-on-target` and the informational
  options are rejected anywhere in the manifest.
- A batch run exits non-zero when any output failed, and the outputs that
  succeeded are still written; the per-output summary on the error stream names
  which one failed and why.
- Added opt-in `--project-file-filter` with ordered root-relative include and
  exclude rules for top-level project SQL files.
- Added opt-in `--ignore-sequence-cache`, which keeps the `CACHE` parameter of a
  sequence out of the comparison and out of the `ALTER` that follows from it,
  for a sequence of its own and for the identity sequence of a column alike. A
  sequence the migration creates is still created with the cache written in the
  project.
- Added opt-in `--no-alter-table-only`, which leaves `ONLY` out of every
  `ALTER TABLE` a migration writes. `ONLY` keeps a change away from the tables
  that inherit from the one named, and a TimescaleDB hypertable rejects it for
  every subcommand but `SET` and `RESET` of options, rejecting the statement as a
  whole. Which tables are hypertables is not a fact the generator can know: the
  set differs between the database a script is built against and the databases it
  is applied to. The two forms TimescaleDB permits keep the word.
- Added opt-in `--ignore-column-statistics`, which keeps the statistics target of
  a column (`attstattarget`) out of the comparison and out of the `ALTER` that
  follows from it, for a database whose targets are set by a scheduled job of its
  own. A column the migration creates still carries the target written in the
  project. `CREATE STATISTICS` objects are unaffected.
- Added the persistent PostgreSQL catalog cache: `--pg-catalog-cache-dir` names
  the directory and `--pg-catalog-cache-max-mb` caps its size, so a repeated
  comparison stops downloading the routine bodies that have not changed. Nothing
  is written until a directory is given. The additional `--pg-catalog-cache-rows`
  extends the same store to whole catalog rows keyed by a server-side hash, so a
  warm comparison transfers only the rows that changed; it is rejected without
  the store behind it.
- Added `--jdbc-fetch-size` for the number of catalog rows one round trip pulls
  from the database. Zero keeps the driver default, so an existing invocation
  behaves exactly as before.
- Added a rollback switch for every part of the loading profile the CLI now
  enables by default: `--no-parallel-load`, `--pg-routine-body-no-hash-first`,
  `--pg-routine-body-no-skip-matched-analysis` and
  `--pg-parallel-catalog-readers 0`, along with
  `--pg-routine-body-residual-batch-count` and
  `--pg-routine-body-residual-batch-bytes` for the size of a residual body batch.
- `--parallel-load` (`-par`) and `--pg-routine-body-hash-first` are idempotent:
  both name behavior the command-line tool already applies, so passing either
  changes nothing. An invocation that already passes `--parallel-load` keeps
  working unchanged; to select the opposite behavior, use the paired
  `--no-parallel-load` or `--pg-routine-body-no-hash-first`.
- Added a log line naming the catalog cache directory, its size cap and whether
  row caching is on, written before the load starts, so a run that lost its cache
  configuration is visible in the run log
  `~/.pgcodekeeper-cli/logs/pgcodekeeper-cli.log` instead of only in how long the
  run takes.
- Added JVM system properties for the parser memory profile:
  `-Dru.taximaxim.codekeeper.parser.maxpending` bounds how many parser tasks are
  in flight at once, `-Dru.taximaxim.codekeeper.parser.maxpendingbytes` bounds
  the same queue by source size, and
  `-Dru.taximaxim.codekeeper.parser.bodycache.maxstates`,
  `-Dru.taximaxim.codekeeper.parser.bodycache.maxconfigs` and
  `-Dru.taximaxim.codekeeper.parser.bodycache.maxcontexts` cap the shared parser
  prediction cache. They are the lever for a run that has to fit a heap smaller
  than the project wants.

### Changed

- The command-line tool now loads a PostgreSQL comparison with the optimized
  profile by default: both sides at once, a JDBC fetch size of 512, routine
  bodies matched by hash before they are fetched, bounded residual body batches,
  and three extra catalog readers sharing the primary snapshot. Core and IDE
  defaults are untouched, every part of the profile has an explicit rollback
  switch, and a load that cannot share the snapshot falls back to sequential
  reading on its own.
- Loading both comparison sides at once and a JDBC fetch size of 512 are now the
  default for every dialect, not only PostgreSQL: an MS SQL or a ClickHouse
  comparison also opens both sides at the same time and pulls catalog rows in
  blocks of 512. `--no-parallel-load` selects sequential loading and
  `--jdbc-fetch-size 0` the driver default, on any dialect. The three extra
  catalog readers and the hash-first body exchange stay PostgreSQL-only.
- A comparison, a project export and a project update no longer build the
  object-reference index. Nothing in the command-line tool reads it - it exists
  for the editor - so building it spent time and memory on every run.
- Batch mode builds the dependency graphs of a loaded comparison once and reuses
  them for every output, instead of rebuilding them for each script it writes.
- A run no longer reads the per-object author metadata that neither a comparison
  nor a project export consumes, which removes a catalog join from every load.
- Parser tasks are now submitted through a bounded window - twice the parser pool
  size by default - instead of all at once, which lowers the peak memory of the
  parse phase. A value of zero or less in
  `-Dru.taximaxim.codekeeper.parser.maxpending` restores unbounded submission.
- The launcher scripts resolve the Java executable and the heap through two
  documented orders. The executable: a non-empty `PGCK_JAVA_EXE`, then
  `JAVA_HOME/bin/java`, then `java` from `PATH` - a machine whose `JAVA_HOME` and
  `PATH` point at different JDKs now runs the one `JAVA_HOME` names. The heap:
  explicit values after `-vmargs` win, then the `PGCK_JAVA_XMS` and
  `PGCK_JAVA_XMX` environment variables, then the built-in
  `-Xms256m -Xmx3072m`. Both launchers quote executable paths and keep Windows
  metacharacters and padded overrides intact, so a path holding a space or an
  ampersand no longer breaks the command processor, and both let the JVM
  terminate at once when the heap is exhausted instead of limping on with dead
  worker threads.
- Reduced PostgreSQL table catalog payload by reusing snapshot-local type and
  collation metadata instead of transferring duplicate column metadata.
- Combined the persistent PostgreSQL catalog row cache with snapshot-sharing
  parallel catalog readers while preserving canonical output order.
- Packed ordered warm-cache row hashes into one compact binary result, reducing
  result-row and protocol overhead, and the bytes sent over the network before
  changed rows are fetched.
- Added target-scoped persistent-cache namespaces, bounded serialized catalog-row
  cache-entry payloads to 32 MiB, and hardened best-effort, non-blocking post-run
  pruning toward the configured size cap.
- Kept the generic persistent-cache flags opt-in. Downstream bootstraps may pass
  them only under an explicit opt-in policy, without changing CLI or Core
  defaults; three parallel catalog readers remain the standard CLI mode. Warm
  row caching targets network traffic and round-trip reduction on repeated
  comparisons, without CPU/RSS or cold-run speed guarantees.

### Fixed

- The launcher scripts now ask the JVM to deduplicate strings, so the heap they
  ship with loads a large project instead of running out of memory. A comparison
  peaks while both sides hold the text of every source file at once, and on a
  real project those texts are largely the same string twice: measured on a
  24k-file project, 96.7% of the strings that survived a young collection were
  duplicates. An explicit `-vmargs -XX:-UseStringDeduplication` still wins.
- `--mode GRAPH` now stops and reports when the load produced errors, instead of
  printing an incomplete dependency graph and exiting with a success code and an
  empty error stream. A pipeline ordering work off that graph had no way to
  notice that it was reading partial data.
- The cause of a failure is now printed instead of being suppressed whenever
  parse errors had accumulated as well, and an unexpected error anywhere in the
  run is reported with its cause and a non-zero result, instead of a bare stack
  trace or nothing at all.
- A malformed ignore list - given explicitly, given as a schema list, or loaded
  from the project - and a malformed dependencies file now fail the whole run
  with a message naming the file and the first error position, instead of
  quietly comparing everything the file was meant to exclude or writing a script
  whose statement order the file was meant to fix.
- An option token that joins a name and its value with a space is now rejected.
  Such a token made a flag without a value spin the argument parser at full CPU
  with no way out, and made a value option pass unnoticed, so a run could report
  success while loading with its persistent cache switched off.
- Rejected `--pg-parallel-catalog-readers` under `--db-type MS` and
  `--db-type CH` and stopped applying its PostgreSQL-only CLI default to other
  dialects, matching every other `--pg-*` option.
- Stopped writing `CREATE INDEX ... ON ONLY` for a partitioned table whose
  partition indexes are not in the model. `ON ONLY` leaves the index invalid
  until an index of every partition has been attached to it, and those attaches
  are written only for the partitions the model holds; where it holds none, the
  index stayed invalid and no plan could ever use it. The word is now written
  only where the attaches follow it, so a project that keeps its partitions is
  unaffected.
- Corrected PostgreSQL sequence-owner migration ordering, ACL reconciliation,
  renamed-sequence targeting, and fail-closed filtering of required table-owner
  changes. Sequences that survive owning-column or table recreation are now
  detached and reattached without losing their OID, current value, properties,
  owner, or privileges.
- Hardened PostgreSQL analysis for recovered `SELECT` trees and corrected
  alias-less `ROWS FROM` signatures, aliases, and ordinality.
- Eliminated false `regtype` parser warnings for multiword, array, and typmod
  type names while preserving qualified custom-type dependencies.

## [15.3.0] - 2026-08-10

### Added

- Added generation of a migration script taking into account the PostgreSQL version when the `Use current database version syntax to generate migration script` setting is enabled.

### Security

- Increased versions of org.apache.httpcomponents libraries due to CVE-2026-64607, CVE-2026-54399, CVE-2026-54428.

## [15.2.0] - 2026-08-10

### Fixed

- Fixed false differences when comparing library objects with the "ignore privileges" option enabled.

## [15.1.0] - 2026-07-30

### Added

- Added support for changing table and column compression settings via `ALTER TABLE` commands instead of recreating objects when the Greenplum 7 syntax is enabled.

### Changed

- Disabled dependency analysis for objects that could not be parsed correctly, reducing the number of irrelevant errors displayed to the user.

### Fixed

- Fixed a parsing error for the `ALTER SEQUENCE ... SET LOGGED/UNLOGGED` command for regular sequences in PostgreSQL.
- Fixed an error in the order of PostgreSQL `ALTER TABLE ... OWNER TO` and `ALTER SEQUENCE ... OWNER TO` commands by moving the latter to the end of the migration script.

### Security

- Increased version of lz4-java library due to CVE-2026-59949.

## [15.0.0] - 2026-07-14

### Changed

- Updated library dependencies.

## [14.7.0] - 2026-06-30

### Changed

- Improved parser rules for working with JSON formats in ClikHouse.

### Fixed

- Fixed errors when working with .pgcodekeeperdependencies files.

## [14.6.0] - 2026-06-18

### Changed

- Improved work with temporary files.

### Fixed

- Fixed full project update to clean only the project's own directories.
- Fixed adding library objects to a project during export.
- Fixed code generation for index when altering in CONCURRENTLY mode.
- Fixed migration script generation when changing a generated column in PostgreSQL.

### Security

- Increased version of logback due to CVE-2026-1225.

## [14.5.0] - 2026-06-02

### Added

- Added support for new syntax for tables for Greenplum 7.
- Added `--structure-file` parameter for parse mode, which allows specifying the path to a properties file with directory layout overrides for project export.

### Changed

- Fixed parser errors for PostgreSQL.

### Security

- Increased version of JDBC driver for PostgreSQL due to CVE-2026-42198.

## [14.4.1] - 2026-05-06

### Fixed

- Fixed a bug when working with Arenadata DB 7.4.0 and higher.

## [14.4.0] - 2026-05-05

### Changed

- Improved code generation when changing an index on partitioned tables PostgreSQL.
- Improved the error message text when connecting to the database.

## [14.3.0] - 2026-04-15

### Added

- Added `--additional-dependencies` parameter, which allows you to specify the path to a file with additional dependencies.
- Added `--simplify-not-null` parameter to simplify reading of NOT NULL constraints via JDBC for PostgreSQL 18+.

## [14.2.0] - 2026-04-07

### Added

- Added localization of missing lines.
- Added `--use-actual-syntax` parameter. When specified, the syntax relevant to the current database version is used (there are currently no syntax changes, but these will be added later). By default, the syntax specific to the minimum supported version is used.

### Changed

- Reduced the number of line breaks and indents added when formatting code.

## [14.1.1] - 2026-03-25

### Changed

- Disabled loading of the OVERRIDES directory for libraries to restore the behavior typical before version 14.0.0.

### Fixed

- Fixed incorrect error on parallel database load failure.

## [14.1.0] - 2026-03-18

### Added

- Added the `--parallel-load` parameter. When specified, databases are loaded in parallel; by default, loading is performed sequentially.
- Added the `--disable-auto-load` parameter. When specified, automatic loading of auxiliary project files is disabled (.pgcodekeeperignore, .pgcodekeeperignoreschema, .dependencies).

### Changed

- Updated the JUnit library version from 5.x to version 6.
- The .dependencies file is now automatically read when loading a project.

### Fixed

- Fixed a bug when parsing the connection string for MS SQL.
- Fixed a migration script generation error with the Print DROP before CREATE option.
- Fixed a bug in the generation of the migration script when data migration when recreating tables with serial-type fields for PostgreSQL.
- Fixed a bug with searching for dependencies for the LATERAL function in PostgreSQL.
- Fixed a bug with automatic object code formatting.

## [14.0.0] - 2026-02-25

### Added

- Added comments processing for NOT NULL constraints in PostgreSQL.
- Added argument `--cluster-name` for adding in migration script ON CLUSTER syntax to ClickHouse (experimentally).
- Added search for .pgcodekeeperignore and .pgcodekeeperignoreschema files during project read. This avoids using the `--ignore-list (-I)` and `--ignore-schema` options with project files.

### Changed

- Improved readability of `--help` help: options are sorted alphabetically.
- Improved parser rules for ClickHouse.
- Improved function analyze in PostgreSQL.

### Fixed

- Fixed default name generation for CONSTRAINT and SEQUENCE objects in PostgreSQL.
- Fixed logic for normalizing views for ClickHouse.
- Fixed parser errors for partitioned tables in MS SQL.
- Fixed a false difference for the STATISTICS object in MS SQL.
- Fixed reading of column length and precision for binary, datetime2, datetimeoffset, and time types in MS SQL database.
- Fixed creating tables with NOT NULL constraints in PostgreSQL.

### Removed

- Removed `--mode verify` and `--mode insert` modes.

### Security

- Updated the version of the clickhouse-jdbc driver due to CVE-2025-66566 and CVE-2025-12183. All connections to ClickHouse must now include the required `password` parameter.

## [13.1.0] - 2025-11-18

### Changed

- Updated library dependencies. To use [Windows authentication](https://pgcodekeeper.readthedocs.io/en/latest/windowsauth.html#id2) you need to update [DDL](https://github.com/microsoft/mssql-jdbc/releases/tag/v13.2.1).

### Fixed

- Fixed an error when reading functions from the ClickHouse database.
- Fixed a false difference between STATISTICS in PostgreSQL.

## [13.0.0] - 2025-11-12

### Changed

- Improved parser rules for ClickHouse.
- Improved migration script generation for tables and indexes with options in MS SQL.
- Added the IS JSON parser rule for PostgreSQL.

### Fixed

- Fixed CTE formatting in ClickHouse.
- Fixed an error comparing tables when the column order is ignored setting is enabled.
- Fixed bug in generating the migration script when adding a column to Log family tables in ClickHouse.
- Fixed a false difference between NOT NULL constraints with the default name in PostgreSQL.
- Fixed adding ONLY for columns in partitioned tables in PostgreSQL.
- Fixed reading of EXTERNAL TABLES in Greenplum 7.

## [12.0.0] - 2025-10-15

### Added

- Added support for PostgreSQL 18.
- Added formatting of the SELECT part of VIEW objects when reading from the database for ClickHouse.

### Changed

### Fixed

- Fixed code generation for columns with Nullable values in ClickHouse.

### Removed

- Removed support for MS SQL versions below 2017.
- Removed support for PostgreSQL versions below 14.
- Removed deprecated `--parse`, `--graph`, `--insert` parameters.

## [11.2.0] - 2025-09-23

### Added

- Added analysis of functions returning the RECORD type.

### Changed

- All localized messages have been brought into a unified style.

### Fixed

- Fixed encoding of text output to console.
- Fixed reading logic when xml file is missing.
- Fixed a race condition when loading a library from an archive.
- Fixed parser rule for floating point constants for MS SQL.

## [11.0.0] - 2025-08-28

### Changed

- The CLI version has been separated from a [main repository](https://github.com/pgcodekeeper/pgcodekeeper).

### Fixed

- Fixed parser rule for PostgreSQL.
- Fixed code generation error when changing views in MS SQL
- Fixed bug with ignoring column order in PostgreSQL and MS SQL constraints.
- Fixed a bug with the settings when saving objects to a project.
- Fixed false differences when ignoring table column order.

[Unreleased]: https://github.com/pgcodekeeper/pgcodekeeper-cli/compare/v15.3.0...HEAD
[15.3.0]: https://github.com/pgcodekeeper/pgcodekeeper-cli/compare/v15.2.0...v15.3.0
[15.2.0]: https://github.com/pgcodekeeper/pgcodekeeper-cli/compare/v15.1.0...v15.2.0
[15.1.0]: https://github.com/pgcodekeeper/pgcodekeeper-cli/compare/v15.0.0...v15.1.0
[15.0.0]: https://github.com/pgcodekeeper/pgcodekeeper-cli/compare/v14.7.0...v15.0.0
[14.7.0]: https://github.com/pgcodekeeper/pgcodekeeper-cli/compare/v14.6.0...v14.7.0
[14.6.0]: https://github.com/pgcodekeeper/pgcodekeeper-cli/compare/v14.5.0...v14.6.0
[14.5.0]: https://github.com/pgcodekeeper/pgcodekeeper-cli/compare/v14.4.1...v14.5.0
[14.4.1]: https://github.com/pgcodekeeper/pgcodekeeper-cli/compare/v14.4.0...v14.4.1
[14.4.0]: https://github.com/pgcodekeeper/pgcodekeeper-cli/compare/v14.3.0...v14.4.0
[14.3.0]: https://github.com/pgcodekeeper/pgcodekeeper-cli/compare/v14.2.0...v14.3.0
[14.2.0]: https://github.com/pgcodekeeper/pgcodekeeper-cli/compare/v14.1.1...v14.2.0
[14.1.1]: https://github.com/pgcodekeeper/pgcodekeeper-cli/compare/v14.1.0...v14.1.1
[14.1.0]: https://github.com/pgcodekeeper/pgcodekeeper-cli/compare/v14.0.0...v14.1.0
[14.0.0]: https://github.com/pgcodekeeper/pgcodekeeper-cli/compare/v13.1.0...v14.0.0
[13.1.0]: https://github.com/pgcodekeeper/pgcodekeeper-cli/compare/v13.0.0...v13.1.0
[13.0.0]: https://github.com/pgcodekeeper/pgcodekeeper-cli/compare/v12.0.0...v13.0.0
[12.0.0]: https://github.com/pgcodekeeper/pgcodekeeper-cli/compare/v11.2.0...v12.0.0
[11.2.0]: https://github.com/pgcodekeeper/pgcodekeeper-cli/compare/v11.0.0...v11.2.0
[11.0.0]: https://github.com/pgcodekeeper/pgcodekeeper-cli/releases/tag/v11.0.0
