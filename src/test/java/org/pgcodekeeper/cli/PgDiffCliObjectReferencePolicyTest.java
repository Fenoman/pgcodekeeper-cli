/*******************************************************************************
 * Copyright 2017-2026 TAXTELECOM, LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *******************************************************************************/
package org.pgcodekeeper.cli;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.pgcodekeeper.core.database.pg.PgDatabaseProvider;
import org.pgcodekeeper.core.database.pg.loader.PgDumpLoader;
import org.pgcodekeeper.core.database.pg.loader.PgProjectLoader;
import org.pgcodekeeper.core.settings.ISettings;

class PgDiffCliObjectReferencePolicyTest {

    @TempDir
    Path tempDir;

    @ParameterizedTest
    @ValueSource(booleans = { false, true })
    void comparisonDisablesBothLoaderIndexesAndRestoresSetting(boolean parallel)
            throws Exception {
        Path oldDump = dump("old.sql");
        Path newDump = dump("new.sql");
        var args = arguments(oldDump, newDump, parallel);

        String script = new PgDiffCli(args).createDiff();

        Assertions.assertEquals("", script);
        Assertions.assertTrue(args.isCollectObjectReferences());
        Assertions.assertTrue(args.disabledReads.get() >= 2,
                "both PostgreSQL loaders must observe the disabled policy");
    }

    @ParameterizedTest
    @ValueSource(booleans = { false, true })
    void comparisonRestoresSettingAfterLoadFailure(boolean parallel) throws Exception {
        Path newDump = dump("new.sql");
        Path missing = tempDir.resolve("missing.sql");
        var args = arguments(missing, newDump, parallel);

        Assertions.assertThrows(IOException.class, () -> new PgDiffCli(args).createDiff());

        Assertions.assertTrue(args.isCollectObjectReferences());
    }

    /**
     * The exporter never reads the file-to-object-location index, so building
     * it on this path costs a copy of every location in the source for nothing.
     */
    @Test
    void exportDisablesLoaderIndexAndRestoresSetting() throws Exception {
        Path source = dump("export-source.sql");
        Path target = Files.createDirectory(tempDir.resolve("exported"));
        var args = new TrackingCliArgs();
        args.parse(new String[] { "--mode", "PARSE", "-o", target.toString(), source.toString() });

        new PgDiffCli(args).exportProject();

        Assertions.assertEquals(1, args.disabledReads.get(),
                "the exported source must be loaded without the reference index");
        Assertions.assertTrue(args.isCollectObjectReferences(),
                "the caller's setting must survive the export");
    }

    /**
     * Project update loads both sides and reads the index on neither.
     */
    @Test
    void updateDisablesBothLoaderIndexesAndRestoresSetting() throws Exception {
        Path source = dump("update-source.sql");
        Path project = Files.createDirectory(tempDir.resolve("updated"));
        var args = new TrackingCliArgs();
        args.parse(new String[] { "--mode", "PARSE", "--update-project",
                "-o", project.toString(), source.toString() });

        new PgDiffCli(args).updateProject();

        Assertions.assertEquals(2, args.disabledReads.get(),
                "both sides of an update must be loaded without the reference index");
        Assertions.assertTrue(args.isCollectObjectReferences(),
                "the caller's setting must survive the update");
    }

    @Test
    void publicLoaderOutsideComparisonKeepsReferenceIndexEnabled() throws Exception {
        Path dump = dump("source.sql");
        var args = arguments(dump, dump, false);
        var diff = new PgDiffCli(args);

        var loader = diff.getDatabaseLoader(dump.toString(),
                args.getSourceLibXmls(), args.getSourceLibs(), args.getSourceLibsWithoutPriv());
        var database = loader.load();

        Assertions.assertTrue(args.isCollectObjectReferences());
        Assertions.assertFalse(database.getObjReferences().isEmpty());
    }

    private TrackingCliArgs arguments(Path oldDump, Path newDump, boolean parallel)
            throws Exception {
        var args = new TrackingCliArgs();
        if (parallel) {
            args.parse(new String[] { "-t", oldDump.toString(), "-s", newDump.toString(),
                    "--parallel-load" });
        } else {
            args.parse(new String[] { "-t", oldDump.toString(), "-s", newDump.toString() });
        }
        return args;
    }

    private Path dump(String name) throws IOException {
        Path path = tempDir.resolve(name);
        Files.writeString(path, "CREATE TABLE public.policy_probe (id integer);\n");
        return path;
    }

    private static final class TrackingCliArgs extends CliArgs {

        private final AtomicInteger disabledReads = new AtomicInteger();
        private final PgDatabaseProvider provider = new PgDatabaseProvider() {
            @Override
            public PgDumpLoader getDumpLoader(Path path, ISettings settings) {
                if (!settings.isCollectObjectReferences()) {
                    disabledReads.incrementAndGet();
                }
                return super.getDumpLoader(path, settings);
            }

            @Override
            public PgProjectLoader getProjectLoader(Path path, ISettings settings,
                    Collection<String> libXmls, Collection<String> libs,
                    Collection<String> libsWithoutPriv, Path metaPath) {
                if (!settings.isCollectObjectReferences()) {
                    disabledReads.incrementAndGet();
                }
                return super.getProjectLoader(path, settings, libXmls, libs,
                        libsWithoutPriv, metaPath);
            }
        };

        @Override
        public PgDatabaseProvider getProvider() {
            return provider;
        }
    }
}
