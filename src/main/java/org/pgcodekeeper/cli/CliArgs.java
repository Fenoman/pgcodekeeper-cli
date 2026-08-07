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

import org.kohsuke.args4j.*;
import org.kohsuke.args4j.spi.OptionHandler;
import org.pgcodekeeper.cli.localizations.CliArgsLocalizationsBundle;
import org.pgcodekeeper.cli.localizations.Messages;
import org.pgcodekeeper.cli.opthandlers.BooleanNoDefOptionHandler;
import org.pgcodekeeper.cli.opthandlers.DangerStatementOptionHandler;
import org.pgcodekeeper.cli.opthandlers.DbObjTypeOptionHandler;
import org.pgcodekeeper.core.Consts;
import org.pgcodekeeper.core.DangerStatement;
import org.pgcodekeeper.core.database.api.IDatabaseProvider;
import org.pgcodekeeper.core.database.api.schema.DbObjType;
import org.pgcodekeeper.core.database.base.formatter.FormatConfiguration;
import org.pgcodekeeper.core.database.ch.ChDatabaseProvider;
import org.pgcodekeeper.core.database.ms.MsDatabaseProvider;
import org.pgcodekeeper.core.database.pg.PgDatabaseProvider;
import org.pgcodekeeper.core.settings.AbstractSettings;
import org.pgcodekeeper.core.settings.ISettings;
import org.pgcodekeeper.core.settings.ProjectFileFilter;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

/**
 * Extension of {@link ISettings} with annotated CLI fields.
 * Override getters so that clients will access either CLI or parent fields
 * depending on the instance.
 */
public class CliArgs extends AbstractSettings {

    enum CliMode {
        DIFF,
        PARSE,
        GRAPH,
        BATCH
    }

    private static final String PG = "PG";
    private static final String MS = "MS";
    private static final String CH = "CH";
    private static final String URL_START_JDBC = "jdbc:"; //$NON-NLS-1$
    private static final int DEFAULT_DEPTH = 10;

    /**
     * Delimiter that separates an option name from its value inside a single
     * argument token. args4j defaults to a space, which makes such a token
     * unparseable: a 0-argument option never advances the token cursor, so the
     * parser spins forever, and a 1-argument option swallows the option name
     * into its own value. Only '=' joins a name and a value in one token here;
     * a space always separates two tokens.
     */
    private static final String OPTION_VALUE_DELIMITER = "="; //$NON-NLS-1$

    /**
     * CLI-specific performance defaults. Core ({@link ISettings}) keeps
     * conservative defaults for consumers that do not select this profile.
     */
    static final int DEFAULT_CLI_JDBC_FETCH_SIZE = 512;
    static final int DEFAULT_CLI_PG_PARALLEL_CATALOG_READERS = 3;

    private IDatabaseProvider provider;
    private volatile boolean collectObjectReferences = true;

    // SONAR-OFF
    {
        // this was moved to initializer to avoid the IDE making the field "final" on-save
        // otherwise args4j breaks
        this.allowedDangers = new ArrayList<>();
        this.allowedTypes = new ArrayList<>();
        this.graphFilterTypes = new ArrayList<>();
        this.ignoreLists = new ArrayList<>();
        this.sourceLibXmls = new ArrayList<>();
        this.sourceLibs = new ArrayList<>();
        this.sourceLibsWithoutPriv = new ArrayList<>();
        this.targetLibXmls = new ArrayList<>();
        this.targetLibs = new ArrayList<>();
        this.targetLibsWithoutPriv = new ArrayList<>();
        this.preFilePath = new ArrayList<>();
        this.postFilePath = new ArrayList<>();
        this.graphNames = new ArrayList<>();
        this.inCharsetName = Consts.UTF_8;
        this.outCharsetName = Consts.UTF_8;
        this.graphDepth = DEFAULT_DEPTH;
        this.dbType = PG;
        this.mode = CliMode.DIFF;
        // CLI defaults remain overridable by the corresponding explicit options.
        this.parallelLoad = true;
        this.jdbcFetchSize = DEFAULT_CLI_JDBC_FETCH_SIZE;
        setPgParallelCatalogReaders(DEFAULT_CLI_PG_PARALLEL_CATALOG_READERS);
        this.disableAutoLoad = false;
    }
    // SONAR-ON

    // "special" options start with "z"

    // all (external) source <-> target (internal) swaps should be done here
    // and not in the (internal) program logic to avoid confusion and accidents
    // only here, everything "source" refers to the NEW DB, and target to OLD DB

    @Option(name = "--help", help = true, usage = "Help")
    private boolean zhelp;

    @Option(name = "--version", help = true, usage = "Version")
    private boolean zversion;

    @Option(name = "--list-charsets", help = true, usage = "ListCharsets")
    private boolean zlistCharsets;

    @Option(name = "--clear-lib-cache", help = true, usage = "ClearLibCache")
    private boolean clearLibCache;

    @Option(name = "--mode", usage = "mode")
    private CliMode mode;

    @Option(name = "--batch-manifest", metaVar = CliArgsLocalizationsBundle.PATH, usage = "batch-manifest")
    private String batchManifestPath;

    @Option(name = "--project-file-filter", metaVar = CliArgsLocalizationsBundle.PATH,
            usage = "project-file-filter")
    private String projectFileFilterPath;

    private boolean projectFileFilterResolved;
    private String preparedProjectFileFilterPath;
    private ProjectFileFilter preparedProjectFileFilter;

    @Option(name = "--source", depends = "-t", aliases = "-s", metaVar = CliArgsLocalizationsBundle.PATH_OR_JDBC, usage = "source")
    @Argument(index = 0, metaVar = CliArgsLocalizationsBundle.SOURCE, usage = "source")
    private String newSrc;

    @Option(name = "--target", depends = "-s", aliases = "-t", metaVar = CliArgsLocalizationsBundle.PATH_OR_JDBC, usage = "target")
    @Argument(index = 1, metaVar = CliArgsLocalizationsBundle.DEST, usage = "target")
    private String oldSrc;

    @Option(name = "--output", aliases = "-o", metaVar = CliArgsLocalizationsBundle.PATH, usage = "output")
    private String outputTarget;

    @Option(name = "--run-on-target", aliases = "-r", forbids = "-R", usage = "run-on-target")
    private boolean runOnTarget;

    @Option(name = "--run-on", aliases = "-R", metaVar = CliArgsLocalizationsBundle.JDBC, forbids = "-r", usage = "run-on")
    private String runOnDb;

    @Option(name = "--in-charset", metaVar = CliArgsLocalizationsBundle.CHARSET, usage = "in-charset")
    private String inCharsetName;

    @Option(name = "--jdbc-fetch-size", metaVar = CliArgsLocalizationsBundle.N, usage = "jdbc-fetch-size")
    private Integer jdbcFetchSizeOption;

    // resolved value: DEFAULT_CLI_JDBC_FETCH_SIZE unless the option is given;
    // an explicit 0 keeps the JDBC driver default (fetch everything at once)
    private int jdbcFetchSize;

    @Option(name = "--out-charset", metaVar = CliArgsLocalizationsBundle.CHARSET, usage = "out-charset")
    private String outCharsetName;

    @Option(name = "--error", aliases = "-E", usage = "error")
    private boolean isDebug;

    @Option(name = "--ignore-errors", usage = "ignore-errors")
    private boolean ignoreErrors;

    @Option(name = "--no-privileges", aliases = "-P", usage = "no-privileges")
    private boolean ignorePrivileges;

    @Option(name = "--keep-newlines", aliases = "-L", usage = "keep-newlines")
    private boolean keepNewlines;

    @Option(name = "--simplify-views", usage = "simplify-views")
    private boolean simplifyView;

    @Option(name = "--add-transaction", aliases = "-X", usage = "add-transaction")
    private boolean addTransaction;

    @Option(name = "--no-check-function-bodies", aliases = "-F", usage = "no-check-function-bodies")
    private boolean disableCheckFunctionBodies;

    @Option(name = "--enable-function-bodies-dependencies", aliases = "-f", usage = "enable-function-bodies-dependencies")
    private boolean enableFunctionBodiesDependencies;

    @Option(name = "--time-zone", aliases = "-Z", metaVar = CliArgsLocalizationsBundle.TIMEZONE, usage = "time-zone")
    private String timeZone;

    @Option(name = "--pre-script", metaVar = CliArgsLocalizationsBundle.PATH, usage = "pre-script")
    private List<String> preFilePath;

    @Option(name = "--post-script", metaVar = CliArgsLocalizationsBundle.PATH, usage = "post-script")
    private List<String> postFilePath;

    @Option(name = "--ignore-column-order", usage = "ignore-column-order")
    private boolean ignoreColumnOrder;

    @Option(name = "--ignore-sequence-cache", usage = "ignore-sequence-cache")
    private boolean ignoreSequenceCache;

    @Option(name = "--no-alter-table-only", usage = "no-alter-table-only")
    private boolean noAlterTableOnly;

    @Option(name = "--ignore-column-statistics", usage = "ignore-column-statistics")
    private boolean ignoreColumnStatistics;

    @Option(name = "--generate-constraint-not-valid", aliases = "-v", usage = "generate-constraint-not-valid")
    private boolean generateConstraintNotValid;

    @Option(name = "--using-off", usage = "using-off")
    private boolean usingTypeCastOff;

    @Option(name = "--migrate-data", usage = "migrate-data")
    private boolean dataMovementMode;

    @Option(name = "--concurrently-mode", aliases = "-C", usage = "concurrently-mode")
    private boolean concurrentlyMode;

    @Option(name = "--generate-exist", usage = "generate-exist")
    private boolean generateExists;

    @Option(name = "--generate-exist-do-block", aliases = "-do", usage = "generate-exist-do-block")
    private boolean generateExistDoBlock;

    @Option(name = "--drop-before-create", usage = "drop-before-create")
    private boolean dropBeforeCreate;

    @Option(name = "--comments-to-end", usage = "comments-to-end")
    private boolean commentsToEnd;

    @Option(name = "--safe-mode", aliases = "-S", usage = "safe-mode")
    private boolean safeMode;

    @Option(name = "--allow-danger-ddl", aliases = "-D", handler = DangerStatementOptionHandler.class, usage = "allow-danger-ddl")
    private List<DangerStatement> allowedDangers;

    @Option(name = "--allowed-object", aliases = "-O", handler = DbObjTypeOptionHandler.class, usage = "allowed-object")
    private List<DbObjType> allowedTypes;

    @Option(name = "--stop-not-allowed", usage = "stop-not-allowed")
    private boolean stopNotAllowed;

    @Option(name = "--selected-only", usage = "selected-only")
    private boolean selectedOnly;

    @Option(name = "--ignore-list", aliases = "-I", metaVar = CliArgsLocalizationsBundle.PATH, usage = "ignore-list")
    private List<String> ignoreLists;

    @Option(name = "--ignore-schema", metaVar = CliArgsLocalizationsBundle.PATH, usage = "ignore-schema")
    private String ignoreSchemaListPath;

    @Option(name = "--src-lib-xml", metaVar = CliArgsLocalizationsBundle.PATH, usage = "src-lib-xml")
    private List<String> targetLibXmls;

    @Option(name = "--src-lib", metaVar = CliArgsLocalizationsBundle.PATH_OR_JDBC, usage = "src-lib")
    private List<String> targetLibs;

    @Option(name = "--src-lib-no-priv", metaVar = CliArgsLocalizationsBundle.PATH_OR_JDBC, usage = "src-lib-no-priv")
    private List<String> targetLibsWithoutPriv;

    @Option(name = "--tgt-lib-xml", metaVar = CliArgsLocalizationsBundle.PATH, usage = "tgt-lib-xml")
    private List<String> sourceLibXmls;

    @Option(name = "--tgt-lib", metaVar = CliArgsLocalizationsBundle.PATH_OR_JDBC, usage = "tgt-lib")
    private List<String> sourceLibs;

    @Option(name = "--tgt-lib-no-priv", metaVar = CliArgsLocalizationsBundle.PATH_OR_JDBC, usage = "tgt-lib-no-priv")
    private List<String> sourceLibsWithoutPriv;

    @Option(name = "--lib-safe-mode", usage = "lib-safe-mode")
    private boolean libSafeMode;

    @Option(name = "--ignore-concurrent-modification", aliases = "-m", usage = "ignore-concurrent-modification")
    private boolean ignoreConcurrentModification;

    @Option(name = "--db-type", metaVar = CliArgsLocalizationsBundle.DB_TYPES, usage = "db-type")
    private String dbType;

    @Option(name = "--update-project", usage = "update-project")
    private boolean projUpdate;

    @Option(name = "--structure-file", metaVar = CliArgsLocalizationsBundle.PATH, usage = "structure-file")
    private String structureFile;

    @Option(name = "--graph-depth", metaVar = CliArgsLocalizationsBundle.N, usage = "graph-depth")
    private int graphDepth;

    @Option(name = "--graph-reverse", depends = "--graph-name", usage = "graph-reverse")
    private boolean graphReverse;

    @Option(name = "--graph-name", metaVar = CliArgsLocalizationsBundle.NAME, usage = "graph-name")
    private List<String> graphNames;

    @Option(name = "--graph-filter-object", handler = DbObjTypeOptionHandler.class, usage = "graph-filter-object")
    private List<DbObjType> graphFilterTypes;

    @Option(name = "--graph-invert-filter", depends = "--graph-filter-object", usage = "graph-invert-filter")
    private boolean graphInvertFilter;

    @Option(name = "--cluster-name", metaVar = CliArgsLocalizationsBundle.NAME, usage = "cluster-name")
    private String clusterName;

    // Parallel loading is the CLI default; the positive option is idempotent
    // and the negative option selects sequential loading.
    @Option(name = "--parallel-load", aliases = "-par", usage = "parallel-load")
    private boolean parallelLoadOption;

    @Option(name = "--no-parallel-load", usage = "no-parallel-load")
    private boolean noParallelLoadOption;

    private boolean parallelLoad;

    // Hash-first is the CLI default for PostgreSQL DIFF comparisons; the
    // positive option is idempotent and the negative option selects full bodies.
    @Option(name = "--pg-routine-body-hash-first",
            usage = "pg-routine-body-hash-first")
    private boolean pgRoutineBodyHashFirstOption;

    @Option(name = "--pg-routine-body-no-hash-first",
            usage = "pg-routine-body-no-hash-first")
    private boolean pgRoutineBodyNoHashFirstOption;

    @Option(name = "--pg-routine-body-no-skip-matched-analysis",
            usage = "pg-routine-body-no-skip-matched-analysis")
    private boolean pgRoutineBodyNoSkipMatchedAnalysisOption =
            !ISettings.DEFAULT_PG_ROUTINE_BODY_SKIP_MATCHED_ANALYSIS;

    @Option(name = "--pg-routine-body-residual-batch-count",
            metaVar = CliArgsLocalizationsBundle.N,
            usage = "pg-routine-body-residual-batch-count")
    private Integer pgRoutineBodyResidualBatchCountOption;

    @Option(name = "--pg-routine-body-residual-batch-bytes",
            metaVar = CliArgsLocalizationsBundle.N,
            usage = "pg-routine-body-residual-batch-bytes")
    private Long pgRoutineBodyResidualBatchBytesOption;

    @Option(name = "--pg-catalog-cache-dir",
            metaVar = CliArgsLocalizationsBundle.PATH, usage = "pg-catalog-cache-dir")
    private String pgCatalogCacheDirOption;

    @Option(name = "--pg-catalog-cache-max-mb", depends = "--pg-catalog-cache-dir",
            metaVar = CliArgsLocalizationsBundle.N, usage = "pg-catalog-cache-max-mb")
    private long pgCatalogCacheMaxMbOption = ISettings.DEFAULT_PG_CATALOG_CACHE_MAX_MB;

    @Option(name = "--pg-catalog-cache-rows", depends = "--pg-catalog-cache-dir",
            usage = "pg-catalog-cache-rows")
    private boolean pgCatalogCacheRowsOption = ISettings.DEFAULT_PG_CATALOG_CACHE_ROWS;

    @Option(name = "--pg-parallel-catalog-readers", metaVar = CliArgsLocalizationsBundle.N,
            usage = "pg-parallel-catalog-readers")
    private Integer pgParallelCatalogReadersOption;

    @Option(name = "--disable-auto-load", usage = "disable-auto-load")
    private boolean disableAutoLoad;

    @Option(name = "--additional-dependencies", metaVar = CliArgsLocalizationsBundle.PATH, usage = "additional-dependencies")
    private String additionalDepsPath;

    @Option(name = "--use-actual-syntax", usage = "use-actual-syntax")
    private boolean isUseActualVersionSyntax;

    @Option(name = "--simplify-not-null", usage = "simplify-not-null")
    private boolean simplifyNotNull;

    CliMode getMode() {
        return mode;
    }

    public String getBatchManifestPath() {
        return batchManifestPath;
    }

    String getProjectFileFilterPath() {
        return projectFileFilterPath;
    }

    void prepareProjectFileFilterFrom(CliArgs parsedArgs) {
        if (parsedArgs.projectFileFilterResolved) {
            preparedProjectFileFilterPath = parsedArgs.projectFileFilterPath;
            preparedProjectFileFilter = parsedArgs.getProjectFileFilter();
        }
    }

    public boolean isClearLibCache() {
        return clearLibCache;
    }

    public String getNewSrc() {
        return newSrc;
    }

    public String getOldSrc() {
        return oldSrc;
    }

    public String getOutputTarget() {
        return outputTarget;
    }

    @Override
    public boolean isAddTransaction() {
        return addTransaction;
    }

    @Override
    public boolean isStopNotAllowed() {
        return stopNotAllowed;
    }

    public boolean isSafeMode() {
        return safeMode;
    }

    public boolean isRunOnTarget() {
        return runOnTarget;
    }

    public String getRunOnDb() {
        return runOnDb;
    }

    public Collection<DangerStatement> getAllowedDangers() {
        return Collections.unmodifiableCollection(allowedDangers);
    }

    public Collection<String> getIgnoreLists() {
        return Collections.unmodifiableCollection(ignoreLists);
    }

    public String getIgnoreSchemaListPath() {
        return ignoreSchemaListPath;
    }

    public Collection<String> getSourceLibXmls() {
        return Collections.unmodifiableCollection(sourceLibXmls);
    }

    public Collection<String> getSourceLibs() {
        return Collections.unmodifiableCollection(sourceLibs);
    }

    public Collection<String> getSourceLibsWithoutPriv() {
        return Collections.unmodifiableCollection(sourceLibsWithoutPriv);
    }

    public Collection<String> getTargetLibXmls() {
        return Collections.unmodifiableCollection(targetLibXmls);
    }

    public Collection<String> getTargetLibs() {
        return Collections.unmodifiableCollection(targetLibs);
    }

    public Collection<String> getTargetLibsWithoutPriv() {
        return Collections.unmodifiableCollection(targetLibsWithoutPriv);
    }

    public boolean isLibSafeMode() {
        return libSafeMode;
    }

    @Override
    public boolean isIgnoreConcurrentModification() {
        return ignoreConcurrentModification;
    }

    public boolean isDebug() {
        return isDebug;
    }

    public boolean isIgnoreErrors() {
        return ignoreErrors;
    }

    @Override
    public boolean isIgnoreColumnOrder() {
        return ignoreColumnOrder;
    }

    @Override
    public boolean isIgnoreSequenceCache() {
        return ignoreSequenceCache;
    }

    @Override
    public boolean isNoAlterTableOnly() {
        return noAlterTableOnly;
    }

    @Override
    public boolean isIgnoreColumnStatistics() {
        return ignoreColumnStatistics;
    }

    @Override
    public boolean isReadAuthors() {
        // the CLI never consumes per-object author metadata (pg_dbo_timestamp)
        // and project export persists SQL only, so skip the dbots_event_data
        // join in every JDBC catalog query; the core default stays true for
        // IDE compatibility
        return false;
    }

    @Override
    public boolean isGenerateConstraintNotValid() {
        return generateConstraintNotValid;
    }

    @Override
    public String getInCharsetName() {
        return inCharsetName;
    }

    public String getOutCharsetName() {
        return outCharsetName;
    }

    @Override
    public boolean isDisableCheckFunctionBodies() {
        return disableCheckFunctionBodies;
    }

    @Override
    public boolean isEnableFunctionBodiesDependencies() {
        return enableFunctionBodiesDependencies;
    }

    @Override
    public String getTimeZone() {
        return timeZone;
    }

    @Override
    public boolean isIgnorePrivileges() {
        return ignorePrivileges;
    }

    @Override
    public boolean isKeepNewlines() {
        return keepNewlines;
    }

    @Override
    public Collection<DbObjType> getAllowedTypes() {
        return Collections.unmodifiableCollection(allowedTypes);
    }

    @Override
    public boolean isGenerateExists() {
        return generateExists;
    }

    @Override
    public boolean isGenerateExistDoBlock() {
        return generateExistDoBlock;
    }

    @Override
    public boolean isDropBeforeCreate() {
        return dropBeforeCreate;
    }

    @Override
    public boolean isCommentsToEnd() {
        return commentsToEnd;
    }

    public Collection<DbObjType> getGraphFilterTypes() {
        return Collections.unmodifiableCollection(graphFilterTypes);
    }

    public boolean isGraphInvertFilter() {
        return graphInvertFilter;
    }

    @Override
    public boolean isSelectedOnly() {
        return selectedOnly;
    }

    @Override
    public boolean isDataMovementMode() {
        return dataMovementMode;
    }

    @Override
    public boolean isPrintUsing() {
        return !usingTypeCastOff;
    }

    @Override
    public boolean isConcurrentlyMode() {
        return concurrentlyMode;
    }

    @Override
    public boolean isSimplifyView() {
        return simplifyView;
    }

    public int getGraphDepth() {
        return graphDepth;
    }

    public boolean isGraphReverse() {
        return graphReverse;
    }

    public boolean isProjUpdate() {
        return projUpdate;
    }

    public String getStructureFile() {
        return structureFile;
    }

    public Collection<String> getGraphNames() {
        return Collections.unmodifiableCollection(graphNames);
    }

    @Override
    public Collection<String> getPreFilePath() {
        return Collections.unmodifiableCollection(preFilePath);
    }

    @Override
    public Collection<String> getPostFilePath() {
        return Collections.unmodifiableCollection(postFilePath);
    }

    @Override
    public boolean isAutoFormatObjectCode() {
        return false;
    }

    @Override
    public FormatConfiguration getFormatConfiguration() {
        return null;
    }

    public IDatabaseProvider getProvider() {
        return provider;
    }

    @Override
    public String getClusterName() {
        return clusterName;
    }

    @Override
    public boolean isUseActualVersionSyntax() {
        return isUseActualVersionSyntax;
    }

    @Override
    public boolean isSimplifyNotNull() {
        return simplifyNotNull;
    }

    @Override
    public boolean isParallelLoad() {
        return parallelLoad;
    }

    @Override
    public int getJdbcFetchSize() {
        return jdbcFetchSize;
    }

    @Override
    public boolean isCollectObjectReferences() {
        return collectObjectReferences;
    }

    void setCollectObjectReferences(boolean collectObjectReferences) {
        this.collectObjectReferences = collectObjectReferences;
    }

    @Override
    public boolean isDisableAutoLoad() {
        return disableAutoLoad;
    }

    public String getAdditionalDepsPath() {
        return additionalDepsPath;
    }

    @Override
    public CliArgs shallowCopy() {
        var args = new CliArgs();
        args.addTransaction = addTransaction;
        args.allowedDangers = new ArrayList<>(allowedDangers);
        args.allowedTypes = new ArrayList<>(allowedTypes);
        args.clearLibCache = clearLibCache;
        args.commentsToEnd = commentsToEnd;
        args.concurrentlyMode = concurrentlyMode;
        args.dataMovementMode = dataMovementMode;
        args.dbType = dbType;
        args.disableCheckFunctionBodies = disableCheckFunctionBodies;
        args.dropBeforeCreate = dropBeforeCreate;
        args.enableFunctionBodiesDependencies = enableFunctionBodiesDependencies;
        args.generateConstraintNotValid = generateConstraintNotValid;
        args.generateExistDoBlock = generateExistDoBlock;
        args.generateExists = generateExists;
        args.graphDepth = graphDepth;
        args.graphFilterTypes = new ArrayList<>(graphFilterTypes);
        args.graphInvertFilter = graphInvertFilter;
        args.graphNames = new ArrayList<>(graphNames);
        args.graphReverse = graphReverse;
        args.ignoreColumnOrder = ignoreColumnOrder;
        args.ignoreColumnStatistics = ignoreColumnStatistics;
        args.ignoreConcurrentModification = ignoreConcurrentModification;
        args.ignoreErrors = ignoreErrors;
        args.ignoreLists = new ArrayList<>(ignoreLists);
        args.ignorePrivileges = ignorePrivileges;
        args.ignoreSchemaListPath = ignoreSchemaListPath;
        args.ignoreSequenceCache = ignoreSequenceCache;
        args.inCharsetName = inCharsetName;
        args.jdbcFetchSize = jdbcFetchSize;
        args.keepNewlines = keepNewlines;
        args.libSafeMode = libSafeMode;
        args.mode = mode;
        args.batchManifestPath = batchManifestPath;
        args.projectFileFilterPath = projectFileFilterPath;
        args.setProjectFileFilter(getProjectFileFilter());
        args.newSrc = newSrc;
        args.noAlterTableOnly = noAlterTableOnly;
        args.oldSrc = oldSrc;
        args.outCharsetName = outCharsetName;
        args.outputTarget = outputTarget;
        args.postFilePath = new ArrayList<>(postFilePath);
        args.preFilePath = new ArrayList<>(preFilePath);
        args.projUpdate = projUpdate;
        args.structureFile = structureFile;
        args.runOnDb = runOnDb;
        args.runOnTarget = runOnTarget;
        args.safeMode = safeMode;
        args.selectedOnly = selectedOnly;
        args.simplifyView = simplifyView;
        args.sourceLibs = new ArrayList<>(sourceLibs);
        args.sourceLibsWithoutPriv = new ArrayList<>(sourceLibsWithoutPriv);
        args.sourceLibXmls = new ArrayList<>(sourceLibXmls);
        args.stopNotAllowed = stopNotAllowed;
        args.targetLibs = new ArrayList<>(targetLibs);
        args.targetLibsWithoutPriv = new ArrayList<>(targetLibsWithoutPriv);
        args.targetLibXmls = new ArrayList<>(targetLibXmls);
        args.timeZone = timeZone;
        args.usingTypeCastOff = usingTypeCastOff;
        args.provider = provider;
        args.clusterName = clusterName;
        args.parallelLoad = parallelLoad;
        args.setPgRoutineBodyHashFirst(isPgRoutineBodyHashFirst());
        args.setPgRoutineBodySkipMatchedAnalysis(isPgRoutineBodySkipMatchedAnalysis());
        args.setPgRoutineBodyResidualBatchCount(getPgRoutineBodyResidualBatchCount());
        args.setPgRoutineBodyResidualBatchBytes(getPgRoutineBodyResidualBatchBytes());
        args.setPgCatalogCacheDir(getPgCatalogCacheDir());
        args.setPgCatalogCacheMaxMb(getPgCatalogCacheMaxMb());
        args.setPgCatalogCacheRows(isPgCatalogCacheRows());
        args.setPgParallelCatalogReaders(getPgParallelCatalogReaders());
        args.collectObjectReferences = collectObjectReferences;
        args.disableAutoLoad = disableAutoLoad;
        args.simplifyNotNull = simplifyNotNull;
        args.additionalDepsPath = additionalDepsPath;
        args.isUseActualVersionSyntax = isUseActualVersionSyntax;
        return args;
    }

    @Override
    public void setIgnorePrivileges(boolean ignorePrivileges) {
        this.ignorePrivileges = ignorePrivileges;
    }

    /**
     * Parses command line arguments or outputs instructions.
     *
     * @param args array of arguments
     * @return true if arguments were parsed and execution can continue,
     * otherwise false
     */
    public boolean parse(String[] args) throws CmdLineException {
        // reset parse buffers: null / false means "absent on this command line"
        projectFileFilterResolved = false;
        parallelLoadOption = false;
        noParallelLoadOption = false;
        pgRoutineBodyHashFirstOption = false;
        pgRoutineBodyNoHashFirstOption = false;
        pgRoutineBodyNoSkipMatchedAnalysisOption = !isPgRoutineBodySkipMatchedAnalysis();
        pgRoutineBodyResidualBatchCountOption = null;
        pgRoutineBodyResidualBatchBytesOption = null;
        jdbcFetchSizeOption = null;
        pgCatalogCacheDirOption = null;
        pgCatalogCacheMaxMbOption = getPgCatalogCacheMaxMb();
        pgCatalogCacheRowsOption = isPgCatalogCacheRows();
        pgParallelCatalogReadersOption = null;

        if (args.length != 0) {
            CmdLineParser parser = new CmdLineParser(this, ParserProperties.defaults()
                    .withOptionValueDelimiter(OPTION_VALUE_DELIMITER));
            checkSpaceJoinedOptions(parser, args);
            parser.parseArgument(args);
        } else {
            // show help instead of failing for 0 args
            zhelp = true;
        }
        if (zhelp) {
            printUsage();
            return false;
        }
        if (zversion) {
            printVersion();
            return false;
        }
        if (zlistCharsets) {
            listCharsets();
            return false;
        }
        if (jdbcFetchSizeOption != null && jdbcFetchSizeOption < 0) {
            badArgs(Messages.CliArgs_error_jdbc_fetch_size_negative);
        }
        if (pgRoutineBodyResidualBatchCountOption != null
                && pgRoutineBodyResidualBatchCountOption <= 0) {
            badArgs(Messages.CliArgs_error_pg_routine_body_residual_batch_count_non_positive);
        }
        if (pgRoutineBodyResidualBatchBytesOption != null
                && pgRoutineBodyResidualBatchBytesOption <= 0) {
            badArgs(Messages.CliArgs_error_pg_routine_body_residual_batch_bytes_non_positive);
        }
        if (pgCatalogCacheMaxMbOption <= 0) {
            badArgs(Messages.CliArgs_error_pg_catalog_cache_max_mb_non_positive);
        }
        if (pgParallelCatalogReadersOption != null && pgParallelCatalogReadersOption < 0) {
            badArgs(Messages.CliArgs_error_pg_parallel_catalog_readers_negative);
        }

        checkRollbackSwitchConflicts();

        // Resolve effective values from CLI defaults and explicit overrides.
        parallelLoad = !noParallelLoadOption;
        if (jdbcFetchSizeOption != null) {
            jdbcFetchSize = jdbcFetchSizeOption;
        }
        setPgRoutineBodyHashFirst(resolvePgRoutineBodyHashFirst());
        setPgRoutineBodySkipMatchedAnalysis(!pgRoutineBodyNoSkipMatchedAnalysisOption);
        if (pgRoutineBodyResidualBatchCountOption != null) {
            setPgRoutineBodyResidualBatchCount(pgRoutineBodyResidualBatchCountOption);
        }
        if (pgRoutineBodyResidualBatchBytesOption != null) {
            setPgRoutineBodyResidualBatchBytes(pgRoutineBodyResidualBatchBytesOption);
        }
        if (pgCatalogCacheDirOption != null) {
            setPgCatalogCacheDir(pgCatalogCacheDirOption);
        }
        setPgCatalogCacheMaxMb(pgCatalogCacheMaxMbOption);
        setPgCatalogCacheRows(pgCatalogCacheRowsOption);
        setPgParallelCatalogReaders(resolvePgParallelCatalogReaders());

        if (clearLibCache && CliMode.DIFF == mode && (oldSrc == null || newSrc == null)) {
            return true;
        }

        checkModeParams();
        checkDbTypesParam();
        checkParams();

        if (projectFileFilterPath != null) {
            ProjectFileFilter prepared = null;
            if (projectFileFilterPath.equals(preparedProjectFileFilterPath)) {
                prepared = preparedProjectFileFilter;
            }
            preparedProjectFileFilterPath = null;
            preparedProjectFileFilter = null;

            if (prepared != null) {
                setProjectFileFilter(prepared);
            } else {
                try {
                    setProjectFileFilter(ProjectFileFilter.parse(Path.of(projectFileFilterPath)));
                } catch (IOException | InvalidPathException ex) {
                    badArgs(Messages.CliArgs_error_project_file_filter
                            .formatted(projectFileFilterPath, ex.getMessage()));
                }
            }
            projectFileFilterResolved = true;
        }

        if (CliMode.DIFF == mode) {
            if (oldSrc == null || newSrc == null) {
                badArgs(Messages.CliArgs_error_source_dest);
            }
        } else if (CliMode.PARSE == mode && projUpdate) {
            oldSrc = outputTarget;
        }

        provider = switch (dbType) {
            case "PG" -> new PgDatabaseProvider();
            case "MS" -> new MsDatabaseProvider();
            case "CH" -> new ChDatabaseProvider();
            default -> throw new IllegalArgumentException(Messages.CliArgs_db_type);
        };

        return true;
    }

    /**
     * Rejects combinations of an override with explicit options that require
     * the behavior it disables. Implicit defaults cannot create a conflict.
     */
    private void checkRollbackSwitchConflicts() throws CmdLineException {
        badArgConflict(parallelLoadOption && noParallelLoadOption,
                "--parallel-load", "--no-parallel-load"); //$NON-NLS-1$ //$NON-NLS-2$
        badArgConflict(pgRoutineBodyHashFirstOption && pgRoutineBodyNoHashFirstOption,
                "--pg-routine-body-hash-first", "--pg-routine-body-no-hash-first"); //$NON-NLS-1$ //$NON-NLS-2$
        // Hash-first exchange requires paired parallel comparison loading.
        badArgConflict(pgRoutineBodyHashFirstOption && noParallelLoadOption,
                "--pg-routine-body-hash-first", "--no-parallel-load"); //$NON-NLS-1$ //$NON-NLS-2$
        // residual batching and the catalog cache act only on the hash-first
        // path; both rollback switches disable that path
        badArgConflict(pgRoutineBodyResidualBatchCountOption != null && pgRoutineBodyNoHashFirstOption,
                "--pg-routine-body-residual-batch-count", "--pg-routine-body-no-hash-first"); //$NON-NLS-1$ //$NON-NLS-2$
        badArgConflict(pgRoutineBodyResidualBatchBytesOption != null && pgRoutineBodyNoHashFirstOption,
                "--pg-routine-body-residual-batch-bytes", "--pg-routine-body-no-hash-first"); //$NON-NLS-1$ //$NON-NLS-2$
        badArgConflict(pgCatalogCacheDirOption != null && pgRoutineBodyNoHashFirstOption,
                "--pg-catalog-cache-dir", "--pg-routine-body-no-hash-first"); //$NON-NLS-1$ //$NON-NLS-2$
        badArgConflict(pgRoutineBodyResidualBatchCountOption != null && noParallelLoadOption,
                "--pg-routine-body-residual-batch-count", "--no-parallel-load"); //$NON-NLS-1$ //$NON-NLS-2$
        badArgConflict(pgRoutineBodyResidualBatchBytesOption != null && noParallelLoadOption,
                "--pg-routine-body-residual-batch-bytes", "--no-parallel-load"); //$NON-NLS-1$ //$NON-NLS-2$
        badArgConflict(pgCatalogCacheDirOption != null && noParallelLoadOption,
                "--pg-catalog-cache-dir", "--no-parallel-load"); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * Enables hash-first body exchange for parallel PostgreSQL DIFF
     * comparisons. Other modes and dialects keep it disabled, while an
     * explicit positive option still reaches mode and database validation.
     */
    private boolean resolvePgRoutineBodyHashFirst() {
        if (pgRoutineBodyNoHashFirstOption) {
            return false;
        }
        if (pgRoutineBodyHashFirstOption) {
            return true;
        }
        return parallelLoad && CliMode.DIFF == mode && PG.equals(dbType);
    }

    /**
     * Applies the CLI reader-lane default to PostgreSQL only. The setting is
     * read by the PostgreSQL JDBC loader alone, so other dialects keep the
     * core default and the profile does not follow them, exactly as
     * hash-first does not. Unlike hash-first this is not restricted to DIFF:
     * the lanes speed up any load from a live PostgreSQL database, including
     * PARSE and GRAPH. An explicit option still reaches database validation,
     * which rejects it outside PostgreSQL.
     */
    private int resolvePgParallelCatalogReaders() {
        if (pgParallelCatalogReadersOption != null) {
            return pgParallelCatalogReadersOption;
        }
        return PG.equals(dbType) ? DEFAULT_CLI_PG_PARALLEL_CATALOG_READERS
                : ISettings.DEFAULT_PG_PARALLEL_CATALOG_READERS;
    }

    private void checkParams() throws CmdLineException {
        if (newSrc == null && CliMode.BATCH != mode) {
            badArgs(Messages.CliArgs_error_source_null);
        }

        if (CliMode.BATCH == mode && batchManifestPath == null) {
            badArgs(Messages.CliArgs_error_batch_manifest_required);
        }

        if (CliMode.DIFF == mode) {
            if (PG.equals(dbType) && addTransaction && concurrentlyMode) {
                badArgs(Messages.CliArgs_error_concurrently_mode_wrong_option);
            }
            if (runOnTarget && !oldSrc.startsWith(URL_START_JDBC)) {
                badArgs(Messages.CliArgs_error_target_non_db);
            }
            if (runOnDb != null && !runOnDb.startsWith(URL_START_JDBC)) {
                badArgs(Messages.CliArgs_error_run_on_non_jdbc);
            }
        } else if (CliMode.PARSE == mode) {
            if (outputTarget == null) {
                badArgs(Messages.CliArgs_error_argument_null.formatted("\"-o (--output)\"")); //$NON-NLS-1$
            }
            if (projUpdate && structureFile != null) {
                badArgs(Messages.CliArgs_error_structure_file_with_update);
            }
        }
    }

    private void checkModeParams() throws CmdLineException {
        // argument can be used only with mode
        badArgWithCorrectModes(batchManifestPath != null, "--batch-manifest", CliMode.BATCH); //$NON-NLS-1$
        badArgWithCorrectModes(projectFileFilterPath != null, "--project-file-filter", //$NON-NLS-1$
                CliMode.DIFF, CliMode.PARSE, CliMode.GRAPH);
        badArgWithCorrectModes(newSrc != null, "--source (-s)", //$NON-NLS-1$
                CliMode.DIFF, CliMode.PARSE, CliMode.GRAPH);
        badArgWithCorrectModes(oldSrc != null, "--target (-t)", CliMode.DIFF); //$NON-NLS-1$
        badArgWithCorrectModes(addTransaction, "--add-transaction (-X)", CliMode.DIFF); //$NON-NLS-1$
        badArgWithCorrectModes(runOnDb != null, "--run-on (-R)", CliMode.DIFF); //$NON-NLS-1$
        badArgWithCorrectModes(simplifyView, "--simplify-views", CliMode.DIFF, CliMode.PARSE); //$NON-NLS-1$
        badArgWithCorrectModes(timeZone != null, "--time-zone (-Z)", CliMode.DIFF, CliMode.PARSE); //$NON-NLS-1$
        badArgWithCorrectModes(!allowedTypes.isEmpty(), "--allowed-object (-O)", CliMode.DIFF); //$NON-NLS-1$
        badArgWithCorrectModes(!ignoreLists.isEmpty(), "--ignore-list-I (-I)", CliMode.DIFF, CliMode.PARSE); //$NON-NLS-1$
        badArgWithCorrectModes(selectedOnly, "--selected-only", CliMode.DIFF); //$NON-NLS-1$
        badArgWithCorrectModes(runOnTarget, "--run-on-target (-r)", CliMode.DIFF); //$NON-NLS-1$
        badArgWithCorrectModes(disableCheckFunctionBodies, "--no-check-function-bodies (-F)", CliMode.DIFF); //$NON-NLS-1$
        badArgWithCorrectModes(!preFilePath.isEmpty(), "--pre-script", CliMode.DIFF); //$NON-NLS-1$
        badArgWithCorrectModes(!postFilePath.isEmpty(), "--post-script", CliMode.DIFF); //$NON-NLS-1$
        badArgWithCorrectModes(ignoreColumnOrder, "--ignore-column-order", CliMode.DIFF); //$NON-NLS-1$
        badArgWithCorrectModes(ignoreSequenceCache, "--ignore-sequence-cache", CliMode.DIFF); //$NON-NLS-1$
        badArgWithCorrectModes(noAlterTableOnly, "--no-alter-table-only", CliMode.DIFF); //$NON-NLS-1$
        badArgWithCorrectModes(ignoreColumnStatistics, "--ignore-column-statistics", CliMode.DIFF); //$NON-NLS-1$
        badArgWithCorrectModes(generateConstraintNotValid, "--generate-constraint-not-valid (-v)", CliMode.DIFF); //$NON-NLS-1$
        badArgWithCorrectModes(usingTypeCastOff, "--using-off", CliMode.DIFF); //$NON-NLS-1$
        badArgWithCorrectModes(dataMovementMode, "--migrate-data", CliMode.DIFF); //$NON-NLS-1$
        badArgWithCorrectModes(concurrentlyMode, "--concurrently-mode (-C)", CliMode.DIFF); //$NON-NLS-1$
        badArgWithCorrectModes(generateExists, "--generate-exist", CliMode.DIFF); //$NON-NLS-1$
        badArgWithCorrectModes(generateExistDoBlock, "--generate-exist-do-block (-do)", CliMode.DIFF); //$NON-NLS-1$
        badArgWithCorrectModes(dropBeforeCreate, "--drop-before-create", CliMode.DIFF); //$NON-NLS-1$
        badArgWithCorrectModes(commentsToEnd, "--comments-to-end", CliMode.DIFF); //$NON-NLS-1$
        badArgWithCorrectModes(safeMode, "--safe-mode (-S)", CliMode.DIFF); //$NON-NLS-1$
        badArgWithCorrectModes(!allowedDangers.isEmpty(), "--allow-danger-ddl (-D)", CliMode.DIFF); //$NON-NLS-1$
        badArgWithCorrectModes(stopNotAllowed, "--stop-not-allowed", CliMode.DIFF); //$NON-NLS-1$
        badArgWithCorrectModes(!sourceLibXmls.isEmpty(), "--tgt-lib-xml", CliMode.DIFF); //$NON-NLS-1$
        badArgWithCorrectModes(!sourceLibs.isEmpty(), "--tgt-lib", CliMode.DIFF); //$NON-NLS-1$
        badArgWithCorrectModes(!sourceLibsWithoutPriv.isEmpty(), "--tgt-lib-no-priv", CliMode.DIFF); //$NON-NLS-1$
        badArgWithCorrectModes(projUpdate, "--update-project", CliMode.PARSE); //$NON-NLS-1$
        badArgWithCorrectModes(structureFile != null, "--structure-file", CliMode.PARSE); //$NON-NLS-1$
        badArgWithCorrectModes(!graphNames.isEmpty(), "--graph-name", CliMode.GRAPH); //$NON-NLS-1$
        badArgWithCorrectModes(DEFAULT_DEPTH != graphDepth, "--graph-depth", CliMode.GRAPH); //$NON-NLS-1$
        badArgWithCorrectModes(!graphFilterTypes.isEmpty(), "--graph-filter-object", CliMode.GRAPH); //$NON-NLS-1$
        badArgWithCorrectModes(additionalDepsPath != null, "--additional-dependencies", CliMode.DIFF); //$NON-NLS-1$
        badArgWithCorrectModes(isUseActualVersionSyntax, "--use-actual-syntax", CliMode.DIFF); //$NON-NLS-1$
        badArgWithCorrectModes(simplifyNotNull, "--simplify-not-null", CliMode.DIFF, CliMode.PARSE); //$NON-NLS-1$
        badArgWithCorrectModes(isPgRoutineBodyHashFirst(), "--pg-routine-body-hash-first", CliMode.DIFF); //$NON-NLS-1$
        badArgWithCorrectModes(pgRoutineBodyNoHashFirstOption,
                "--pg-routine-body-no-hash-first", CliMode.DIFF); //$NON-NLS-1$
        badArgWithCorrectModes(pgRoutineBodyNoSkipMatchedAnalysisOption,
                "--pg-routine-body-no-skip-matched-analysis", CliMode.DIFF); //$NON-NLS-1$
        badArgWithCorrectModes(pgRoutineBodyResidualBatchCountOption != null,
                "--pg-routine-body-residual-batch-count", CliMode.DIFF); //$NON-NLS-1$
        badArgWithCorrectModes(pgRoutineBodyResidualBatchBytesOption != null,
                "--pg-routine-body-residual-batch-bytes", CliMode.DIFF); //$NON-NLS-1$
        badArgWithCorrectModes(pgCatalogCacheDirOption != null,
                "--pg-catalog-cache-dir", CliMode.DIFF); //$NON-NLS-1$
    }

    private void checkDbTypesParam() throws CmdLineException {
        badArgWithWrongDbType(simplifyView, "--simplify-views", MS, CH); //$NON-NLS-1$
        badArgWithWrongDbType(timeZone != null, "--time-zone (-Z)", MS, CH); //$NON-NLS-1$
        badArgWithWrongDbType(generateExistDoBlock, "--generate-exist-do-block (-do)", MS, CH); //$NON-NLS-1$
        badArgWithWrongDbType(concurrentlyMode, "--concurrently-mode (-C)", CH); //$NON-NLS-1$
        badArgWithWrongDbType(commentsToEnd, "--comments-to-end", CH); //$NON-NLS-1$
        badArgWithWrongDbType(null != clusterName, "--cluster-name", PG, MS); //$NON-NLS-1$
        badArgWithWrongDbType(simplifyNotNull, "--simplify-not-null", MS, CH); //$NON-NLS-1$
        badArgWithWrongDbType(isPgRoutineBodyHashFirst(), "--pg-routine-body-hash-first", MS, CH); //$NON-NLS-1$
        badArgWithWrongDbType(pgRoutineBodyNoHashFirstOption,
                "--pg-routine-body-no-hash-first", MS, CH); //$NON-NLS-1$
        badArgWithWrongDbType(pgRoutineBodyNoSkipMatchedAnalysisOption,
                "--pg-routine-body-no-skip-matched-analysis", MS, CH); //$NON-NLS-1$
        badArgWithWrongDbType(pgRoutineBodyResidualBatchCountOption != null,
                "--pg-routine-body-residual-batch-count", MS, CH); //$NON-NLS-1$
        badArgWithWrongDbType(pgRoutineBodyResidualBatchBytesOption != null,
                "--pg-routine-body-residual-batch-bytes", MS, CH); //$NON-NLS-1$
        badArgWithWrongDbType(pgCatalogCacheDirOption != null,
                "--pg-catalog-cache-dir", MS, CH); //$NON-NLS-1$
        badArgWithWrongDbType(pgParallelCatalogReadersOption != null,
                "--pg-parallel-catalog-readers", MS, CH); //$NON-NLS-1$
    }

    private void badArgWithCorrectModes(boolean condition, String param, CliMode... modes) throws CmdLineException {
        if (condition && !containsInArray(mode, modes)) {
            badArgs(Messages.CliArgs_error_wrong_mode.formatted(param, mode));
        }
    }

    private void badArgWithWrongDbType(boolean condition, String arg, String... wrongDbNames)
            throws CmdLineException {
        if (condition && containsInArray(dbType, wrongDbNames)) {
            badArgs(Messages.CliArgs_error_wrong_db_type.formatted(arg, dbType));
        }
    }

    private void badArgConflict(boolean condition, String arg, String conflictingArg)
            throws CmdLineException {
        if (condition) {
            badArgs(Messages.CliArgs_error_conflicting_options.formatted(arg, conflictingArg));
        }
    }

    private void badArgs(String message) throws CmdLineException {
        throw new CmdLineException(null, message, null);
    }

    /**
     * Rejects a single argument token that joins a known option name and its
     * value with whitespace, such as {@code "--parallel-load true"}. Only an
     * unquoted wrapper variable produces such a token, and no parser reading
     * can recover the intent, so it is reported instead of being reinterpreted.
     *
     * @param parser parser holding the declared options of this bean
     * @param args   raw argument array
     */
    private void checkSpaceJoinedOptions(CmdLineParser parser, String[] args) throws CmdLineException {
        for (String arg : args) {
            if (arg == null || arg.isEmpty() || arg.charAt(0) != '-') {
                continue;
            }
            int space = indexOfWhitespace(arg);
            if (space > 0 && isKnownOption(parser, arg.substring(0, space))) {
                badArgs(Messages.CliArgs_error_space_joined_option
                        .formatted(arg, arg.substring(0, space)));
            }
        }
    }

    private int indexOfWhitespace(String arg) {
        for (int i = 0; i < arg.length(); i++) {
            if (Character.isWhitespace(arg.charAt(i))) {
                return i;
            }
        }

        return -1;
    }

    private boolean isKnownOption(CmdLineParser parser, String name) {
        for (OptionHandler<?> handler : parser.getOptions()) {
            if (handler.option instanceof NamedOptionDef named
                    && (name.equals(named.name()) || containsInArray(name, named.aliases()))) {
                return true;
            }
        }

        return false;
    }

    private void printUsage() {
        // fix defaults for options like help and other 0-arg booleans
        OptionHandlerRegistry.getRegistry().registerHandler(Boolean.class, BooleanNoDefOptionHandler.class);
        OptionHandlerRegistry.getRegistry().registerHandler(boolean.class, BooleanNoDefOptionHandler.class);

        ParserProperties prop = ParserProperties.defaults().withUsageWidth(80);

        ByteArrayOutputStream buf = new ByteArrayOutputStream();

        // new args instance to get correct defaults
        new CmdLineParser(new CliArgs(), prop).printUsage(new OutputStreamWriter(buf, StandardCharsets.UTF_8),
                new CliArgsLocalizationsBundle());

        writeToConsole(Messages.UsageHelp.replace("${tab}", "\t").formatted( //$NON-NLS-1$ //$NON-NLS-2$
                buf.toString(StandardCharsets.UTF_8),
                DangerStatementOptionHandler.getMetaVariable() + '\n' + DbObjTypeOptionHandler.getMetaVariable()));
    }

    private void writeToConsole(String message) {
        System.out.println(message);
    }

    private void printVersion() {
        writeToConsole(Utils.getVersion());
    }

    private void listCharsets() {
        Charset.availableCharsets().keySet().forEach(this::writeToConsole);
    }

    private <T> boolean containsInArray(T element, T[] elements) {
        for (T t : elements) {
            if (t.equals(element)) {
                return true;
            }
        }

        return false;
    }
}
