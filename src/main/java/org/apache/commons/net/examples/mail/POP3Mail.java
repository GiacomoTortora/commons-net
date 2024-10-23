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
import java.io.IOException;
import java.util.Locale;

import org.apache.commons.net.examples.PrintCommandListeners;
import org.apache.commons.net.pop3.POP3Client;
import org.apache.commons.net.pop3.POP3MessageInfo;
import org.apache.commons.net.pop3.POP3SClient;

/**
 * This is an example program demonstrating how to use the POP3[S]Client class. This program connects to a POP3[S] server and retrieves the message headers of
 * all the messages, printing the {@code From:} and {@code Subject:} header entries for each message.
 * <p>
 * See main() method for usage details
 */
public final class POP3Mail {

    public static void main(final String[] args) {
        if (args.length < 3) {
            System.err.println("Usage: POP3Mail <server[:port]> <username> <password|-|*|VARNAME> [TLS [true=implicit]]");
            System.exit(1);
        }
        final String[] arg0 = args[0].split(":");
        final String server = arg0[0];
        final String username = args[1];
        String password = args[2];
        // prompt for the password if necessary
        password = getPassword(username, password);
        final POP3Client pop3 = initializePOP3Client(args);
        final int port = getPort(arg0, pop3);
        System.out.println("Connecting to server " + server + " on " + port);
        try {
            connectAndProcessMail(pop3, server, username, password);
        } catch (final IOException e) {
            e.printStackTrace();
        }
    }

    private static void connectAndProcessMail(final POP3Client pop3, final String server, final String username, String password) throws IOException {
        pop3.setDefaultTimeout(60000);
        pop3.addProtocolCommandListener(PrintCommandListeners.sysOutPrintCommandListener());
        try {
            pop3.connect(server);
            if (!pop3.login(username, password)) {
                System.err.println("Could not login to server. Check password.");
                pop3.disconnect();
                return;
            }
            final POP3MessageInfo status = pop3.status();
            if (status == null) {
                System.err.println("Could not retrieve status.");
                pop3.logout();
                pop3.disconnect();
                return;
            }
            System.out.println("Status: " + status);
            processMessages(pop3);
        } finally {
            pop3.logout();
            pop3.disconnect();
        }
    }

    private static void processMessages(final POP3Client pop3) throws IOException {
        final POP3MessageInfo[] messages = pop3.listMessages();
        if (messages == null) {
            System.err.println("Could not retrieve message list.");
            return;
        }
        if (messages.length == 0) {
            System.out.println("No messages");
            return;
        }
        System.out.println("Message count: " + messages.length);
        for (final POP3MessageInfo msginfo : messages) {
            try (BufferedReader reader = (BufferedReader) pop3.retrieveMessageTop(msginfo.number, 0)) {
                if (reader == null) {
                    System.err.println("Could not retrieve message header.");
                    return;
                }
                printMessageInfo(reader, msginfo.number);
            }
        }
    }

    private static String getPassword(final String username, String password) {
        try {
            return Utils.getPassword(username, password);
        } catch (final IOException e) {
            System.err.println("Could not retrieve password: " + e.getMessage());
            System.exit(1);
            return null;  // Unreachable, but required for compilation.
        }
    }

    private static POP3Client initializePOP3Client(final String[] args) {
        final String proto = args.length > 3 ? args[3] : null;
        final boolean implicit = args.length > 4 && Boolean.parseBoolean(args[4]);
        if (proto != null) {
            System.out.println("Using secure protocol: " + proto);
            return new POP3SClient(proto, implicit);
        } else {
            return new POP3Client();
        }
    }

    private static int getPort(final String[] arg0, final POP3Client pop3) {
        if (arg0.length == 2) {
            return Integer.parseInt(arg0[1]);
        } else {
            return pop3.getDefaultPort();
        }
    }

    public static void printMessageInfo(final BufferedReader reader, final int id) throws IOException {
        String from = "";
        String subject = "";
        String line;
        while ((line = reader.readLine()) != null) {
            final String lower = line.toLowerCase(Locale.ENGLISH);
            if (lower.startsWith("from: ")) {
                from = line.substring(6).trim();
            } else if (lower.startsWith("subject: ")) {
                subject = line.substring(9).trim();
            }
        }
        System.out.println(Integer.toString(id) + " From: " + from + "  Subject: " + subject);
    }
}