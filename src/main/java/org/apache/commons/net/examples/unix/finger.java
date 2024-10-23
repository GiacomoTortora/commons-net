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

package org.apache.commons.net.examples.unix;

import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;

import org.apache.commons.net.finger.FingerClient;

/**
 * This is an example of how you would implement the finger command in Java using NetComponents. The Java version is much shorter. But keep in mind that the
 * UNIX finger command reads all sorts of local files to output local finger information. This program only queries the finger daemon.
 * <p>
 * The -l flag is used to request long output from the server.
 */
public final class finger {

    public static void main(final String[] args) {
        boolean longOutput = false;
        int arg = 0;
        FingerClient finger = new FingerClient();

        // Get flags. If an invalid flag is present, exit with usage message.
        arg = parseFlags(args, arg);

        finger.setDefaultTimeout(60000);

        if (arg >= args.length) {
            handleLocalFinger(finger, longOutput);
        } else {
            handleRemoteFingers(args, finger, longOutput, arg);
        }
    }

    private static int parseFlags(final String[] args, int arg) {
        while (arg < args.length && args[arg].startsWith("-")) {
            if (!args[arg].equals("-l")) {
                System.err.println("usage: finger [-l] [[[handle][@<server>]] ...]");
                System.exit(1);
            }
            ++arg;
        }
        return arg;
    }

    private static void handleLocalFinger(FingerClient finger, boolean longOutput) {
        InetAddress address = getLocalHost();

        try {
            finger.connect(address);
            System.out.print(finger.query(longOutput));
            finger.disconnect();
        } catch (final IOException e) {
            System.err.println("Error I/O exception: " + e.getMessage());
            System.exit(1);
        }
    }

    private static void handleRemoteFingers(final String[] args, FingerClient finger, boolean longOutput, int arg) {
        while (arg < args.length) {
            String handle;
            InetAddress address;
            int index = args[arg].lastIndexOf('@');

            if (index == -1) {
                handle = args[arg];
                address = getLocalHost();
            } else {
                handle = args[arg].substring(0, index);
                String host = args[arg].substring(index + 1);
                address = getAddressByName(host);
                if (address == null) {
                    System.exit(1);
                }
            }

            try {
                finger.connect(address);
                System.out.print(finger.query(longOutput, handle));
                finger.disconnect();
            } catch (final IOException e) {
                System.err.println("Error I/O exception: " + e.getMessage());
                System.exit(1);
            }

            ++arg;
            if (arg != args.length) {
                System.out.print("\n");
            }
        }
    }

    private static InetAddress getLocalHost() {
        try {
            return InetAddress.getLocalHost();
        } catch (final UnknownHostException e) {
            System.err.println("Error unknown host: " + e.getMessage());
            System.exit(1);
            return null; // Non verrà mai raggiunto, ma è necessario per la compilazione
        }
    }

    private static InetAddress getAddressByName(String host) {
        try {
            InetAddress address = InetAddress.getByName(host);
            System.out.println("[" + address.getHostName() + "]");
            return address;
        } catch (final UnknownHostException e) {
            System.err.println("Error unknown host: " + e.getMessage());
            return null; // Ritorna null se non riesce a trovare l'indirizzo
        }
    }
}