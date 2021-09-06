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

import com.google.gson.Gson;
import java.io.BufferedReader;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.sonar.api.batch.fs.internal.DefaultInputFile;
import org.sonar.api.batch.fs.internal.TestInputFileBuilder;
import org.sonar.api.batch.sensor.Sensor;
import org.sonar.api.batch.sensor.SensorContext;
import org.sonar.api.batch.sensor.SensorDescriptor;
import org.sonar.api.batch.sensor.internal.SensorContextTester;
import org.sonar.api.config.internal.MapSettings;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MonitoringTest {

  @TempDir
  Path baseDir;

  @TempDir
  Path monitoringPath;

  Gson gson = new Gson();

  @Test
  void test_sensor() throws Exception {
    Monitoring monitoring = new Monitoring();
    TestSensor sensor = new TestSensor();
    SensorContextTester sensorContextTester = SensorContextTester.create(baseDir);
    sensorContextTester.setSettings(getSettings());
    monitoring.startSensor(sensorContextTester, sensor);
    sleep();
    monitoring.stopSensor();
    monitoring.stop();
    Path metricsPath = monitoringPath.resolve("metrics.json");
    assertThat(metricsPath).exists();
    try (BufferedReader br = Files.newBufferedReader(metricsPath)) {
      Monitoring.SensorMetric sensorMetric = gson.fromJson(br, Monitoring.SensorMetric.class);
      assertThat(sensorMetric.component).isEqualTo(TestSensor.class.getCanonicalName());
      assertThat(sensorMetric.duration).isGreaterThan(100);
    }
  }

  private MapSettings getSettings() {
    MapSettings settings = new MapSettings();
    settings.setProperty("sonar.javascript.monitoring", true);
    settings.setProperty("sonar.javascript.monitoring.path", monitoringPath.toString());
    return settings;
  }

  @Test
  void test_not_enabled() throws Exception {
    Monitoring monitoring = new Monitoring();
    TestSensor sensor = new TestSensor();
    SensorContextTester sensorContextTester = SensorContextTester.create(baseDir);
    monitoring.startSensor(sensorContextTester, sensor);
    DefaultInputFile inputFile = TestInputFileBuilder.create("module", "path").build();
    monitoring.startFile(inputFile);
    monitoring.stopFile(inputFile, 0, new EslintBridgeServer.Perf());
    monitoring.stopSensor();
    monitoring.stop();
    Path metricsPath = monitoringPath.resolve("metrics.json");
    assertThat(metricsPath).doesNotExist();
  }

  @Test
  void test_file() throws Exception {
    Monitoring monitoring = new Monitoring();
    TestSensor sensor = new TestSensor();
    SensorContextTester sensorContextTester = SensorContextTester.create(baseDir);
    sensorContextTester.setSettings(getSettings());
    monitoring.startSensor(sensorContextTester, sensor);
    DefaultInputFile inputFile = TestInputFileBuilder.create("module", "path").build();
    monitoring.startFile(inputFile);
    EslintBridgeServer.Perf perf = new EslintBridgeServer.Perf();
    perf.analysisTime = 2;
    perf.parseTime = 3;
    monitoring.stopFile(inputFile, 4, perf);
    monitoring.stopSensor();
    monitoring.stop();
    Path metricsPath = monitoringPath.resolve("metrics.json");
    assertThat(metricsPath).exists();
    try (BufferedReader br = Files.newBufferedReader(metricsPath)) {
      Monitoring.FileMetric fileMetric = gson.fromJson(br.readLine(), Monitoring.FileMetric.class);
      assertThat(fileMetric.component).isEqualTo("path");
      assertThat(fileMetric.analysisTime).isEqualTo(2);
      assertThat(fileMetric.parseTime).isEqualTo(3);
      assertThat(fileMetric.ncloc).isEqualTo(4);
      assertThat(fileMetric.ordinal).isZero();
    }
  }

  @Test
  void test_file_mismatch() throws Exception {
    Monitoring monitoring = new Monitoring();
    TestSensor sensor = new TestSensor();
    SensorContextTester sensorContextTester = SensorContextTester.create(baseDir);
    sensorContextTester.setSettings(getSettings());
    monitoring.startSensor(sensorContextTester, sensor);
    DefaultInputFile file1 = TestInputFileBuilder.create("module", "file1").build();
    DefaultInputFile file2 = TestInputFileBuilder.create("module", "file2").build();
    monitoring.startFile(file1);
    monitoring.startFile(file2);
    EslintBridgeServer.Perf perf = new EslintBridgeServer.Perf();
    assertThatThrownBy(() -> monitoring.stopFile(file1, 0, perf))
      .isInstanceOf(IllegalStateException.class);
  }


  static class TestSensor implements Sensor {

    @Override
    public void describe(SensorDescriptor descriptor) {

    }

    @Override
    public void execute(SensorContext context) {

    }
  }

  static void sleep() {
    try {
      Thread.sleep(100);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

}
