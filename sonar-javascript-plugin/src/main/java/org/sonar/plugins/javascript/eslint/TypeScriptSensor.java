/*
 * SonarQube JavaScript Plugin
 * Copyright (C) 2011-2021 SonarSource SA
 * mailto:info AT sonarsource DOT com
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 3 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301, USA.
 */
package org.sonar.plugins.javascript.eslint;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;
import org.sonar.api.batch.fs.FilePredicate;
import org.sonar.api.batch.fs.FileSystem;
import org.sonar.api.batch.fs.InputFile;
import org.sonar.api.batch.fs.InputFile.Type;
import org.sonar.api.batch.sensor.SensorDescriptor;
import org.sonar.api.issue.NoSonarFilter;
import org.sonar.api.measures.FileLinesContextFactory;
import org.sonar.api.utils.TempFolder;
import org.sonar.api.utils.log.Logger;
import org.sonar.api.utils.log.Loggers;
import org.sonar.plugins.javascript.CancellationException;
import org.sonar.plugins.javascript.JavaScriptFilePredicate;
import org.sonar.plugins.javascript.JavaScriptLanguage;
import org.sonar.plugins.javascript.TypeScriptChecks;
import org.sonar.plugins.javascript.TypeScriptLanguage;
import org.sonar.plugins.javascript.eslint.EslintBridgeServer.AnalysisRequest;
import org.sonar.plugins.javascript.eslint.EslintBridgeServer.AnalysisResponse;
import org.sonarsource.analyzer.commons.ProgressReport;

import static java.util.Collections.singletonList;

public class TypeScriptSensor extends AbstractEslintSensor {

  private static final Logger LOG = Loggers.get(TypeScriptSensor.class);
  private final TempFolder tempFolder;

  public TypeScriptSensor(TypeScriptChecks typeScriptChecks, NoSonarFilter noSonarFilter,
                          FileLinesContextFactory fileLinesContextFactory,
                          EslintBridgeServer eslintBridgeServer,
                          AnalysisWarningsWrapper analysisWarnings,
                          TempFolder tempFolder, Monitoring monitoring) {
    super(typeScriptChecks,
      noSonarFilter,
      fileLinesContextFactory,
      eslintBridgeServer,
      analysisWarnings,
      monitoring
    );
    this.tempFolder = tempFolder;
  }

  @Override
  public void describe(SensorDescriptor descriptor) {
    descriptor
      // JavaScriptLanguage.KEY is required for Vue single file components, bc .vue is considered as JS language
      .onlyOnLanguages(JavaScriptLanguage.KEY, TypeScriptLanguage.KEY)
      .name("TypeScript analysis")
      .onlyOnFileType(Type.MAIN);
  }

  @Override
  protected List<InputFile> getInputFiles() {
    FileSystem fileSystem = context.fileSystem();
    FilePredicate mainFilePredicate = JavaScriptFilePredicate.getTypeScriptPredicate(fileSystem);
    return StreamSupport.stream(fileSystem.inputFiles(mainFilePredicate).spliterator(), false)
      .collect(Collectors.toList());
  }

  @Override
  void analyzeFiles(List<InputFile> inputFiles) throws IOException {
    boolean success = false;
    ProgressReport progressReport = new ProgressReport("Progress of TypeScript analysis", TimeUnit.SECONDS.toMillis(10));
    eslintBridgeServer.initLinter(rules, environments, globals);
    List<String> tsConfigs = new TsConfigProvider(tempFolder).tsconfigs(context);
    if (tsConfigs.isEmpty()) {
      // This can happen in SonarLint context where we are not able to create temporary file for generated tsconfig.json
      // See also https://github.com/SonarSource/SonarJS/issues/2506
      LOG.warn("No tsconfig.json file found, analysis will be skipped.");
      return;
    }
    Map<TsConfigFile, List<InputFile>> filesByTsConfig = TsConfigFile.inputFilesByTsConfig(loadTsConfigs(tsConfigs), inputFiles);
    try {
      progressReport.start(filesByTsConfig.values().stream().flatMap(List::stream).map(InputFile::toString).collect(Collectors.toList()));
      for (Map.Entry<TsConfigFile, List<InputFile>> entry : filesByTsConfig.entrySet()) {
        TsConfigFile tsConfigFile = entry.getKey();
        List<InputFile> files = entry.getValue();
        if (TsConfigFile.UNMATCHED_CONFIG.equals(tsConfigFile)) {
          LOG.info("Skipping {} files with no tsconfig.json", files.size());
          LOG.debug("Skipped files: " + files.stream().map(InputFile::toString).collect(Collectors.joining("\n")));
          continue;
        }
        LOG.info("Analyzing {} files using tsconfig: {}", files.size(), tsConfigFile);
        analyzeFilesWithTsConfig(files, tsConfigFile, progressReport);
        eslintBridgeServer.newTsConfig();
      }
      success = true;
    } finally {
      if (success) {
        progressReport.stop();
      } else {
        progressReport.cancel();
      }
    }
  }

  private void analyzeFilesWithTsConfig(List<InputFile> files, TsConfigFile tsConfigFile, ProgressReport progressReport) throws IOException {
    for (InputFile inputFile : files) {
      if (context.isCancelled()) {
        throw new CancellationException("Analysis interrupted because the SensorContext is in cancelled state");
      }
      if (eslintBridgeServer.isAlive()) {
        monitoring.startFile(inputFile);
        analyze(inputFile, tsConfigFile);
        progressReport.nextFile();
      } else {
        throw new IllegalStateException("eslint-bridge server is not answering");
      }
    }
  }

  private void analyze(InputFile file, TsConfigFile tsConfigFile) throws IOException {
    try {
      String fileContent = shouldSendFileContent(file) ? file.contents() : null;
      AnalysisRequest request = new AnalysisRequest(file.absolutePath(), file.type().toString(), fileContent, ignoreHeaderComments(), singletonList(tsConfigFile.filename));
      AnalysisResponse response = eslintBridgeServer.analyzeTypeScript(request);
      processResponse(file, response);
    } catch (IOException e) {
      LOG.error("Failed to get response while analyzing " + file, e);
      throw e;
    }
  }

  private List<TsConfigFile> loadTsConfigs(List<String> tsConfigPaths) {
    List<TsConfigFile> tsConfigFiles = new ArrayList<>();
    Deque<String> workList = new ArrayDeque<>(tsConfigPaths);
    Set<String> processed = new HashSet<>();
    while (!workList.isEmpty()) {
      String path = workList.pop();
      if (processed.add(path)) {
        TsConfigFile tsConfigFile = eslintBridgeServer.loadTsConfig(path);
        tsConfigFiles.add(tsConfigFile);
        if (!tsConfigFile.projectReferences.isEmpty()) {
          LOG.debug("Adding referenced project's tsconfigs {}", tsConfigFile.projectReferences);
        }
        workList.addAll(tsConfigFile.projectReferences);
      }
    }
    return tsConfigFiles;
  }
}
