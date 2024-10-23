/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.commons.net.examples.mail;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.commons.net.pop3.POP3Client;
import org.apache.commons.net.pop3.POP3MessageInfo;
import org.apache.commons.net.pop3.POP3SClient;

/**
 * This is an example program demonstrating how to use the POP3[S]Client class. This program connects to a POP3[S] server and writes the messages to an mbox
 * file.
 * <p>
 * The code currently assumes that POP3Client decodes the POP3 data as iso-8859-1. The POP3 standard only allows for ASCII so in theory iso-8859-1 should be OK.
 * However, it appears that actual POP3 implementations may return 8bit data that is outside the ASCII range; this may result in loss of data when the mailbox
 * is created.
 * <p>
 * See main() method for usage details
 */
public final class POP3ExportMbox {

    private static final Pattern PATFROM = Pattern.compile(">*From "); // unescaped From_

    public static void main(final String[] args) throws IOException {
        int argIdx;
        String file = null;
        for (argIdx = 0; argIdx < args.length; argIdx++) {
            if (!args[argIdx].equals("-F")) {
                break;
            }
            file = args[++argIdx];
        }

        final int argCount = args.length - argIdx;
        if (argCount < 3) {
            System.err.println("Usage: POP3Mail [-F file/directory] <server[:port]> <user> <password|-|*|VARNAME> [TLS [true=implicit]]");
            System.exit(1);
        }

        final String[] arg0 = args[argIdx++].split(":");
        final String server = arg0[0];
        final String user = args[argIdx++];
        String password = getPassword(user, args[argIdx++]);
        final String proto = args.length > argIdx ? args[argIdx++] : null;
        final boolean implicit = args.length > argIdx && Boolean.parseBoolean(args[argIdx++]);

        POP3Client pop3 = connectToServer(proto, implicit, server);

        if (pop3 == null || !loginToServer(pop3, user, password)) {
            return;
        }

        handleMessages(pop3, file);
        try {
            pop3.logout();
            pop3.disconnect();
        } catch (final IOException e) {
            e.printStackTrace();
        }
    }

    private static String getPassword(String user, String password) {
        try {
            return Utils.getPassword(user, password);
        } catch (final IOException e) {
            System.err.println("Could not retrieve password: " + e.getMessage());
            System.exit(1);
        }
        return null; // this won't be reached, but makes the method compile
    }

    private static POP3Client connectToServer(String proto, boolean implicit, String server) {
        final POP3Client pop3;
        if (proto != null) {
            System.out.println("Using secure protocol: " + proto);
            pop3 = new POP3SClient(proto, implicit);
        } else {
            pop3 = new POP3Client();
        }

        int port = pop3.getDefaultPort();
        System.out.println("Connecting to server " + server + " on " + port);
        pop3.setDefaultTimeout(60000);

        try {
            pop3.connect(server);
            return pop3;
        } catch (final IOException e) {
            System.err.println("Could not connect to server.");
            e.printStackTrace();
            return null;
        }
    }

    private static boolean loginToServer(POP3Client pop3, String user, String password) throws IOException {
        try {
            if (!pop3.login(user, password)) {
                System.err.println("Could not login to server. Check password.");
                pop3.disconnect();
                return false;
            }
            return true;
        } catch (final IOException e) {
            System.err.println("Login failed.");
            pop3.disconnect();
            return false;
        }
    }

    private static void handleMessages(POP3Client pop3, String file) {
        try {
            final POP3MessageInfo status = pop3.status();
            if (status == null) {
                System.err.println("Could not retrieve status.");
                pop3.logout();
                return;
            }

            System.out.println("Status: " + status);
            final int count = status.number;
            if (file != null) {
                writeMessagesToFile(pop3, file, count);
            }
        } catch (final IOException e) {
            System.err.println("Error I/O exception: " + e.getMessage());
        }
    }

    private static void writeMessagesToFile(POP3Client pop3, String file, int count) throws IOException {
        final File mbox = new File(file);
        if (mbox.isDirectory()) {
            System.out.println("Writing dir: " + mbox);
            for (int i = 1; i <= count; i++) {
                try (OutputStreamWriter fw = new OutputStreamWriter(new FileOutputStream(new File(mbox, i + ".eml")),
                        StandardCharsets.ISO_8859_1)) {
                    writeFile(pop3, fw, i);
                }
            }
        } else {
            System.out.println("Writing file: " + mbox);
            try (OutputStreamWriter fw = new OutputStreamWriter(new FileOutputStream(mbox), StandardCharsets.ISO_8859_1)) {
                for (int i = 1; i <= count; i++) {
                    writeMbox(pop3, fw, i);
                }
            }
        }
    }

    private static boolean startsWith(final String input, final Pattern pat) {
        final Matcher m = pat.matcher(input);
        return m.lookingAt();
    }

    private static void writeFile(final POP3Client pop3, final OutputStreamWriter fw, final int i) throws IOException {
        try (BufferedReader r = (BufferedReader) pop3.retrieveMessage(i)) {
            String line;
            while ((line = r.readLine()) != null) {
                fw.write(line);
                fw.write("\n");
            }
        }
    }

    private static void writeMbox(final POP3Client pop3, final OutputStreamWriter fw, final int i) throws IOException {
        final SimpleDateFormat DATE_FORMAT // for mbox From_ lines
                = new SimpleDateFormat("EEE MMM dd HH:mm:ss yyyy");
        final String replyTo = "MAILER-DAEMON"; // default
        final Date received = new Date();
        try (BufferedReader r = (BufferedReader) pop3.retrieveMessage(i)) {
            fw.append("From ");
            fw.append(replyTo);
            fw.append(' ');
            fw.append(DATE_FORMAT.format(received));
            fw.append("\n");
            String line;
            while ((line = r.readLine()) != null) {
                if (startsWith(line, PATFROM)) {
                    fw.write(">");
                }
                fw.write(line);
                fw.write("\n");
            }
            fw.write("\n");
        }
    }
}