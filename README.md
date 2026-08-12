[![Apache 2.0](https://img.shields.io/github/license/pgcodekeeper/pgcodekeeper-cli.svg)](http://www.apache.org/licenses/LICENSE-2.0)

# pgcodekeeper-cli

A CLI version for [pgcodekeeper-core](https://github.com/pgcodekeeper/pgcodekeeper-core).

## Documentation

* [User manual](https://pgcodekeeper.readthedocs.io/en/latest/cli_version.html)
* [Issue tracker](https://github.com/pgcodekeeper/pgcodekeeper-cli/issues)

## Project file filtering

`--project-file-filter PATH` enables an explicit, pre-parse filter for SQL files
in a top-level project. It is available in `DIFF`, `PARSE`, and `GRAPH` modes.
For `BATCH`, put the option and its value in the manifest's `common` array; the
outer `--mode BATCH` command rejects the option, and per-output `args` reject it
because all outputs must share one loaded comparison.

The filter is opt-in only. No conventional file name is discovered or loaded
automatically. `PATH` must name a UTF-8 text file with this grammar:

```text
# blank lines and lines whose first non-space character is # are ignored
ACTION KIND VALUE

ACTION := INCLUDE | EXCLUDE
KIND   := PATH | REGEX
```

Rules are evaluated in file order. A path starts as included, and the last
matching rule wins. For example:

```text
EXCLUDE REGEX ^SCHEMA/dummy_tmp/(?:TABLE|FUNCTION)/.*\.sql$
INCLUDE PATH SCHEMA/dummy_tmp/TABLE/report_meta.sql
```

`PATH` is an exact match, not a prefix or glob. Both the rule and candidate use
normalized slash-separated, top-level-root-relative paths; repeated separators,
`.` segments, and `\` versus `/` are normalized. Absolute paths, drive-prefixed
paths, and `..` traversal are invalid. `REGEX` is a Java regular expression
matched against the entire normalized path, with the same full-match behavior
as `java.util.regex.Pattern.matches`.

The root-relative namespace includes `OVERRIDES/`, so override rules must use
paths such as `OVERRIDES/SCHEMA/public/TABLE/example.sql`. Schema filtering has
precedence: an `INCLUDE` rule cannot restore a file from a schema excluded by
`.pgcodekeeperignoreschema` or `--ignore-schema`. In v15, project libraries,
including nested libraries, always use `ALLOW_ALL`; the option filters only the
top-level project side or sides.

## Large project memory tuning

The distribution launchers (`pgcodekeeper-cli.sh` and `pgcodekeeper-cli.bat`)
use a bounded pending-task window for parser phases. Configure it with:

```text
-Dru.taximaxim.codekeeper.parser.maxpending=<positive window>
```

The property controls how many parser tasks are submitted at one time. If it is
omitted, the window is twice the parser thread-pool size. A value less than or
equal to zero enables the legacy eager-submission mode and should only be used
for diagnostics or A/B comparisons.

A smaller positive window can reduce parser-stage peak memory, but it can also
reduce throughput; select the value using measurements from the target project.

The launchers use `-Xms256m -Xmx3072m` by default. Heap values have the
following precedence: explicit values after `-vmargs`, then non-sentinel
`PGCK_JAVA_XMS` / `PGCK_JAVA_XMX` environment values, then the defaults.
Whitespace around environment values is ignored; an empty value, `0`, or `-1`
selects the corresponding default. Explicit `-vmargs` remain last among the JVM
arguments, so the JVM's last-value rule makes them the effective values.

Java executable selection is also deterministic and does not modify `PATH`:

1. A non-empty `PGCK_JAVA_EXE` is used as the exact executable path.
2. Otherwise, a non-empty `JAVA_HOME` selects `JAVA_HOME/bin/java` on Unix or
   `JAVA_HOME\bin\java.exe` on Windows.
3. Otherwise, the launcher invokes `java` from the existing `PATH`.

Executable paths are quoted by both launchers, so `PGCK_JAVA_EXE` and
`JAVA_HOME` may contain spaces.

Deferred PostgreSQL function bodies use a shared, generational ANTLR cache. Its
three independent limits are configurable with:

```text
-Dru.taximaxim.codekeeper.parser.bodycache.maxstates=2048
-Dru.taximaxim.codekeeper.parser.bodycache.maxconfigs=262144
-Dru.taximaxim.codekeeper.parser.bodycache.maxcontexts=16384
```

The values above are the conservative defaults. A generation is atomically
retired when any limit is exceeded; parsers already using it remain valid.
Increasing these limits by large multipliers did not improve comparison time on
the measured OmniX workload and increased retained parser state, RSS, and the
risk of an out-of-memory failure. Keep the conservative defaults unless an A/B
measurement on the target workload proves that another profile is both faster
and within the process-memory limit.

For the distribution launchers, CLI arguments must precede `-vmargs` and JVM
arguments must follow it:

```shell
./pgcodekeeper-cli.sh -s SOURCE -t DEST -o migration.sql \
  -vmargs -Xms256m -Xmx3g \
  -Dru.taximaxim.codekeeper.parser.maxpending=22
```

When starting the JAR directly, omit `-vmargs` and place the JVM arguments
before `-jar`.

An option and its value must be two separate arguments, or be joined with `=`
inside one argument (`--jdbc-fetch-size 64` or `--jdbc-fetch-size=64`). A single
argument that joins them with a space, which is what an unquoted wrapper
variable produces, is rejected with a diagnostic instead of being guessed at.

`-Xmx4g` limits the Java heap, not total process memory, so it does not
guarantee that RSS remains below 4 GiB. Metaspace, thread stacks, the code
cache, GC bookkeeping, and direct buffers are all charged on top of the heap:
a measured project-to-database `DIFF` of the development database peaked at
**4.05 GiB RSS with `-Xmx4g`**, so budget about **4.3 GiB** for the process.

Size the machine accordingly before raising the heap. A runner advertised as
4 GB cannot hold a `-Xmx4g` run and will have the JVM killed by the OOM killer
rather than reported as a Java `OutOfMemoryError`; **`-Xmx4g` needs at least
6 GB of free RAM**. The launcher default of `-Xmx3072m` is chosen to fit a
4 GB runner, and it stays the default: raise it only where the extra RAM
exists.

## PostgreSQL comparison performance profile

For a project-to-PostgreSQL `DIFF`, the CLI enables the verified performance
profile by default:

- source and target are loaded in parallel (`--parallel-load` remains accepted
  as a compatibility no-op);
- JDBC catalog fetch size is `512`;
- matching routine bodies use the hash-first comparison path; and
- PostgreSQL catalogs use three snapshot-sharing reader workers.

These are CLI defaults only. The Core settings used by the Eclipse plug-in keep
the compatibility defaults: sequential top-level loading, JDBC fetch size `0`,
hash-first disabled, no persistent cache, and `0` parallel catalog readers.
The Eclipse plug-in therefore does not adopt this CLI profile automatically.

Use the rollback switches independently when diagnosing compatibility or
resource limits:

- `--no-parallel-load` restores sequential top-level loading and the legacy
  full routine-body exchange;
- `--pg-parallel-catalog-readers 0` restores sequential catalog reading while
  leaving top-level loading unchanged;
- `--pg-routine-body-no-hash-first` keeps top-level parallel loading but always
  fetches full routine bodies; and
- `--jdbc-fetch-size 0` restores the JDBC driver's fetch behavior.

Top-level parallel loading may keep both models alive while they are being
built. Use `--no-parallel-load` when measured peak RSS, rather than wall time,
is the binding constraint.

### Persistent PostgreSQL cache

The ANTLR cache described above stores parser prediction state only for the
current JVM. It is separate from the opt-in persistent PostgreSQL cache:

```text
--pg-catalog-cache-dir PATH
--pg-catalog-cache-max-mb 512
--pg-catalog-cache-rows
```

For project-to-database comparisons, `--pg-catalog-cache-dir` persists residual
routine bodies fetched by the hash-first path, so unchanged bodies do not cross
the network again on a warm comparison. `--pg-catalog-cache-rows` additionally
persists complete catalog reader rows. Warm row-cache reads transfer one compact
ordered hash list and then fetch only missing or changed rows. Row caching can
run together with the CLI default of three snapshot-sharing catalog readers.

The generic cache options remain explicit opt-in: neither the CLI nor Core
enables them by default. A downstream bootstrap may pass them only under its own
explicit opt-in policy; that integration policy does not change the CLI or Core
defaults. Three snapshot-sharing catalog readers remain the standard CLI mode.

Warm row caching is intended primarily to reduce the network traffic and the
round trips of repeated comparisons, which is what dominates the wall-clock time
of a comparison over a slow or high-latency link. It does not promise lower CPU
or RSS, or a faster cold-cache run. This release also uses
target-scoped cache namespaces, bounds serialized catalog-row cache-entry
payloads to 32 MiB, and hardens best-effort, non-blocking post-run pruning toward
the configured size cap.

Every run logs the effective cache configuration at `INFO` before any loading
starts, so a silently degraded CI job is visible in the log:

```text
Effective PostgreSQL cache mode: catalog cache dir absent, size cap 512 MB, row cache false, hash-first true
```

The cache is disabled when `--pg-catalog-cache-dir` is omitted. Its default size
cap is 512 MiB; change it with `--pg-catalog-cache-max-mb`. The cache options
belong to the hash-first parallel comparison path and cannot be combined with
`--no-parallel-load` or `--pg-routine-body-no-hash-first`. Use a stable private
directory if the cache should survive between CI runs.

## PostgreSQL migration correctness

Owned sequences are preserved when their owning column or table has to be
recreated: the migration detaches and reattaches the existing sequence instead
of replacing it, retaining its OID, current value, properties, owner, and ACL.
If a required owner or dependency action is filtered out or cannot be proven
safe, generation fails before returning a partial migration.

PostgreSQL full analysis also tolerates incomplete parser-recovery trees and
handles alias-less `ROWS FROM` correctly. Regression coverage includes valid
qualified composite arrays and casts through direct, project, and JDBC paths;
that syntax was already accepted by the grammar and is not a new language
extension in this release.

## Build

Build requires Java (JDK) 17+ and Apache Maven 3.9+.

```shell
git clone --branch neo https://github.com/Fenoman/pgcodekeeper-cli.git
git clone --branch neo https://github.com/Fenoman/pgcodekeeper-core.git
cd pgcodekeeper-cli

# the release workflow holds the only copy of the Core pin; read it instead
# of hard-coding a commit here
CORE_REF="$(sed -n 's/^ *CORE_REF: *//p' .github/workflows/release.yaml | head -1)"
git -C ../pgcodekeeper-core checkout "$CORE_REF"

M2="$(mktemp -d)"
mvn -f ../pgcodekeeper-core/pom.xml clean install \
  -Dmaven.repo.local="$M2"
mvn clean verify -Dmaven.repo.local="$M2"
cmp ../pgcodekeeper-core/target/pgcodekeeper-core-15.3.0-neo1.jar \
  "$M2/org/pgcodekeeper/pgcodekeeper-core/15.3.0-neo1/pgcodekeeper-core-15.3.0-neo1.jar"
```

The separate `15.3.0-neo1` Core coordinate and the dependency identity test
prevent a stale stock Core from being bundled silently. The isolated repository
also makes the source-to-binary relationship reproducible. The Core commit is
pinned once, by `env.CORE_REF` in `.github/workflows/release.yaml`; both the
release job and the build snippet above read that single value, so a release
and a local build never disagree. Change the pin only together with the Core
version and the dependency identity test. Binaries will be created in
`pgcodekeeper-cli/target`.
