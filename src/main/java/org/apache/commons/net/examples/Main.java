/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.commons.net.examples;

import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.security.CodeSource;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.Properties;

/**
 * Helper application for example classes.
 */
public class Main {

    private static boolean fromJar() {
        final CodeSource codeSource = Main.class.getProtectionDomain().getCodeSource();
        if (codeSource != null) {
            return codeSource.getLocation().getFile().endsWith(".jar");
        }
        return false; // No idea if this can happen
    }

    /**
     * Helper application for example classes. Lists available classes, and provides shorthand invocation. For example:<br>
     * {@code java -jar commons-net-examples-m.n.jar FTPClientExample -l host user password}
     *
     * @param args the first argument is used to name the class; remaining arguments are passed to the target class.
     * @throws Throwable if an error occurs
     */
    public static void main(final String[] args) throws Throwable {
        Properties properties = loadProperties();

        if (args.length == 0) {
            displayUsage(properties);
        } else {
            invokeExample(properties, args);
        }
    }

    private static Properties loadProperties() {
        Properties properties = new Properties();
        try (InputStream ras = Main.class.getResourceAsStream("examples.properties")) {
            if (ras != null) {
                properties.load(ras);
            } else {
                System.err.println("[Cannot find examples.properties file, so aliases cannot be used]");
            }
        } catch (Exception e) {
            System.err.println("Error loading properties: " + e.getMessage());
        }
        return properties;
    }

    private static void displayUsage(Properties properties) {
        if (Thread.currentThread().getStackTrace().length > 2) { // called by Maven
            System.out.println(
                "Usage: mvn -q exec:java -Dexec.arguments=<alias or exampleClass>,<exampleClass parameters> (comma-separated, no spaces)");
            System.out.println(
                "Or   : mvn -q exec:java -Dexec.args=\"<alias or exampleClass> <exampleClass parameters>\" (space separated)");
        } else if (fromJar()) {
            System.out.println("Usage: java -jar commons-net-examples-m.n.jar <alias or exampleClass> <exampleClass parameters>");
        } else {
            System.out.println("Usage: java -cp target/classes org.apache.commons.net.examples.Main <alias or exampleClass> <exampleClass parameters>");
        }

        List<String> aliases = getPropertyNamesAsList(properties);
        if (!aliases.isEmpty()) {
            System.out.println("\nAliases and their classes:");
            Collections.sort(aliases);
            for (String alias : aliases) {
                System.out.printf("%-25s %s%n", alias, properties.getProperty(alias));
            }
        }
    }

    private static List<String> getPropertyNamesAsList(Properties properties) {
        Enumeration<?> propertyNames = properties.propertyNames();
        List<String> list = new ArrayList<>();
        while (propertyNames.hasMoreElements()) {
            list.add((String) propertyNames.nextElement());
        }
        return list;
    }

    private static void invokeExample(Properties properties, String[] args) throws Throwable {
        String shortName = args[0];
        String fullName = properties.getProperty(shortName);
        if (fullName == null) {
            fullName = shortName;
        }
        try {
            Class<?> clazz = Class.forName(fullName);
            Method method = clazz.getDeclaredMethod("main", String[].class);
            String[] exampleArgs = new String[args.length - 1];
            System.arraycopy(args, 1, exampleArgs, 0, exampleArgs.length);
            method.invoke(null, (Object) exampleArgs);
        } catch (ClassNotFoundException e) {
            System.out.println(e);
        } catch (NoSuchMethodException e) {
            System.out.println("No main method found in " + fullName);
        } catch (InvocationTargetException ite) {
            Throwable cause = ite.getCause();
            if (cause != null) {
                throw cause;
            }
            throw ite;
        }
    }
}