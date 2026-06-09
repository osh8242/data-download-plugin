# Changelog

## [1.0.4]
### Changed
- Upgraded fastexcel library version from 0.18.4 to 0.20.1.
  - Improved data conversion performance, particularly for date/time (temporal) and column calculations.
  - Added support for rich inline text formatting via the new `Worksheet.inlineString` API.
  - Upgraded internal XML parser (aalto-xml) and commons-io dependencies for safety.

## [1.0.3]
### Fixed
- Migrated Excel generation library from Apache POI to fastexcel.
- Reduced plugin package size by 90% (from 17.2MB to 1.8MB) for faster downloads.

## [1.0.2]
### Fixed
- Resolved binary incompatibility with DatabaseConnectionManager.Companion class loading in DataGrip 2023.3.

## [1.0.1]
### Fixed
- Fixed Kotlin compiler type inference error on Directory Browse Listener.
- Set compatibility untilBuild up to 2026.3+ for modern IDEs.
