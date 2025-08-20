/*******************************************************************************
 * Copyright 2017-2025 TAXTELECOM, LLC
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

import org.pgcodekeeper.cli.exception.LibraryObjectDuplicationException;
import org.pgcodekeeper.cli.localizations.Messages;
import org.pgcodekeeper.core.PgCodekeeperException;
import org.pgcodekeeper.core.PgDiff;
import org.pgcodekeeper.core.api.DatabaseFactory;
import org.pgcodekeeper.core.api.PgCodeKeeperApi;
import org.pgcodekeeper.core.loader.FullAnalyze;
import org.pgcodekeeper.core.loader.LibraryLoader;
import org.pgcodekeeper.core.loader.ProjectLoader;
import org.pgcodekeeper.core.schema.AbstractDatabase;
import org.pgcodekeeper.core.schema.PgOverride;
import org.pgcodekeeper.core.xmlstore.DependenciesXmlStore;

import java.io.IOException;
import java.nio.file.Paths;
import java.util.Collection;
import java.util.List;

public final class PgDiffCli extends PgDiff {

    private final CliArgs arguments;

    public PgDiffCli(CliArgs arguments) {
        super(arguments);
        this.arguments = arguments;
    }

    public void updateProject()
            throws IOException, InterruptedException, PgCodekeeperException {
        AbstractDatabase oldDatabase = loadOldDatabaseWithLibraries();
        AbstractDatabase newDatabase = loadNewDatabaseWithLibraries();

        PgCodeKeeperApi.update(settings, oldDatabase, newDatabase,
                arguments.getOutputTarget(), arguments.getIgnoreLists(), null);
    }

    public void exportProject() throws IOException, InterruptedException, PgCodekeeperException {
        AbstractDatabase newDb = loadNewDatabase();
        PgCodeKeeperApi.export(settings, newDb, arguments.getOutputTarget(), arguments.getIgnoreLists(), null);
    }

    public String createDiff() throws InterruptedException, IOException, PgCodekeeperException {
        AbstractDatabase oldDatabase = loadOldDatabaseWithLibraries();
        AbstractDatabase newDatabase = loadNewDatabaseWithLibraries();
        return PgCodeKeeperApi.diff(settings, oldDatabase, newDatabase, arguments.getIgnoreLists());
    }

    public AbstractDatabase loadNewDatabaseWithLibraries()
            throws IOException, InterruptedException, PgCodekeeperException {
        LOG.info(Messages.PgDiffCli_log_load_new_db);
        AbstractDatabase newDatabase = loadNewDatabase();
        LOG.info(Messages.PgDiffCli_log_load_new_db_lib);
        loadLibraries(newDatabase, arguments.getTargetLibXmls(), arguments.getTargetLibs(),
                arguments.getTargetLibsWithoutPriv());

        List<PgOverride> overrides = newDatabase.getOverrides();
        if (arguments.isLibSafeMode() && !overrides.isEmpty()) {
            LOG.error(Messages.PgDiffCli_log_lib_have_dupl);
            throw new LibraryObjectDuplicationException(overrides);
        }

        // read additional privileges from special folder
        LOG.info(Messages.PgDiffCli_log_load_new_db_overrides);
        loadOverrides(newDatabase, arguments.getNewSrcFormat(), arguments.getNewSrc());

        LOG.info(Messages.PgDiffCli_log_start_db_analyze);
        analyzeDatabase(newDatabase);

        return newDatabase;
    }

    public AbstractDatabase loadOldDatabaseWithLibraries()
            throws IOException, InterruptedException, PgCodekeeperException {
        LOG.info(Messages.PgDiffCli_log_load_old_db);
        AbstractDatabase oldDatabase = loadOldDatabase();

        LOG.info(Messages.PgDiffCli_log_load_old_db_lib);
        loadLibraries(oldDatabase, arguments.getSourceLibXmls(), arguments.getSourceLibs(),
                arguments.getSourceLibsWithoutPriv());

        List<PgOverride> overrides = oldDatabase.getOverrides();
        if (arguments.isLibSafeMode() && !overrides.isEmpty()) {
            LOG.error(Messages.PgDiffCli_log_lib_have_dupl);
            throw new LibraryObjectDuplicationException(overrides);
        }
        LOG.info(Messages.PgDiffCli_log_load_old_db_overrides);
        // read additional privileges from special folder
        loadOverrides(oldDatabase, arguments.getOldSrcFormat(), arguments.getOldSrc());

        LOG.info(Messages.PgDiffCli_log_start_db_analyze);
        analyzeDatabase(oldDatabase);

        return oldDatabase;
    }

    private void analyzeDatabase(AbstractDatabase db)
            throws InterruptedException, IOException, PgCodekeeperException {
        FullAnalyze.fullAnalyze(db, errors);
        assertErrors();
    }

    private void loadOverrides(AbstractDatabase db, SourceFormat format, String source)
            throws InterruptedException, IOException, PgCodekeeperException {
        if (SourceFormat.PARSED != format) {
            return;
        }

        new ProjectLoader(source, settings, errors).loadOverrides(db);
        assertErrors();
    }

    private void loadLibraries(AbstractDatabase db, Collection<String> libXmls, Collection<String> libs,
            Collection<String> libsWithoutPriv)
            throws InterruptedException, IOException, PgCodekeeperException {
        LibraryLoader ll = new LibraryLoader(db, Utils.getMetaPath(), errors);

        for (String xml : libXmls) {
            ll.loadXml(new DependenciesXmlStore(Paths.get(xml)), settings);
        }

        ll.loadLibraries(settings, false, libs);
        ll.loadLibraries(settings, true, libsWithoutPriv);
        assertErrors();
    }

    private AbstractDatabase loadNewDatabase()
            throws IOException, InterruptedException, PgCodekeeperException {
        return loadDatabaseSchema(arguments.getNewSrcFormat(), arguments.getNewSrc());
    }

    private AbstractDatabase loadOldDatabase()
            throws IOException, InterruptedException, PgCodekeeperException {
        return loadDatabaseSchema(arguments.getOldSrcFormat(), arguments.getOldSrc());
    }

    /**
     * Loads database schema choosing the provided method.
     *
     * @param format  format of the database source, must be "dump", "parsed" or
     *                "db" otherwise exception is thrown
     * @param srcPath path to the database source to load
     *
     * @return the loaded database
     */
    private AbstractDatabase loadDatabaseSchema(SourceFormat format, String srcPath)
            throws InterruptedException, IOException, PgCodekeeperException {
        var factory = new DatabaseFactory(settings, arguments.isIgnoreErrors(), false);
        return switch (format) {
        case DB -> factory.loadFromJdbc(srcPath, arguments.getIgnoreSchemaList());
        case DUMP -> factory.loadFromDump(srcPath);
        case PARSED -> factory.loadFromProject(srcPath, arguments.getIgnoreSchemaList());
        };
    }

    private void assertErrors() throws PgCodekeeperException {
        if (!errors.isEmpty() && !arguments.isIgnoreErrors()) {
            throw new PgCodekeeperException(Messages.PgDiffCli_error_while_load_database);
        }
    }
}
