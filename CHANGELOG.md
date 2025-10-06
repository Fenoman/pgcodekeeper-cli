# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](http://keepachangelog.com/)
and this project adheres to [Semantic Versioning](http://semver.org/).

## [Unreleased]

### Added

### Changed

### Fixed

### Removed

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

[Unreleased]: https://github.com/pgcodekeeper/pgcodekeeper-cli/compare/v11.2.0...HEAD
[11.2.0]: https://github.com/pgcodekeeper/pgcodekeeper-cli/compare/v11.0.0...v11.2.0
[11.0.0]: https://github.com/pgcodekeeper/pgcodekeeper-cli/releases/tag/v11.0.0
