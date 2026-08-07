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
import org.pgcodekeeper.core.database.api.loader.ComparisonLoaderFactories;
import org.pgcodekeeper.core.database.api.loader.ILoader;
import org.pgcodekeeper.core.database.api.loader.ILoaderFactory;
import org.pgcodekeeper.core.database.base.loader.LoaderFactories;
import org.pgcodekeeper.core.dependencieslist.DependenciesReader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

public final class PgDiffCli {

    private static final Logger LOG = LoggerFactory.getLogger(PgDiffCli.class);

    private final CliArgs arguments;

    public PgDiffCli(CliArgs arguments) {
        this.arguments = arguments;
    }

    public void updateProject()
            throws IOException, InterruptedException {
        addIgnoreLists();
        boolean previous = arguments.isCollectObjectReferences();
        arguments.setCollectObjectReferences(false);
        try {
            var oldDbLoader = getDatabaseLoader(arguments.getOldSrc(),
                    arguments.getSourceLibXmls(), arguments.getSourceLibs(), arguments.getSourceLibsWithoutPriv());
            var newDbLoader = getDatabaseLoader(arguments.getNewSrc(),
                    arguments.getTargetLibXmls(), arguments.getTargetLibs(), arguments.getTargetLibsWithoutPriv());

            PgCodeKeeperApi.exportToProject(arguments.getProvider(), oldDbLoader, newDbLoader,
                    Path.of(arguments.getOutputTarget()), arguments);

            assertErrorsEmpty();
        } finally {
            arguments.setCollectObjectReferences(previous);
        }
    }

    public void exportProject() throws IOException, InterruptedException {
        addIgnoreLists();
        boolean previous = arguments.isCollectObjectReferences();
        arguments.setCollectObjectReferences(false);
        try {
            var newDbLoader = getDatabaseLoader(arguments.getNewSrc(),
                    arguments.getTargetLibXmls(), arguments.getTargetLibs(), arguments.getTargetLibsWithoutPriv());

            String structureFile = arguments.getStructureFile();
            PgCodeKeeperApi.exportToProject(arguments.getProvider(), null, newDbLoader,
                    Path.of(arguments.getOutputTarget()), false,
                    structureFile == null ? null : Paths.get(structureFile), arguments);

            assertErrorsEmpty();
        } finally {
            arguments.setCollectObjectReferences(previous);
        }
    }

    public String createDiff() throws InterruptedException, IOException {
        addIgnoreLists();
        boolean previous = arguments.isCollectObjectReferences();
        arguments.setCollectObjectReferences(false);
        try {
            String script;
            if (arguments.isParallelLoad() || arguments.requiresComparisonLoaderFactories()) {
                if (arguments.getAdditionalDepsPath() != null) {
                    addAdditionalDependencies();
                }
                var loaderFactories = new ComparisonLoaderFactories(
                        getDatabaseLoaderFactory(arguments.getOldSrc(),
                                arguments.getSourceLibXmls(), arguments.getSourceLibs(),
                                arguments.getSourceLibsWithoutPriv()),
                        getDatabaseLoaderFactory(arguments.getNewSrc(),
                                arguments.getTargetLibXmls(), arguments.getTargetLibs(),
                                arguments.getTargetLibsWithoutPriv()));
                script = PgCodeKeeperApi.diff(arguments.getProvider(), loaderFactories, arguments);
            } else {
                var oldDbLoader = getDatabaseLoader(arguments.getOldSrc(),
                        arguments.getSourceLibXmls(), arguments.getSourceLibs(),
                        arguments.getSourceLibsWithoutPriv());
                var newDbLoader = getDatabaseLoader(arguments.getNewSrc(),
                        arguments.getTargetLibXmls(), arguments.getTargetLibs(),
                        arguments.getTargetLibsWithoutPriv());
                if (arguments.getAdditionalDepsPath() != null) {
                    addAdditionalDependencies();
                }
                script = PgCodeKeeperApi.diff(arguments.getProvider(), oldDbLoader, newDbLoader, arguments);
            }

            assertErrorsEmpty();
            return script;
        } finally {
            arguments.setCollectObjectReferences(previous);
        }
    }

    /**
     * Reads the source and returns the dependency lines the graph mode prints.
     * <p>
     * The walk goes over the model, so the file-to-object-location index the
     * loader can build is dead weight here: its only readers are the script
     * mode, the plug-in's analysis replay and the library merge. The caller's
     * setting is restored, because it belongs to the caller and not to this
     * run.
     */
    public List<String> analyzeDependencies() throws IOException, InterruptedException {
        boolean previous = arguments.isCollectObjectReferences();
        arguments.setCollectObjectReferences(false);
        try {
            ILoader dbLoader = getDatabaseLoader(arguments.getNewSrc(),
                    arguments.getTargetLibXmls(), arguments.getTargetLibs(),
                    arguments.getTargetLibsWithoutPriv());
            LOG.info(Messages.Main_log_build_graph_deps);
            List<String> dependencies = PgCodeKeeperApi.analyzeDependencies(dbLoader,
                    arguments.getGraphNames(), arguments.getGraphDepth(), arguments.isGraphReverse(),
                    arguments.getGraphFilterTypes(), arguments.isGraphInvertFilter());
            // the contract diff, parse and batch all hold: a statement that did
            // not load declares nothing, so the graph built without it is
            // missing edges - and a deployment ordered off it would be wrong
            // with nothing on stderr and exit 0 to say so
            assertErrorsEmpty();
            return dependencies;
        } finally {
            arguments.setCollectObjectReferences(previous);
        }
    }

    public List<Object> getErrors() {
        return Collections.unmodifiableList(arguments.getErrors());
    }

    public ILoader getDatabaseLoader(String srcPath, Collection<String> libXmls, Collection<String> libs,
                                     Collection<String> libsWithoutPriv) {
        IDatabaseProvider provider = arguments.getProvider();

        return switch (SourceFormat.parsePath(srcPath)) {
            case DB -> provider.getJdbcLoader(srcPath, arguments);
            case DUMP -> provider.getDumpLoader(Paths.get(srcPath), arguments);
            case PARSED -> provider.getProjectLoader(Paths.get(srcPath), arguments, libXmls, libs, libsWithoutPriv,
                    Utils.getMetaPath());
        };
    }

    public ILoaderFactory getDatabaseLoaderFactory(String srcPath,
            Collection<String> libXmls, Collection<String> libs,
            Collection<String> libsWithoutPriv) {
        IDatabaseProvider provider = arguments.getProvider();

        return switch (SourceFormat.parsePath(srcPath)) {
            case DB -> LoaderFactories.of(
                    sideSettings -> provider.getJdbcLoader(srcPath, sideSettings));
            case DUMP -> {
                Path path = Paths.get(srcPath);
                yield LoaderFactories.of(
                        sideSettings -> provider.getDumpLoader(path, sideSettings));
            }
            case PARSED -> {
                Path path = Paths.get(srcPath);
                Path metaPath = Utils.getMetaPath();
                List<String> immutableXmls = List.copyOf(libXmls);
                List<String> immutableLibs = List.copyOf(libs);
                List<String> immutableNoPriv = List.copyOf(libsWithoutPriv);
                yield LoaderFactories.project(path,
                        sideSettings -> provider.getProjectLoader(path, sideSettings,
                                immutableXmls, immutableLibs, immutableNoPriv, metaPath));
            }
        };
    }

    /**
     * The one gate every mode of this CLI passes through: a source that
     * accumulated load errors must not go on producing output as if it had
     * loaded, unless {@code --ignore-errors} says so.
     * <p>
     * Public because {@code Application.graph} has no {@code PgDiffCli} method
     * of its own to call: it drives the loader directly and asks this gate
     * itself.
     *
     * @throws LoadErrorsException if the load reported errors and
     *                             {@code --ignore-errors} was not given
     */
    public void assertErrorsEmpty() {
        if (!getErrors().isEmpty() && !arguments.isIgnoreErrors()) {
            throw new LoadErrorsException(Messages.PgDiffCli_error_while_load_database);
        }
    }

    private void addIgnoreLists() throws IOException {
        for (String ignorePath : arguments.getIgnoreLists()) {
            if (ignorePath != null) {
                arguments.addIgnoreList(Paths.get(ignorePath));
            }
        }

        if (arguments.getIgnoreSchemaListPath() != null) {
            arguments.addIgnoreSchemaList(Paths.get(arguments.getIgnoreSchemaListPath()));
        }
    }

    private void addAdditionalDependencies() {
        var additionalDependencies = DependenciesReader.getDependencies(Paths.get(arguments.getAdditionalDepsPath()));
        arguments.addAdditionalDependencies(additionalDependencies);
    }
}
