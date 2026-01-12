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

import org.pgcodekeeper.cli.exception.LibraryObjectDuplicationException;
import org.pgcodekeeper.cli.localizations.Messages;
import org.pgcodekeeper.core.PgCodekeeperException;
import org.pgcodekeeper.core.api.DatabaseFactory;
import org.pgcodekeeper.core.api.PgCodeKeeperApi;
import org.pgcodekeeper.core.loader.FullAnalyze;
import org.pgcodekeeper.core.loader.LibraryLoader;
import org.pgcodekeeper.core.loader.ProjectLoader;
import org.pgcodekeeper.core.schema.AbstractDatabase;
import org.pgcodekeeper.core.schema.PgOverride;
import org.pgcodekeeper.core.xmlstore.DependenciesXmlStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

public final class PgDiffCli {
    private static final Logger LOG = LoggerFactory.getLogger(PgDiffCli.class);
    private final List<Object> errors = new ArrayList<>();
    private final CliArgs arguments;

    public PgDiffCli(CliArgs arguments) {
        this.arguments = arguments;
    }

    public void updateProject()
            throws IOException, InterruptedException, PgCodekeeperException {
        AbstractDatabase oldDatabase = loadOldDatabaseWithLibraries();
        AbstractDatabase newDatabase = loadNewDatabaseWithLibraries();

        PgCodeKeeperApi.update(arguments, oldDatabase, newDatabase,
                arguments.getOutputTarget(), arguments.getIgnoreLists(), null);
    }

    public void exportProject() throws IOException, InterruptedException, PgCodekeeperException {
        AbstractDatabase newDb = loadDatabaseSchema(arguments.getNewSrc());
        PgCodeKeeperApi.export(arguments, newDb, arguments.getOutputTarget(), arguments.getIgnoreLists(), null);
    }

    public String createDiff() throws InterruptedException, IOException, PgCodekeeperException {
        AbstractDatabase oldDatabase = loadOldDatabaseWithLibraries();
        AbstractDatabase newDatabase = loadNewDatabaseWithLibraries();
        return PgCodeKeeperApi.diff(arguments, oldDatabase, newDatabase, arguments.getIgnoreLists());
    }

    public AbstractDatabase loadNewDatabaseWithLibraries()
            throws IOException, InterruptedException, PgCodekeeperException {
        LOG.info(Messages.PgDiffCli_log_load_new_db);
        AbstractDatabase newDatabase = loadDatabaseSchema(arguments.getNewSrc());
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
        loadOverrides(newDatabase, arguments.getNewSrc());

        LOG.info(Messages.PgDiffCli_log_start_db_analyze);
        analyzeDatabase(newDatabase);

        return newDatabase;
    }

    public AbstractDatabase loadOldDatabaseWithLibraries()
            throws IOException, InterruptedException, PgCodekeeperException {
        LOG.info(Messages.PgDiffCli_log_load_old_db);
        AbstractDatabase oldDatabase = loadDatabaseSchema(arguments.getOldSrc());

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
        loadOverrides(oldDatabase, arguments.getOldSrc());

        LOG.info(Messages.PgDiffCli_log_start_db_analyze);
        analyzeDatabase(oldDatabase);

        return oldDatabase;
    }

    private void analyzeDatabase(AbstractDatabase db)
            throws InterruptedException, IOException, PgCodekeeperException {
        FullAnalyze.fullAnalyze(db, errors);
        assertErrors();
    }

    private void loadOverrides(AbstractDatabase db, String source)
            throws InterruptedException, IOException, PgCodekeeperException {
        if (SourceFormat.PARSED != SourceFormat.parsePath(source)) {
            return;
        }

        new ProjectLoader(source, arguments, errors).loadOverrides(db);
        assertErrors();
    }

    private void loadLibraries(AbstractDatabase db, Collection<String> libXmls, Collection<String> libs,
            Collection<String> libsWithoutPrivileges)
            throws InterruptedException, IOException, PgCodekeeperException {
        LibraryLoader ll = new LibraryLoader(db, Utils.getMetaPath(), errors);

        for (String xml : libXmls) {
            ll.loadXml(new DependenciesXmlStore(Paths.get(xml)), arguments);
        }

        ll.loadLibraries(arguments, false, libs);
        ll.loadLibraries(arguments, true, libsWithoutPrivileges);
        assertErrors();
    }

    /**
     * Loads database schema choosing the provided method.
     *
     * @param srcPath path to the database source to load
     *
     * @return the loaded database
     */
    private AbstractDatabase loadDatabaseSchema(String srcPath)
            throws InterruptedException, IOException, PgCodekeeperException {
        var factory = new DatabaseFactory(arguments, arguments.isIgnoreErrors(), false);
        return switch (SourceFormat.parsePath(srcPath)) {
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

    public List<Object> getErrors() {
        return errors;
    }
}
