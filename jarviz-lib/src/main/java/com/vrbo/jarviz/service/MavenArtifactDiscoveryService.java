/*
 * Copyright 2020 Expedia, Inc.
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
 */

package com.vrbo.jarviz.service;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.List;
import java.util.concurrent.TimeUnit;

import javax.inject.Inject;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.collect.ImmutableList;
import com.vrbo.jarviz.config.JarvizConfig;
import com.vrbo.jarviz.model.Artifact;

import static com.vrbo.jarviz.util.FileReadWriteUtils.toFullPath;

public class MavenArtifactDiscoveryService implements ArtifactDiscoveryService {

    private final Logger log = LoggerFactory.getLogger(MavenArtifactDiscoveryService.class);

    private final String localRepoPath;

    private final boolean continueOnMavenError;

    private final long mavenTimeOutSeconds;

    @Inject
    public MavenArtifactDiscoveryService(final JarvizConfig config) {
        this.localRepoPath = config.getArtifactDirectory();
        this.continueOnMavenError = config.getContinueOnMavenError();
        this.mavenTimeOutSeconds = config.getMavenTimeOutSeconds();
    }

    @Override
    public File discoverArtifact(final Artifact artifact) throws ArtifactNotFoundException {
        // Try to find the file directly in the artifact directory first
        final String artifactFileName = artifact.toFileName();
        final String fullPath = toFullPath(localRepoPath, artifactFileName);
        final File file = new File(fullPath);

        if (file.exists()) {
            log.info("Found artifact file locally at {}", fullPath);
            return file;
        }

        // Check if a file with similar name exists (without version or with different
        // version format)
        File directory = new File(localRepoPath);
        File[] matchingFiles = directory.listFiles(f -> {
            String name = f.getName().toLowerCase();
            // Match files that start with the artifactId and have the right extension
            return name.startsWith(artifact.getArtifactId().toLowerCase()) &&
                    name.endsWith("." + artifact.getPackaging().toLowerCase());
        });

        if (matchingFiles != null && matchingFiles.length > 0) {
            log.info("Found matching artifact file locally: {}", matchingFiles[0].getName());
            return matchingFiles[0];
        }

        // If not found locally, try Maven as last resort
        log.info("Artifact not found locally, attempting Maven download: {}", artifactFileName);
        runMavenCopy(artifact);

        // Check again after Maven attempt
        if (file.exists()) {
            return file;
        }

        throw new ArtifactNotFoundException(
                String.format("Unable to find artifact %s locally or via Maven", artifactFileName));
    }

    private Process runMavenCopy(final Artifact artifact) throws ArtifactNotFoundException {
        try {
            final String artifactMavenId = artifact.toMavenId();
            log.info("Maven: fetching artifact {}", artifactMavenId);

            final String stripVersionSwitch = artifact.isVersionLatestOrRelease() ? "true" : "false";
            final String mvnCommand = String.format(
                    "mvn dependency:copy -DoutputDirectory=%s -Dartifact=%s -Dmdep.stripVersion=%s",
                    localRepoPath, artifactMavenId, stripVersionSwitch);
            final Process process = Runtime.getRuntime().exec(mvnCommand);
            boolean failed = false;

            if (process.waitFor(mavenTimeOutSeconds, TimeUnit.SECONDS)) {
                drainInputStream(process.getInputStream()).forEach(s -> log.info("{}", s));
                if (process.exitValue() != 0) {
                    failed = true;
                    // In case, there is an error, let's log and throw exception
                    drainInputStream(process.getErrorStream()).forEach(s -> log.error("{}", s));
                    log.error("Maven command failed: {}", mvnCommand);
                }
            } else {
                failed = true;
                log.error(
                        "Maven command failed to execute in {} seconds: {}\n Consider increasing mavenTimeOutSeconds config value.",
                        mavenTimeOutSeconds, mvnCommand);
            }

            if (failed && !continueOnMavenError) {
                throw new ArtifactNotFoundException(
                        String.format("Unable to fetch the artifact %s from Maven repository", artifactMavenId));
            }

            return process;
        } catch (Exception e) {
            throw new ArtifactNotFoundException(e);
        }
    }

    private static List<String> drainInputStream(final InputStream stream) throws IOException {
        final BufferedReader reader = new BufferedReader(new InputStreamReader(stream));
        final ImmutableList.Builder<String> logLines = ImmutableList.builder();
        String s;
        while ((s = reader.readLine()) != null) {
            logLines.add(s);
        }
        return logLines.build();
    }
}
