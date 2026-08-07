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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.pgcodekeeper.core.api.PgCodeKeeperApi;
import org.pgcodekeeper.core.database.api.loader.ComparisonLoaderFactories;
import org.pgcodekeeper.core.database.api.loader.ILoader;
import org.pgcodekeeper.core.database.api.loader.ILoaderFactory;
import org.pgcodekeeper.core.database.api.script.IScriptBuilder;
import org.pgcodekeeper.core.database.pg.PgDatabaseProvider;
import org.pgcodekeeper.core.database.pg.loader.PgDumpLoader;
import org.pgcodekeeper.core.database.pg.loader.PgProjectLoader;
import org.pgcodekeeper.core.database.pg.schema.PgDatabase;
import org.pgcodekeeper.core.settings.CoreSettings;
import org.pgcodekeeper.core.settings.ISettings;

class PgDiffCliFactoryTest {

    private static final String OLD_DIAGNOSTIC = "OLD diagnostic";
    private static final String NEW_DIAGNOSTIC = "NEW diagnostic";

    @TempDir
    Path tempDir;

    @Test
    void parallelDiffUsesDistinctSideSettingsAndOrderedDiagnostics()
            throws Exception {
        Path oldProject = createOldProject();
        Path newDump = createNewDump();
        var provider = new RecordingProvider(oldProject, newDump);
        var args = arguments(provider, oldProject, newDump,
                "--parallel-load",
                "--pg-routine-body-hash-first",
                "--pg-routine-body-residual-batch-count", "17",
                "--pg-routine-body-residual-batch-bytes", "4096",
                "--ignore-errors");

        String script = new PgDiffCli(args).createDiff();

        ISettings oldSettings = provider.oldSettings.get();
        ISettings newSettings = provider.newSettings.get();
        assertNotSame(args, oldSettings);
        assertNotSame(args, newSettings);
        assertNotSame(oldSettings, newSettings);
        assertFalse(oldSettings.isCollectObjectReferences());
        assertFalse(newSettings.isCollectObjectReferences());
        assertTrue(oldSettings.isPgRoutineBodyHashFirst());
        assertTrue(newSettings.isPgRoutineBodyHashFirst());
        assertEquals(17, oldSettings.getPgRoutineBodyResidualBatchCount());
        assertEquals(17, newSettings.getPgRoutineBodyResidualBatchCount());
        assertEquals(4096L, oldSettings.getPgRoutineBodyResidualBatchBytes());
        assertEquals(4096L, newSettings.getPgRoutineBodyResidualBatchBytes());
        assertEquals(List.of(OLD_DIAGNOSTIC, NEW_DIAGNOSTIC), args.getErrors());
        assertEquals(List.of("OLD create", "NEW create"), provider.creationEvents);
        assertEquals(1, provider.scriptBuilderCalls.get());
        assertNotSame(args, provider.renderSettings.get());
        assertNotSame(oldSettings, provider.renderSettings.get());
        assertNotSame(newSettings, provider.renderSettings.get());
        assertTrue(args.isCollectObjectReferences());
        assertTrue(script.contains("ADD COLUMN name text"), script);
    }

    @Test
    void factoryRequiredSettingsCannotFallIntoPreconstructedLoaderPath()
            throws Exception {
        Path oldProject = createOldProject();
        Path newDump = createNewDump();
        var provider = new RecordingProvider(oldProject, newDump);
        var args = new FactoryRequiredCliArgs(provider);
        // Disable parallel loading to isolate the factory requirement itself.
        assertTrue(args.parse(new String[] {
                "-t", oldProject.toString(),
                "-s", newDump.toString(),
                "--no-parallel-load",
                "--ignore-errors"
        }));
        assertFalse(args.isParallelLoad());
        assertTrue(args.requiresComparisonLoaderFactories());

        String script = new PgDiffCli(args).createDiff();

        assertNotSame(args, provider.oldSettings.get());
        assertNotSame(args, provider.newSettings.get());
        assertEquals(List.of("OLD create", "NEW create"), provider.creationEvents);
        assertTrue(script.contains("ADD COLUMN name text"), script);
    }

    @Test
    void sequentialDiffUsesSharedSettingsAndMatchesParallelBytes()
            throws Exception {
        Path oldProject = createOldProject();
        Path newDump = createNewDump();
        var sequentialProvider = new RecordingProvider(oldProject, newDump);
        // Sequential loading shares one settings object; parallel loading uses
        // side-specific snapshots and must produce the same script.
        var sequentialArgs = arguments(sequentialProvider, oldProject, newDump,
                "--no-parallel-load", "--ignore-errors");
        var parallelProvider = new RecordingProvider(oldProject, newDump);
        var parallelArgs = arguments(parallelProvider, oldProject, newDump,
                "--parallel-load", "--ignore-errors");

        String sequential = new PgDiffCli(sequentialArgs).createDiff();
        String parallel = new PgDiffCli(parallelArgs).createDiff();

        assertSame(sequentialArgs, sequentialProvider.oldSettings.get());
        assertSame(sequentialArgs, sequentialProvider.newSettings.get());
        assertSame(sequentialArgs, sequentialProvider.renderSettings.get());
        assertEquals(sequential, parallel);
        assertEquals(List.of(OLD_DIAGNOSTIC, NEW_DIAGNOSTIC), sequentialArgs.getErrors());
        assertEquals(sequentialArgs.getErrors(), parallelArgs.getErrors());
        assertTrue(sequentialArgs.isCollectObjectReferences());
        assertTrue(parallelArgs.isCollectObjectReferences());
    }

    @Test
    void projectFactorySnapshotsMutableLibraryCollections()
            throws Exception {
        Path project = createOldProject();
        var provider = new SnapshotProvider(project);
        var args = new RecordingCliArgs(provider);
        var xmls = new ArrayList<>(List.of("before.xml"));
        var libs = new ArrayList<>(List.of("before.sql"));
        var noPriv = new ArrayList<>(List.of("before-no-priv.sql"));

        ILoaderFactory factory = new PgDiffCli(args).getDatabaseLoaderFactory(
                project.toString(), xmls, libs, noPriv);
        xmls.add("after.xml");
        libs.add("after.sql");
        noPriv.add("after-no-priv.sql");
        var sideSettings = new CoreSettings();
        factory.contributeCommonConfiguration(sideSettings);

        ILoader loader = factory.create(sideSettings);

        assertSame(sideSettings, loader.getSettings());
        assertEquals(List.of("before.xml"), provider.libXmls.get());
        assertEquals(List.of("before.sql"), provider.libs.get());
        assertEquals(List.of("before-no-priv.sql"), provider.libsWithoutPriv.get());
        assertThrows(UnsupportedOperationException.class,
                () -> provider.libXmls.get().add("mutation.xml"));
        assertThrows(UnsupportedOperationException.class,
                () -> provider.libs.get().add("mutation.sql"));
        assertThrows(UnsupportedOperationException.class,
                () -> provider.libsWithoutPriv.get().add("mutation-no-priv.sql"));
        loader.close();
    }

    @Test
    void factoryHelpersFollowCoordinatorLogicalOrder()
            throws Exception {
        Path oldProject = createOldProject();
        Path newDump = createNewDump();
        var provider = new RecordingProvider(oldProject, newDump);
        var args = arguments(provider, oldProject, newDump, "--ignore-errors");
        var cli = new PgDiffCli(args);
        ILoaderFactory oldFactory = cli.getDatabaseLoaderFactory(
                oldProject.toString(), List.of(), List.of(), List.of());
        ILoaderFactory newFactory = cli.getDatabaseLoaderFactory(
                newDump.toString(), List.of(), List.of(), List.of());
        var logicalEvents = new CopyOnWriteArrayList<String>();
        var factories = new ComparisonLoaderFactories(
                recordingFactory("OLD", oldFactory, logicalEvents),
                recordingFactory("NEW", newFactory, logicalEvents));

        PgCodeKeeperApi.diff(provider, factories, args);

        assertEquals(List.of(
                "OLD contribute", "NEW contribute",
                "OLD create", "NEW create"), logicalEvents);
    }

    private RecordingCliArgs arguments(RecordingProvider provider,
            Path oldSource, Path newSource, String... extra) throws Exception {
        String[] parsed = new String[4 + extra.length];
        parsed[0] = "-t";
        parsed[1] = oldSource.toString();
        parsed[2] = "-s";
        parsed[3] = newSource.toString();
        System.arraycopy(extra, 0, parsed, 4, extra.length);
        var args = new RecordingCliArgs(provider);
        assertTrue(args.parse(parsed));
        return args;
    }

    private Path createOldProject() throws IOException {
        Path schema = tempDir.resolve("old-project/SCHEMA/public");
        Path tables = schema.resolve("TABLE");
        Files.createDirectories(tables);
        Files.writeString(schema.resolve("public.sql"), "CREATE SCHEMA public;\n");
        Files.writeString(tables.resolve("account.sql"), """
                CREATE TABLE public.account (
                    id integer
                );
                """);
        return tempDir.resolve("old-project");
    }

    private Path createNewDump() throws IOException {
        Path dump = tempDir.resolve("new.sql");
        Files.writeString(dump, """
                CREATE TABLE public.account (
                    id integer,
                    name text
                );
                """);
        return dump;
    }

    private static ILoaderFactory recordingFactory(String side,
            ILoaderFactory delegate, Collection<String> events) {
        return new ILoaderFactory() {
            @Override
            public ILoader create(ISettings settings)
                    throws IOException, InterruptedException {
                events.add(side + " create");
                return delegate.create(settings);
            }

            @Override
            public void contributeCommonConfiguration(ISettings settings)
                    throws IOException, InterruptedException {
                events.add(side + " contribute");
                delegate.contributeCommonConfiguration(settings);
            }
        };
    }

    private static final class RecordingCliArgs extends CliArgs {

        private final PgDatabaseProvider provider;

        private RecordingCliArgs(PgDatabaseProvider provider) {
            this.provider = provider;
        }

        @Override
        public PgDatabaseProvider getProvider() {
            return provider;
        }
    }

    private static final class FactoryRequiredCliArgs extends CliArgs {

        private final PgDatabaseProvider provider;

        private FactoryRequiredCliArgs(PgDatabaseProvider provider) {
            this.provider = provider;
        }

        @Override
        public PgDatabaseProvider getProvider() {
            return provider;
        }

        @Override
        public boolean requiresComparisonLoaderFactories() {
            return true;
        }
    }

    private static final class SnapshotProvider extends PgDatabaseProvider {

        private final Path project;
        private final AtomicReference<Collection<String>> libXmls = new AtomicReference<>();
        private final AtomicReference<Collection<String>> libs = new AtomicReference<>();
        private final AtomicReference<Collection<String>> libsWithoutPriv = new AtomicReference<>();

        private SnapshotProvider(Path project) {
            this.project = project;
        }

        @Override
        public PgProjectLoader getProjectLoader(Path path, ISettings settings,
                Collection<String> libXmls, Collection<String> libs,
                Collection<String> libsWithoutPriv, Path metaPath) {
            assertEquals(project, path);
            this.libXmls.set(libXmls);
            this.libs.set(libs);
            this.libsWithoutPriv.set(libsWithoutPriv);
            return super.getProjectLoader(path, settings,
                    libXmls, libs, libsWithoutPriv, metaPath);
        }
    }

    private static final class RecordingProvider extends PgDatabaseProvider {

        private final Path oldProject;
        private final Path newDump;
        private final AtomicReference<ISettings> oldSettings = new AtomicReference<>();
        private final AtomicReference<ISettings> newSettings = new AtomicReference<>();
        private final AtomicReference<ISettings> renderSettings = new AtomicReference<>();
        private final AtomicInteger scriptBuilderCalls = new AtomicInteger();
        private final List<String> creationEvents = new CopyOnWriteArrayList<>();

        private RecordingProvider(Path oldProject, Path newDump) {
            this.oldProject = oldProject;
            this.newDump = newDump;
        }

        @Override
        public PgProjectLoader getProjectLoader(Path path, ISettings settings,
                Collection<String> libXmls, Collection<String> libs,
                Collection<String> libsWithoutPriv, Path metaPath) {
            assertEquals(oldProject, path);
            oldSettings.set(settings);
            creationEvents.add("OLD create");
            return new DiagnosticProjectLoader(path, settings, libXmls,
                    libs, libsWithoutPriv, metaPath, OLD_DIAGNOSTIC);
        }

        @Override
        public PgDumpLoader getDumpLoader(Path path, ISettings settings) {
            assertEquals(newDump, path);
            newSettings.set(settings);
            creationEvents.add("NEW create");
            return new DiagnosticDumpLoader(path, settings, NEW_DIAGNOSTIC);
        }

        @Override
        public IScriptBuilder getScriptBuilder(ISettings settings) {
            renderSettings.set(settings);
            scriptBuilderCalls.incrementAndGet();
            return super.getScriptBuilder(settings);
        }
    }

    private static final class DiagnosticProjectLoader extends PgProjectLoader {

        private final String diagnostic;

        private DiagnosticProjectLoader(Path path, ISettings settings,
                Collection<String> libXmls, Collection<String> libs,
                Collection<String> libsWithoutPriv, Path metaPath,
                String diagnostic) {
            super(path, settings, libXmls, libs, libsWithoutPriv, metaPath);
            this.diagnostic = diagnostic;
        }

        @Override
        public PgDatabase loadInternal() throws InterruptedException, IOException {
            addError(diagnostic);
            return super.loadInternal();
        }
    }

    private static final class DiagnosticDumpLoader extends PgDumpLoader {

        private final String diagnostic;

        private DiagnosticDumpLoader(Path path, ISettings settings,
                String diagnostic) {
            super(path, settings);
            this.diagnostic = diagnostic;
        }

        @Override
        public PgDatabase loadInternal() throws IOException, InterruptedException {
            addError(diagnostic);
            return super.loadInternal();
        }
    }
}
