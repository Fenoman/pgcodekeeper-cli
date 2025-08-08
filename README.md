[![Apache 2.0](https://img.shields.io/github/license/pgcodekeeper/pgcodekeeper-cli.svg)](http://www.apache.org/licenses/LICENSE-2.0)

# pgcodekeeper-cli

A CLI version for [pgcodekeeper-core](https://github.com/pgcodekeeper/pgcodekeeper-core).

## Documentation

* [User manual](https://pgcodekeeper.readthedocs.io/en/latest/cli_version.html)
* [Issue tracker](https://github.com/pgcodekeeper/pgcodekeeper-cli/issues)

## Build

Build requires Java (JDK) 17+ and Apache Maven 3.9+.

```shell
git clone https://github.com/pgcodekeeper/pgcodekeeper-cli.git
cd pgcodekeeper-cli
mvn clean verify -DskipTests
```

Binaries will be created in pgcodekeeper-cli/target