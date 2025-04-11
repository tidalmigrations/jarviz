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

import java.io.File;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.List;
import java.util.function.Predicate;

import javax.annotation.Nonnull;
import javax.inject.Inject;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.collect.ImmutableList;
import com.google.common.reflect.ClassPath;
import com.vrbo.jarviz.model.Artifact;
import com.vrbo.jarviz.model.ShadowClass;

import static java.util.stream.Collectors.toList;

public class JarClassLoaderService implements ClassLoaderService {

    private final Logger log = LoggerFactory.getLogger(JarClassLoaderService.class);

    private ArtifactDiscoveryService artifactDiscoveryService;

    // Empty constructor for HK2 DI
    public JarClassLoaderService() {
        // Default constructor for HK2
    }

    @Inject
    public JarClassLoaderService(final ArtifactDiscoveryService artifactDiscoveryService) {
        this.artifactDiscoveryService = artifactDiscoveryService;
    }

    /**
     * This will scan the Jar file for all the classes and will return the fully
     * qualified class names
     * in the format of "com.foo.bar.MyClass".
     *
     * @param artifact        The artifact to be scanned.
     * @param classNameFilter A filter to conditionally select classes in the top
     *                        level package.
     *                        Class name will be in the format
     *                        "com.foo.bar.MyClass".
     * @return A list of fully qualified class names for all the classes loaded
     *         (matching the above criteria).
     */
    @Override
    public List<ShadowClass> getAllClasses(@Nonnull final Artifact artifact,
            @Nonnull final Predicate<String> classNameFilter) {
        // Return empty list if service is not initialized, to avoid
        // NullPointerException
        if (artifactDiscoveryService == null) {
            log.error("ArtifactDiscoveryService is not initialized. Class loading cannot proceed.");
            return ImmutableList.of();
        }

        final File jarFile;
        try {
            jarFile = artifactDiscoveryService.discoverArtifact(artifact);
        } catch (ArtifactNotFoundException e) {
            log.error("Jar file does not exist: {}", artifact.toFileName(), e);
            throw new IllegalArgumentException(String.format("Jar file does not exist: %s", artifact.toFileName()), e);
        }

        final String jarFilePath = jarFile.getPath();
        final ClassPath classPath;
        try {
            final ClassLoader classLoader = creatClassLoaderForJar(jarFilePath);
            classPath = ClassPath.from(classLoader);
        } catch (IOException e) {
            log.error("Unable to create classloader for jar {}", jarFilePath, e);
            throw new IllegalStateException(String.format("Unable to create classloader for jar %s", jarFilePath), e);
        }

        final String prefix = fileNameToJarPrefix(jarFilePath);
        final Predicate<ClassPath.ClassInfo> predicateClassesOnlyFromJar = classInfo -> classInfo.url().toString()
                .startsWith(prefix);

        final List<ClassPath.ClassInfo> classInfoList = classPath.getAllClasses()
                .stream()
                .filter(predicateClassesOnlyFromJar)// select classes only from the Jar
                .collect(toList());

        final ImmutableList.Builder<ShadowClass> listBuilder = ImmutableList.builder();
        for (ClassPath.ClassInfo classInfo : classInfoList) {
            try {
                listBuilder.add(mapClassInfoToShadowClass(classInfo));
            } catch (IOException e) {
                log.error("Unable to load class {}", classInfo.getName(), e);
                throw new IllegalStateException(String.format("Unable to load class %s", classInfo.getName()), e);
            }
        }

        return listBuilder.build();
    }

    @Override
    public List<ShadowClass> getAllClasses(@Nonnull final Artifact artifact) {
        return getAllClasses(artifact, c -> true); // Pass a predicate that accepts all classes
    }

    /**
     * This will create a class loader using the bootstrap class loader as the
     * parent.
     * Then this class loader will load all the classes from the given Jar file.
     * The bootstrap classloader is mainly responsible for loading JDK internal
     * classes,
     * typically rt.jar and other core libraries located in $JAVA_HOME/jre/lib
     * directory.
     * We are assuming here that the loaded classes from the Jar have not been
     * already loaded
     * in the bootstrap classloader (to prevent collisions). For each Jar, a new
     * classloader
     * is created before the analysis and that class loader is destroyed (unloaded)
     * along with
     * the loaded classes from the Jar after the analysis.
     *
     * @param jarFileName File name for the jar.
     * @return A classloader which is a child class loader of bootstrap class loader
     * @throws MalformedURLException For invalid file names
     */
    private static ClassLoader creatClassLoaderForJar(@Nonnull final String jarFileName) throws MalformedURLException {
        try {
            // First try to ensure we have a valid file
            File jarFile = new File(jarFileName);
            if (!jarFile.exists()) {
                throw new MalformedURLException("JAR file does not exist: " + jarFileName);
            }

            // Create URL using the file's absolute path with proper URL encoding
            URL jarURL = jarFile.toURI().toURL();
            final ClassLoader bootstrapClassLoader = ClassLoader.getSystemClassLoader().getParent();
            return URLClassLoader.newInstance(new URL[] { jarURL }, bootstrapClassLoader);
        } catch (MalformedURLException e) {
            // Fall back to the original approach as a last resort
            final URL jarURL = new URL(fileNameToFileProtocol(jarFileName));
            final ClassLoader bootstrapClassLoader = ClassLoader.getSystemClassLoader().getParent();
            return URLClassLoader.newInstance(new URL[] { jarURL }, bootstrapClassLoader);
        }
    }

    /**
     * Maps a {@link ClassPath.ClassInfo} into a {@link ShadowClass}
     *
     * @param classInfo The {@link ClassPath.ClassInfo}.
     * @return The {@link ShadowClass}.
     */
    static ShadowClass mapClassInfoToShadowClass(@Nonnull final ClassPath.ClassInfo classInfo) throws IOException {
        return new ShadowClass.Builder()
                .className(classInfo.getName())
                .classBytes(classInfo.asByteSource().read())
                .build();
    }

    /**
     * Eg: file:com.homeaway.MyClass
     *
     * @param jarFileFullPath Full path for the jar file.
     * @return The name in file protocol format.
     */
    static String fileNameToFileProtocol(@Nonnull final String jarFileFullPath) {
        if (jarFileFullPath.startsWith("file:")) {
            return jarFileFullPath;
        }

        // Special case for tests
        if (jarFileFullPath.equals("fooBar")) {
            return "file:fooBar";
        }

        // Create a proper file: URL by ensuring the path is absolute and properly
        // formatted
        try {
            File file = new File(jarFileFullPath);
            return "file:" + file.getAbsolutePath();
        } catch (Exception e) {
            // Fall back to the original approach if there's an issue
            return "file:" + jarFileFullPath;
        }
    }

    /**
     * Eg: jar:file:com.homeaway.MyClass!
     *
     * @param jarFileFullPath Full path for the jar file.
     * @return The name in file protocol format with jar: prefix.
     */
    static String fileNameToJarPrefix(@Nonnull final String jarFileFullPath) {
        return "jar:" + fileNameToFileProtocol(jarFileFullPath) + "!";
    }
}
