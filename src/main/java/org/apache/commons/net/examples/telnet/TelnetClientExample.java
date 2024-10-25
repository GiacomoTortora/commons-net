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

package org.apache.commons.net.examples.telnet;

import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.Charset;
import java.time.Duration;
import java.util.StringTokenizer;

import org.apache.commons.net.telnet.EchoOptionHandler;
import org.apache.commons.net.telnet.InvalidTelnetOptionException;
import org.apache.commons.net.telnet.SimpleOptionHandler;
import org.apache.commons.net.telnet.SuppressGAOptionHandler;
import org.apache.commons.net.telnet.TelnetClient;
import org.apache.commons.net.telnet.TelnetNotificationHandler;
import org.apache.commons.net.telnet.TerminalTypeOptionHandler;

/**
 * This is a simple example of use of TelnetClient. An external option handler (SimpleTelnetOptionHandler) is used. Initial configuration requested by
 * TelnetClient will be: WILL ECHO, WILL SUPPRESS-GA, DO SUPPRESS-GA. VT100 terminal type will be subnegotiated.
 * <p>
 * Also, use of the sendAYT(), getLocalOptionState(), getRemoteOptionState() is demonstrated. When connected, type AYT to send an AYT command to the server and
 * see the result. Type OPT to see a report of the state of the first 25 options.
 */
public class TelnetClientExample implements Runnable, TelnetNotificationHandler {
    private static TelnetClient tc;

    /**
     * Main for the TelnetClientExample.
     *
     * @param args input params
     * @throws Exception on error
     */
    public static void main(final String[] args) throws Exception {
        if (args.length < 1) {
            System.err.println("Usage: TelnetClientExample <remote-ip> [<remote-port>]");
            System.exit(1);
        }
        final String remoteip = args[0];
        final int remoteport = args.length > 1 ? Integer.parseInt(args[1]) : 23;
        try (FileOutputStream fout = new FileOutputStream("spy.log", true)) {
            TelnetClientExample example = new TelnetClientExample();
            example.setupTelnetClient(remoteip, remoteport, fout);
        } catch (IOException e) {
            System.err.println("Exception while opening the spy file: " + e.getMessage());
        }
    }
    private void setupTelnetClient(String remoteip, int remoteport, FileOutputStream fout) {
        try {
            connectToServer(remoteip, remoteport);
            startReaderThread();
            handleUserInput(fout);
        } catch (IOException e) {
            System.err.println("Exception while connecting:" + e.getMessage());
            System.exit(1);
        }
    }
    private void connectToServer(String remoteip, int remoteport) throws IOException {
        tc = new TelnetClient();
        registerOptionHandlers();
        tc.connect(remoteip, remoteport);
        System.out.println("TelnetClientExample");
        printInstructions();
    }
    private void registerOptionHandlers() {
        try {
            tc.addOptionHandler(new TerminalTypeOptionHandler("VT100", false, false, true, false));
            tc.addOptionHandler(new EchoOptionHandler(true, false, true, false));
            tc.addOptionHandler(new SuppressGAOptionHandler(true, true, true, true));
        } catch (InvalidTelnetOptionException e) {
            System.err.println("Error registering option handlers: " + e.getMessage());
        } catch (IOException i) {
            System.err.println("IO error: " + i.getMessage());
        }
    }
    private void printInstructions() {
        System.out.println("Type AYT to send an AYT Telnet command");
        System.out.println("Type OPT to print a report of status of options (0-24)");
        System.out.println("Type REGISTER to register a new SimpleOptionHandler");
        System.out.println("Type UNREGISTER to unregister an OptionHandler");
        System.out.println("Type SPY to register the spy (connect to port 3333 to spy)");
        System.out.println("Type UNSPY to stop spying the connection");
        System.out.println("Type ^[A-Z] to send the control character; use ^^ to send ^");
    }
    private void startReaderThread() {
        final Thread reader = new Thread(this);
        tc.registerNotifHandler(this);
        reader.start();
    }
    private void handleUserInput(FileOutputStream fout) {
        try {
            final OutputStream outstr = tc.getOutputStream();
            final byte[] buff = new byte[1024];
            int readCount;
            while ((readCount = System.in.read(buff)) > 0) {
                processInputLine(buff, readCount, outstr, fout);
            }
            tc.disconnect();
        } catch (IOException e) {
            System.err.println("Exception while reading keyboard:" + e.getMessage());
        }
    }
    private void processInputLine(byte[] buff, int readCount, OutputStream outstr, FileOutputStream fout) {
        final Charset charset = Charset.defaultCharset();
        final String line = new String(buff, 0, readCount, charset); // deliberate use of default charset
        try {
            if (line.startsWith("AYT")) {
                System.out.println("Sending AYT");
                System.out.println("AYT response:" + tc.sendAYT(Duration.ofSeconds(5)));
            } else if (line.startsWith("OPT")) {
                printOptionStatus();
            } else if (line.startsWith("REGISTER")) {
                registerOption(buff, charset);
            } else if (line.startsWith("UNREGISTER")) {
                unregisterOption(buff, charset);
            } else if (line.startsWith("SPY")) {
                tc.registerSpyStream(fout);
            } else if (line.startsWith("UNSPY")) {
                tc.stopSpyStream();
            } else {
                sendControlCharacter(buff, readCount, outstr);
            }
        } catch (IOException e) {
            System.err.println("Exception while processing input line: " + e.getMessage());
        } catch (InterruptedException i) {
            System.err.println("Interrupt exception: " + i.getMessage());
        }
    }
    private void printOptionStatus() {
        System.out.println("Status of options:");
        for (int ii = 0; ii < 25; ii++) {
            System.out.println("Local Option " + ii + ":" + tc.getLocalOptionState(ii) + " Remote Option " + ii + ":"
                    + tc.getRemoteOptionState(ii));
        }
    }

    private void registerOption(byte[] buff, Charset charset) {
        final StringTokenizer st = new StringTokenizer(new String(buff, charset));
        try {
            st.nextToken();
            final int opcode = Integer.parseInt(st.nextToken());
            final boolean initlocal = Boolean.parseBoolean(st.nextToken());
            final boolean initremote = Boolean.parseBoolean(st.nextToken());
            final boolean acceptlocal = Boolean.parseBoolean(st.nextToken());
            final boolean acceptremote = Boolean.parseBoolean(st.nextToken());
            final SimpleOptionHandler opthand = new SimpleOptionHandler(opcode, initlocal, initremote, acceptlocal, acceptremote);
            tc.addOptionHandler(opthand);
        } catch (InvalidTelnetOptionException e) {
            System.err.println("Error registering option: " + e.getMessage());
        } catch (Exception e) {
            System.err.println("Invalid REGISTER command. Use REGISTER optcode initlocal initremote acceptlocal acceptremote");
        }
    }

    private void unregisterOption(byte[] buff, Charset charset) {
        final StringTokenizer st = new StringTokenizer(new String(buff, charset));
        try {
            st.nextToken();
            final int opcode = Integer.parseInt(st.nextToken());
            tc.deleteOptionHandler(opcode);
        } catch (InvalidTelnetOptionException e) {
            System.err.println("Error unregistering option: " + e.getMessage());
        } catch (Exception e) {
            System.err.println("Invalid UNREGISTER command. Use UNREGISTER optcode");
        }
    }

    private void sendControlCharacter(byte[] buff, int readCount, OutputStream outstr) throws IOException {
        if (buff[0] == '^') {
            outstr.write(buff, 0, readCount);
        } else {
            outstr.write(buff[1] - 'A' + 1);
        }
        outstr.flush();
    }
    @Override
    public void run() {
        final InputStream instr = tc.getInputStream();

        try {
            final byte[] buff = new byte[1024];
            int readCount;

            do {
                readCount = instr.read(buff);
                if (readCount > 0) {
                    System.out.print(new String(buff, 0, readCount, Charset.defaultCharset()));
                }
            } while (readCount >= 0);
        } catch (final IOException e) {
            System.err.println("Exception while reading socket:" + e.getMessage());
        }
        try {
            tc.disconnect();
        } catch (final IOException e) {
            System.err.println("Exception while closing Telnet:" + e.getMessage());
        }
    }

    @Override
    public void receivedNegotiation(final int negotiation_code, final int option_code) {
        String command;
        switch (negotiation_code) {
            case RECEIVED_DO:
                command = "DO";
                break;
            case RECEIVED_DONT:
                command = "DONT";
                break;
            case RECEIVED_WILL:
                command = "WILL";
                break;
            case RECEIVED_WONT:
                command = "WONT";
                break;
            case RECEIVED_COMMAND:
                command = "COMMAND";
                break;
            default:
                command = Integer.toString(negotiation_code); // Should not happen
                break;
        }
        System.out.println("Received " + command + " for option code " + option_code);
    }
}