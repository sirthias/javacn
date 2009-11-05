/*
 * Copyright 2000-2008 JetBrains s.r.o.
 * Modified in 2009 by Mathias Doenitz
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.parboiled.ant;

import org.parboiled.compiler.notNullVerification.NotNullVerifyingInstrumenter;
import org.apache.tools.ant.BuildException;
import org.apache.tools.ant.Project;
import org.apache.tools.ant.taskdefs.Javac;
import org.apache.tools.ant.types.Path;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.StringTokenizer;

public class Javacn extends Javac {

    public Javacn() {}

    /**
     * Returns true if actual compilation is to be done.
     *
     * @return true if the java classes are to be compiled, false if just instrumentation is to be performed.
     */
    protected boolean compiling() {
        return true;
    }

    /**
     * Logs an error message with the given option name when this task is non-compiling.
     *
     * @param optionName the option name to warn about.
     */
    private void logErrorIfNotCompiling(String optionName) {
        if (!compiling()) {
            log("The option " + optionName + " is not supported by instrumentNotNull task", Project.MSG_ERR);
        }
    }

    public void setDebugLevel(String v) {
        logErrorIfNotCompiling("debugLevel");
        super.setDebugLevel(v);
    }

    public void setListfiles(boolean list) {
        logErrorIfNotCompiling("listFiles");
        super.setListfiles(list);
    }

    public void setMemoryInitialSize(String memoryInitialSize) {
        logErrorIfNotCompiling("memoryInitialSize");
        super.setMemoryInitialSize(memoryInitialSize);
    }

    public void setMemoryMaximumSize(String memoryMaximumSize) {
        logErrorIfNotCompiling("memoryMaximumSize");
        super.setMemoryMaximumSize(memoryMaximumSize);
    }

    public void setEncoding(String encoding) {
        logErrorIfNotCompiling("encoding");
        super.setEncoding(encoding);
    }

    public void setOptimize(boolean optimize) {
        logErrorIfNotCompiling("optimize");
        super.setOptimize(optimize);
    }

    public void setDepend(boolean depend) {
        logErrorIfNotCompiling("depend");
        super.setDepend(depend);
    }

    public void setFork(boolean f) {
        logErrorIfNotCompiling("fork");
        super.setFork(f);
    }

    public void setExecutable(String forkExec) {
        logErrorIfNotCompiling("executable");
        super.setExecutable(forkExec);
    }

    public void setCompiler(String compiler) {
        logErrorIfNotCompiling("compiler");
        super.setCompiler(compiler);
    }

    protected void compile() {
        if (compiling()) {
            // compile only if required
            super.compile();
        }

        ClassLoader loader = buildClasspathClassLoader();
        if (loader != null) {
            if (isJdkVersion(5) || isJdkVersion(6)) {
                int instrumented = instrumentNotNull(getDestdir(), loader);
                log("Added @NotNull assertions to " + instrumented + " files", Project.MSG_INFO);
            } else {
                log("Skipped @NotNull instrumentation because target JDK is not 1.5 or 1.6", Project.MSG_INFO);
            }
        }
    }

    /**
     * Create class loader based on classpath, bootclasspath, and sourcepath.
     *
     * @return a URL classloader
     */
    private ClassLoader buildClasspathClassLoader() {
        StringBuilder classPathBuffer = new StringBuilder();
        Path cp = new Path(getProject());
        appendPath(cp, getBootclasspath());
        cp.setLocation(getDestdir().getAbsoluteFile());
        appendPath(cp, getClasspath());
        appendPath(cp, getSourcepath());
        appendPath(cp, getSrcdir());
        if (getIncludeantruntime()) {
            cp.addExisting(cp.concatSystemClasspath("last"));
        }
        if (getIncludejavaruntime()) {
            cp.addJavaRuntime();
        }
        cp.addExtdirs(getExtdirs());

        String[] pathElements = cp.list();
        for (String pathElement : pathElements) {
            classPathBuffer.append(File.pathSeparator);
            classPathBuffer.append(pathElement);
        }

        String classPath = classPathBuffer.toString();
        log("classpath=" + classPath, Project.MSG_VERBOSE);

        try {
            return createClassLoader(classPath);
        }
        catch (MalformedURLException e) {
            fireError(e.getMessage());
            return null;
        }
    }

    /**
     * Check JDK version
     *
     * @param ver the version to check (for Java 5 it is {@value 5}. for Java 6 it is {@value 6})
     * @return if the target JDK is of the specified version
     */
    private boolean isJdkVersion(int ver) {
        String versionString = Integer.toString(ver);
        String targetVersion = getTarget();
        if (targetVersion == null) {
            String[] strings = getCurrentCompilerArgs();
            for (int i = 0; i < strings.length; i++) {
                log("currentCompilerArgs: " + strings[i], Project.MSG_VERBOSE);
                if (strings[i].equals("-target") && i < strings.length - 1) {
                    targetVersion = strings[i + 1];
                    break;
                }
            }
        }
        if (targetVersion != null) {
            log("targetVersion: " + targetVersion, Project.MSG_VERBOSE);
            return targetVersion.equals(versionString) || targetVersion.equals("1." + versionString);
        }
        return getCompilerVersion().equals("javac1." + versionString);
    }

    /**
     * @return the flags for class writer
     */
    private int getAsmClassWriterFlags() {
        return isJdkVersion(6) ? ClassWriter.COMPUTE_FRAMES : ClassWriter.COMPUTE_MAXS;
    }

    /**
     * Append path to class path if the appended path is not empty and is not null
     *
     * @param cp the path to modify
     * @param p  the path to append
     */
    private void appendPath(Path cp, Path p) {
        if (p != null && p.size() > 0) {
            cp.append(p);
        }
    }

    /**
     * Instrument classes with NotNull annotations
     *
     * @param dir    the directory with classes to instrument (the directory is processed recursively)
     * @param loader the classloader to use
     * @return the amount of classes actually affected by instrumentation
     */
    private int instrumentNotNull(File dir, ClassLoader loader) {
        int instrumented = 0;
        File[] files = dir.listFiles();
        for (File file : files) {
            String name = file.getName();
            if (name.endsWith(".class")) {
                String path = file.getPath();
                log("Adding @NotNull assertions to " + path, Project.MSG_VERBOSE);
                try {
                    FileInputStream inputStream = new FileInputStream(file);
                    try {
                        ClassReader reader = new ClassReader(inputStream);
                        ClassWriter writer = new AntClassWriter(getAsmClassWriterFlags(), loader);

                        NotNullVerifyingInstrumenter instrumenter = new NotNullVerifyingInstrumenter(this, writer);
                        reader.accept(instrumenter, 0);
                        if (instrumenter.isModification()) {
                            FileOutputStream fileOutputStream = new FileOutputStream(path);
                            try {
                                fileOutputStream.write(writer.toByteArray());
                                instrumented++;
                            }
                            finally {
                                fileOutputStream.close();
                            }
                        }
                    }
                    finally {
                        inputStream.close();
                    }
                }
                catch (IOException e) {
                    log("Failed to instrument @NotNull assertion for " + path + ": " + e.getMessage(),
                            Project.MSG_WARN);
                }
                catch (Exception e) {
                    fireError("@NotNull instrumentation failed for " + path + ": " + e.toString());
                }
            } else if (file.isDirectory()) {
                instrumented += instrumentNotNull(file, loader);
            }
        }

        return instrumented;
    }

    private void fireError(String message) {
        if (failOnError) {
            throw new BuildException(message, getLocation());
        } else {
            log(message, Project.MSG_ERR);
        }
    }

    private static URLClassLoader createClassLoader(String classPath) throws MalformedURLException {
        ArrayList<URL> urls = new ArrayList<URL>();
        for (StringTokenizer tokenizer = new StringTokenizer(classPath, File.pathSeparator); tokenizer
                .hasMoreTokens();) {
            String s = tokenizer.nextToken();
            urls.add(new File(s).toURL());
        }
        URL[] urlsArr = urls.toArray(new URL[urls.size()]);
        return new URLClassLoader(urlsArr, null);
    }

}
