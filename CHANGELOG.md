# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](http://keepachangelog.com/)
and this project adheres to [Semantic Versioning](http://semver.org/).

## [Unreleased]

### Added

### Changed

### Fixed

## [13.0.0] - 2025-11-12

### Added

- Added connection string validation.
- Added a common interface for JDBC connections.

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

[Unreleased]: https://github.com/pgcodekeeper/pgcodekeeper-cli/compare/v13.0.0...HEAD
[13.0.0]: https://github.com/pgcodekeeper/pgcodekeeper-cli/compare/v12.0.0...v13.0.0
[12.0.0]: https://github.com/pgcodekeeper/pgcodekeeper-cli/compare/v11.2.0...v12.0.0
[11.2.0]: https://github.com/pgcodekeeper/pgcodekeeper-cli/compare/v11.0.0...v11.2.0
[11.0.0]: https://github.com/pgcodekeeper/pgcodekeeper-cli/releases/tag/v11.0.0
