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

package org.apache.commons.net.examples.ftp;

import java.io.IOException;
import java.net.InetAddress;

import org.apache.commons.net.ProtocolCommandListener;
import org.apache.commons.net.examples.PrintCommandListeners;
import org.apache.commons.net.ftp.FTPClient;
import org.apache.commons.net.ftp.FTPReply;

/**
 * This is an example program demonstrating how to use the FTPClient class. This program arranges a server to server file transfer that transfers a file from
 * host1 to host2. Keep in mind, this program might only work if host2 is the same as the host you run it on (for security reasons, some ftp servers only allow
 * PORT commands to be issued with a host argument equal to the client host).
 * <p>
 * Usage: ftp <host1> <user1> <pass1> <file1> <host2> <user2> <pass2> <file2>
 */
public final class ServerToServerFTP {

    public static void main(final String[] args) {
        // Verifica se ci sono abbastanza argomenti
        if (args.length < 8) {
            System.err.println("Usage: ftp <host1> <user1> <pass1> <file1> <host2> <user2> <pass2> <file2>");
            System.exit(1);
        }

        // Estrae informazioni dal primo server
        String server1 = args[0];
        int port1 = getPort(server1);
        server1 = getHost(server1);

        String user1 = args[1];
        String password1 = args[2];
        String file1 = args[3];

        // Estrae informazioni dal secondo server
        String server2 = args[4];
        int port2 = getPort(server2);
        server2 = getHost(server2);

        String user2 = args[5];
        String password2 = args[6];
        String file2 = args[7];

        ProtocolCommandListener listener = PrintCommandListeners.sysOutPrintCommandListener();
        FTPClient ftp1 = new FTPClient();
        FTPClient ftp2 = new FTPClient();

        ftp1.addProtocolCommandListener(listener);
        ftp2.addProtocolCommandListener(listener);

        try {
            // Connessione ai due server
            connectToServer(ftp1, server1, port1);
            connectToServer(ftp2, server2, port2);
            // Esecuzione del trasferimento di file
            performFileTransfer(ftp1, ftp2, user1, password1, user2, password2, file1, file2);
        } finally {
            // Chiusura delle connessioni
            disconnect(ftp1);
            disconnect(ftp2);
        }
    }

    private static int getPort(String server) {
        String[] parts = server.split(":");
        return parts.length == 2 ? Integer.parseInt(parts[1]) : 0;
    }

    private static String getHost(String server) {
        String[] parts = server.split(":");
        return parts[0];
    }

    private static void connectToServer(FTPClient ftp, String server, int port) {
        try {
            if (port > 0) {
                ftp.connect(server, port);
            } else {
                ftp.connect(server);
            }
            System.out.println("Connected to " + server + ".");
            int reply = ftp.getReplyCode();
            if (!FTPReply.isPositiveCompletion(reply)) {
                ftp.disconnect();
                System.err.println("FTP server " + server + " refused connection.");
                System.exit(1);
            }
        } catch (IOException e) {
            handleConnectionError(ftp, server, e);
        }
    }

    private static void handleConnectionError(FTPClient ftp, String server, IOException e) {
        if (ftp.isConnected()) {
            try {
                ftp.disconnect();
            } catch (IOException f) {
                // do nothing
            }
        }
        System.err.println("Could not connect to " + server + ".");
        e.printStackTrace();
        System.exit(1);
    }

    private static void performFileTransfer(FTPClient ftp1, FTPClient ftp2, String user1, String password1,
                                            String user2, String password2, String file1, String file2) {
        try {
            // Login al primo server
            if (!ftp1.login(user1, password1)) {
                System.err.println("Could not login to " + ftp1.getRemoteAddress().getHostAddress());
                return;
            }

            // Login al secondo server
            if (!ftp2.login(user2, password2)) {
                System.err.println("Could not login to " + ftp2.getRemoteAddress().getHostAddress());
                return;
            }

            // Impostazione delle modalità di trasferimento
            ftp2.enterRemotePassiveMode();
            ftp1.enterRemoteActiveMode(InetAddress.getByName(ftp2.getPassiveHost()), ftp2.getPassivePort());

            // Trasferimento dei file
            if (!ftp1.remoteRetrieve(file1) || !ftp2.remoteStoreUnique(file2)) {
                System.err.println("Couldn't initiate transfer. Check that file names are valid.");
                return;
            }

            // Attesa della risposta positiva
            ftp1.completePendingCommand();
            ftp2.completePendingCommand();

        } catch (IOException e) {
            e.printStackTrace();
            System.exit(1);
        }
    }

    private static void disconnect(FTPClient ftp) {
        try {
            if (ftp.isConnected()) {
                ftp.logout();
                ftp.disconnect();
            }
        } catch (IOException e) {
            // do nothing
        }
    }
}