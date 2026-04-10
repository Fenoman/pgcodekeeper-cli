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

import org.pgcodekeeper.cli.localizations.Messages;
import org.pgcodekeeper.core.api.PgCodeKeeperApi;
import org.pgcodekeeper.core.database.api.IDatabaseProvider;
import org.pgcodekeeper.core.database.api.loader.ILoader;
import org.pgcodekeeper.core.dependencieslist.DependenciesReader;
import org.pgcodekeeper.core.settings.DiffSettings;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

public final class PgDiffCli {

    private final CliArgs arguments;
    private final DiffSettings diffSettings;

    public PgDiffCli(CliArgs arguments, DiffSettings diffSettings) {
        this.arguments = arguments;
        this.diffSettings = diffSettings;
    }

    public void updateProject()
            throws IOException, InterruptedException {
        addIgnoreLists();
        var oldDbLoader = getDatabaseLoader(arguments.getOldSrc(),
                arguments.getSourceLibXmls(), arguments.getSourceLibs(), arguments.getSourceLibsWithoutPriv());
        var newDbLoader = getDatabaseLoader(arguments.getNewSrc(),
                arguments.getTargetLibXmls(), arguments.getTargetLibs(), arguments.getTargetLibsWithoutPriv());

        PgCodeKeeperApi.exportToProject(arguments.getProvider(), oldDbLoader, newDbLoader,
                Path.of(arguments.getOutputTarget()), diffSettings);

        assertErrorsEmpty();
    }

    public void exportProject() throws IOException, InterruptedException {
        addIgnoreLists();
        var newDbLoader = getDatabaseLoader(arguments.getNewSrc(),
                arguments.getTargetLibXmls(), arguments.getTargetLibs(), arguments.getTargetLibsWithoutPriv());

        PgCodeKeeperApi.exportToProject(arguments.getProvider(), null, newDbLoader,
                Path.of(arguments.getOutputTarget()), diffSettings);

        assertErrorsEmpty();
    }

    public String createDiff() throws InterruptedException, IOException {
        addIgnoreLists();
        var oldDbLoader = getDatabaseLoader(arguments.getOldSrc(),
                arguments.getSourceLibXmls(), arguments.getSourceLibs(), arguments.getSourceLibsWithoutPriv());
        var newDbLoader = getDatabaseLoader(arguments.getNewSrc(),
                arguments.getTargetLibXmls(), arguments.getTargetLibs(), arguments.getTargetLibsWithoutPriv());

        if (arguments.getAdditionalDependencies() != null) {
            addAdditionalDependencies();
        }
        var script = PgCodeKeeperApi.diff(arguments.getProvider(), oldDbLoader, newDbLoader, diffSettings);

        assertErrorsEmpty();

        return script;
    }

    public List<Object> getErrors() {
        return Collections.unmodifiableList(diffSettings.getErrors());
    }

    public ILoader getDatabaseLoader(String srcPath, Collection<String> libXmls, Collection<String> libs,
                                     Collection<String> libsWithoutPriv) {
        IDatabaseProvider provider = arguments.getProvider();

        return switch (SourceFormat.parsePath(srcPath)) {
            case DB -> provider.getJdbcLoader(srcPath, diffSettings);
            case DUMP -> provider.getDumpLoader(Paths.get(srcPath), diffSettings);
            case PARSED -> provider.getProjectLoader(Paths.get(srcPath), diffSettings, libXmls, libs, libsWithoutPriv,
                    Utils.getMetaPath());
        };
    }

    private void assertErrorsEmpty() {
        if (!getErrors().isEmpty() && !arguments.isIgnoreErrors()) {
            throw new IllegalStateException(Messages.PgDiffCli_error_while_load_database);
        }
    }

    private void addIgnoreLists() throws IOException {
        for (String ignorePath : arguments.getIgnoreLists()) {
            if (ignorePath != null) {
                diffSettings.addIgnoreList(Paths.get(ignorePath));
            }
        }

        if (arguments.getIgnoreSchemaList() != null) {
            diffSettings.addIgnoreSchemaList(Paths.get(arguments.getIgnoreSchemaList()));
        }
    }

    private void addAdditionalDependencies() {
        var additionalDependencies = DependenciesReader.getDependencies(Paths.get(arguments.getAdditionalDependencies()));
        diffSettings.addAdditionalDependencies(additionalDependencies);
    }
}
