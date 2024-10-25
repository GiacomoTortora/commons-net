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

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.TimeZone;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.commons.net.PrintCommandListener;
import org.apache.commons.net.ProtocolCommandEvent;
import org.apache.commons.net.imap.IMAP;
import org.apache.commons.net.imap.IMAP.IMAPChunkListener;
import org.apache.commons.net.imap.IMAPClient;
import org.apache.commons.net.imap.IMAPReply;

/**
 * This is an example program demonstrating how to use the IMAP[S]Client class. This program connects to a IMAP[S] server and exports selected messages from a
 * folder into an mbox file.
 * <p>
 * Usage: IMAPExportMbox imap[s]://user:password@host[:port]/folder/path <mboxfile> [sequence-set] [item-names]
 * <p>
 * An example sequence-set might be:
 * <ul>
 * <li>11,2,3:10,20:*</li>
 * <li>1:* - this is the default</li>
 * </ul>
 * <p>
 * Some example item-names might be:
 * <ul>
 * <li>BODY.PEEK[HEADER]</li>
 * <li>'BODY.PEEK[HEADER.FIELDS (SUBJECT)]'</li>
 * <li>ALL - macro equivalent to '(FLAGS INTERNALDATE RFC822.SIZE ENVELOPE)'</li>
 * <li>FAST - macro equivalent to '(FLAGS INTERNALDATE RFC822.SIZE)'</li>
 * <li>FULL - macro equivalent to '(FLAGS INTERNALDATE RFC822.SIZE ENVELOPE BODY)'</li>
 * <li>ENVELOPE X-GM-LABELS</li>
 * <li>'(INTERNALDATE BODY.PEEK[])' - this is the default</li>
 * </ul>
 * <p>
 * Macro names cannot be combined with anything else; they must be used alone.<br>
 * Note that using BODY will set the \Seen flag. This is why the default uses BODY.PEEK[].<br>
 * The item name X-GM-LABELS is a Google Mail extension; it shows the labels for a message.<br>
 * For example:<br>
 * IMAPExportMbox imaps://user:password@imap.googlemail.com/messages_for_export exported.mbox 1:10,20<br>
 * IMAPExportMbox imaps://user:password@imap.googlemail.com/messages_for_export exported.mbox 3 ENVELOPE X-GM-LABELS<br>
 * <p>
 * The sequence-set is passed unmodified to the FETCH command.<br>
 * The item names are wrapped in parentheses if more than one is provided. Otherwise, the parameter is assumed to be wrapped if necessary.<br>
 * Parameters with spaces must be quoted otherwise the OS shell will normally treat them as separate parameters.<br>
 * Also, the listener that writes the mailbox only captures the multi-line responses (e.g. ones that include BODY references). It does not capture the output
 * from FETCH commands using item names such as ENVELOPE or FLAGS that return a single line response.
 */
public final class IMAPExportMbox {

    private static final class MboxListener implements IMAPChunkListener {

        private final BufferedWriter bufferedWriter;
        volatile AtomicInteger total = new AtomicInteger();
        volatile List<String> missingIds = new ArrayList<>();
        volatile long lastSeq = -1;
        private final String lineSeparator;
        private final SimpleDateFormat DATE_FORMAT // for mbox From_ lines
                = new SimpleDateFormat("EEE MMM dd HH:mm:ss yyyy");

        // e.g. INTERNALDATE "27-Oct-2013 07:43:24 +0000"
        // for parsing INTERNALDATE
        private final SimpleDateFormat IDPARSE = new SimpleDateFormat("dd-MMM-yyyy HH:mm:ss Z");
        private final boolean printHash;
        private final boolean printMarker;
        private final boolean checkSequence;

        MboxListener(final BufferedWriter bufferedWriter, final String lineSeparator, final boolean printHash, final boolean printMarker,
                final boolean checkSequence) {
            this.lineSeparator = lineSeparator;
            this.printHash = printHash;
            this.printMarker = printMarker;
            DATE_FORMAT.setTimeZone(TimeZone.getTimeZone("GMT"));
            this.bufferedWriter = bufferedWriter;
            this.checkSequence = checkSequence;
        }

        @Override
        public boolean chunkReceived(final IMAP imap) {
            final String[] replyStrings = imap.getReplyStrings();
            Date received = parseReceivedDate(replyStrings[0]);
            String replyTo = parseReplyTo(replyStrings);
            try {
                writeHeader(received, replyTo);
                processReplyStrings(replyStrings);
            } catch (IOException e) {
                e.printStackTrace();
                throw new UncheckedIOException(e); // chunkReceived cannot throw a checked Exception
            }
            total.incrementAndGet();
            checkAndLogSequence(replyStrings[0]);
            printProgress();
            return true;
        }
        private Date parseReceivedDate(String firstLine) {
            Matcher m = PATID.matcher(firstLine);
            if (m.lookingAt()) {
                try {
                    return IDPARSE.parse(m.group(PATID_DATE_GROUP));
                } catch (ParseException e) {
                    System.err.println(e);
                }
            } else {
                System.err.println("No timestamp found in: " + firstLine + " - using current time");
            }
            return new Date();
        }
        private String parseReplyTo(String[] replyStrings) {
            String replyTo = "MAILER-DAEMON";
            for (int i = 1; i < replyStrings.length - 1; i++) {
                if (replyStrings[i].startsWith("Return-Path: ")) {
                    String[] parts = replyStrings[i].split(" ", 2);
                    replyTo = extractReplyTo(parts);
                    break;
                }
            }
            return replyTo;
        }
        private String extractReplyTo(String[] parts) {
            String replyTo = parts[1];
            if (!replyTo.equals("<>") && replyTo.startsWith("<") && replyTo.endsWith(">")) {
                replyTo = replyTo.substring(1, replyTo.length() - 1);
            }
            return replyTo;
        }
        private void writeHeader(Date received, String replyTo) throws IOException {
            bufferedWriter.append("From ")
                          .append(replyTo)
                          .append(' ')
                          .append(DATE_FORMAT.format(received))
                          .append(lineSeparator);
        }
        private void processReplyStrings(String[] replyStrings) throws IOException {
            bufferedWriter.append("X-IMAP-Response: ").append(replyStrings[0]).append(lineSeparator);
            if (printMarker) {
                System.err.println("[" + total + "] " + replyStrings[0]);
            }
            for (int i = 1; i < replyStrings.length - 1; i++) {
                String line = replyStrings[i];
                if (startsWith(line, PATFROM)) {
                    bufferedWriter.append('>');
                }
                bufferedWriter.append(line).append(lineSeparator);
            }
            String lastLine = replyStrings[replyStrings.length - 1];
            if (lastLine.length() > 1) {
                bufferedWriter.append(lastLine, 0, lastLine.length() - 1).append(lineSeparator);
            }
            bufferedWriter.append(lineSeparator);
        }
        private void checkAndLogSequence(String firstLine) {
            if (checkSequence) {
                Matcher m = PATSEQ.matcher(firstLine);
                if (m.lookingAt()) {
                    long msgSeq = Long.parseLong(m.group(PATSEQ_SEQUENCE_GROUP));
                    if (lastSeq != -1 && msgSeq - lastSeq > 1) {
                        logMissingIds(msgSeq);
                    }
                    lastSeq = msgSeq;
                }
            }
        }
        private void logMissingIds(long msgSeq) {
            for (long j = lastSeq + 1; j < msgSeq; j++) {
                missingIds.add(String.valueOf(j));
            }
            System.err.println("*** Sequence error: current=" + msgSeq + " previous=" + lastSeq + " Missing=" + (msgSeq - lastSeq - 1));
        }
        private void printProgress() {
            if (printHash) {
                System.err.print(".");
            }
        }
        public void close() throws IOException {
            if (bufferedWriter != null) {
                bufferedWriter.close();
            }
        }
    }

    private static final String CRLF = "\r\n";
    private static final String LF = "\n";
    private static final String EOL_DEFAULT = System.lineSeparator();
    private static final Pattern PATFROM = Pattern.compile(">*From "); // unescaped From_
    // e.g. * nnn (INTERNALDATE "27-Oct-2013 07:43:24 +0000" BODY[] {nn} ...)
    private static final Pattern PATID = // INTERNALDATE
        Pattern.compile(".*INTERNALDATE \"(\\d\\d-\\w{3}-\\d{4} \\d\\d:\\d\\d:\\d\\d [+-]\\d+)\"");
    private static final int PATID_DATE_GROUP = 1;
    private static final Pattern PATSEQ = Pattern.compile("\\* (\\d+) "); // Sequence number
    private static final int PATSEQ_SEQUENCE_GROUP = 1;
    // e.g. * 382 EXISTS
    private static final Pattern PATEXISTS = Pattern.compile("\\* (\\d+) EXISTS"); // Response from SELECT
    // AAAC NO [TEMPFAIL] FETCH Temporary failure on server [CODE: WBL]
    private static final Pattern PATTEMPFAIL = Pattern.compile("[A-Z]{4} NO \\[TEMPFAIL\\] FETCH .*");
    private static final int CONNECT_TIMEOUT = 10; // Seconds
    private static final int READ_TIMEOUT = 10;

    public static void main(final String[] args) throws IOException, URISyntaxException {
        int connectTimeout = CONNECT_TIMEOUT;
        int readTimeout = READ_TIMEOUT;
        int retryWaitSecs = 0;
        String eol = EOL_DEFAULT;
        boolean printHash = false;
        boolean printMarker = false;
        int argIdx = 0;

        for (argIdx = 0; argIdx < args.length; argIdx++) {
            if (args[argIdx].equals("-c")) {
                connectTimeout = Integer.parseInt(args[++argIdx]);
            } else if (args[argIdx].equals("-r")) {
                readTimeout = Integer.parseInt(args[++argIdx]);
            } else if (args[argIdx].equals("-R")) {
                retryWaitSecs = Integer.parseInt(args[++argIdx]);
            } else if (args[argIdx].equals("-LF")) {
                eol = LF;
            } else if (args[argIdx].equals("-CRLF")) {
                eol = CRLF;
            } else if (args[argIdx].equals("-.")) {
                printHash = true;
            } else if (args[argIdx].equals("-X")) {
                printMarker = true;
            } else {
                break;
            }
        }

        if (argIdx < 2) {
            printUsage();
            System.exit(1);
        }

        final String uriString = args[argIdx++];
        URI uri = parseUri(uriString);
        String file = args[argIdx++];
        String sequenceSet = argIdx < args.length ? args[argIdx++] : "1:*";
        String itemNames = parseItemNames(args, argIdx);
        boolean checkSequence = sequenceSet.matches("\\d+:(\\d+|\\*)");
        MboxListener mboxListener = setupMboxListener(file, eol, printHash, printMarker, checkSequence);
        String folder = uri.getPath().substring(1);
        validateFolderPath(folder);
        // suppress login details
        PrintCommandListener listener = createCommandListener();
        // Connect and login
        IMAPClient imap = IMAPUtils.imapLogin(uri, connectTimeout * 1000, listener);
        String maxIndexInFolder = null;
        try {
            imap.setSoTimeout(readTimeout * 1000);
            if (!imap.select(folder)) {
                throw new IOException("Could not select folder: " + folder);
            }
            maxIndexInFolder = getMaxIndexInFolder(imap);
            if (mboxListener != null) {
                imap.setChunkListener(mboxListener);
            }
            fetchMessages(imap, sequenceSet, itemNames, retryWaitSecs, mboxListener);
        } catch (final IOException ioe) {
            handleFetchError(ioe, mboxListener);
        } finally {
            finalizeProcess(mboxListener, imap);
        }
        if (mboxListener != null) {
            System.out.println("Processed " + mboxListener.total + " messages.");
        }
        if (maxIndexInFolder != null) {
            System.out.println("Folder contained " + maxIndexInFolder + " messages.");
        }
    }

    private static void printUsage() {
        System.err.println("Usage: IMAPExportMbox [-LF|-CRLF] [-c n] [-r n] [-R n] [-.] [-X]"
                + " imap[s]://user:password@host[:port]/folder/path [+|-]<mboxfile> [sequence-set] [itemnames]");
        System.err.println("\t-LF | -CRLF set end-of-line to LF or CRLF (default is the line.separator system property)");
        System.err.println("\t-c connect timeout in seconds (default 10)");
        System.err.println("\t-r read timeout in seconds (default 10)");
        System.err.println("\t-R temporary failure retry wait in seconds (default 0; i.e. disabled)");
        System.err.println("\t-. print a . for each complete message received");
        System.err.println("\t-X print the X-IMAP line for each complete message received");
        System.err.println("\tthe mboxfile is where the messages are stored; use '-' to write to standard output.");
        System.err.println("\tPrefix file name with '+' to append to the file. Prefix with '-' to allow overwrite.");
        System.err.println("\ta sequence-set is a list of numbers/number ranges e.g. 1,2,3-10,20:* - default 1:*");
        System.err.println("\titemnames are the message data item name(s) e.g. BODY.PEEK[HEADER.FIELDS (SUBJECT)]"
                + " or a macro e.g. ALL - default (INTERNALDATE BODY.PEEK[])");
    }
    private static URI parseUri(String uriString) throws URISyntaxException {
        try {
            return URI.create(uriString);
        } catch (final IllegalArgumentException e) {
            Matcher m = Pattern.compile("(imaps?://[^/]+)(/.*)").matcher(uriString);
            if (!m.matches()) {
                throw e;
            }
            URI uri = URI.create(m.group(1));
            return new URI(uri.getScheme(), uri.getAuthority(), m.group(2), null, null);
        }
    }
    private static String parseItemNames(String[] args, int argIdx) {
        if (argIdx > 3) {
            StringBuilder sb = new StringBuilder();
            sb.append("(");
            for (int i = 4; i <= args.length; i++) {
                if (i > 4) {
                    sb.append(" ");
                }
                sb.append(args[argIdx++]);
            }
            sb.append(")");
            return sb.toString();
        } else {
            return "(INTERNALDATE BODY.PEEK[])";
        }
    }
    private static MboxListener setupMboxListener(String file, String eol, boolean printHash,
    boolean printMarker, boolean checkSequence) throws IOException {
        if (file.equals("-")) {
            return null;
        } else if (file.startsWith("+")) {
            Path mboxPath = Paths.get(file.substring(1));
            System.out.println("Appending to file " + mboxPath);
            return new MboxListener(Files.newBufferedWriter(mboxPath, Charset.defaultCharset(), StandardOpenOption.CREATE,
            StandardOpenOption.APPEND), eol, printHash, printMarker, checkSequence);
        } else if (file.startsWith("-")) {
            Path mboxPath = Paths.get(file.substring(1));
            System.out.println("Writing to file " + mboxPath);
            return new MboxListener(Files.newBufferedWriter(mboxPath, Charset.defaultCharset(), StandardOpenOption.CREATE),
            eol, printHash, printMarker, checkSequence);
        } else {
            Path mboxPath = Paths.get(file);
            if (Files.exists(mboxPath) && Files.size(mboxPath) > 0) {
                throw new IOException("mailbox file: " + mboxPath + " already exists and is non-empty!");
            }
            System.out.println("Creating file " + mboxPath);
            return new MboxListener(Files.newBufferedWriter(mboxPath, Charset.defaultCharset(), StandardOpenOption.CREATE),
            eol, printHash, printMarker, checkSequence);
        }
    }
    private static void validateFolderPath(String folder) {
        if (folder == null || folder.length() < 1) {
            throw new IllegalArgumentException("Invalid folderPath: '" + folder + "'");
        }
    }
    private static PrintCommandListener createCommandListener() {
        return new PrintCommandListener(System.out, true) {
            @Override
            public void protocolReplyReceived(final ProtocolCommandEvent event) {
                if (event.getReplyCode() != IMAPReply.PARTIAL) {
                    super.protocolReplyReceived(event);
                }
            }
        };
    }
    private static String getMaxIndexInFolder(IMAPClient imap) throws IOException {
        for (final String line : imap.getReplyStrings()) {
            String maxIndexInFolder = matches(line, PATEXISTS, 1);
            if (maxIndexInFolder != null) {
                return maxIndexInFolder;
            }
        }
        return null;
    }
    private static void fetchMessages(IMAPClient imap, String sequenceSet, String itemNames, int retryWaitSecs, MboxListener mboxListener) throws IOException {
        while (true) {
            boolean ok = imap.fetch(sequenceSet, itemNames);
            if (ok || retryWaitSecs <= 0 || mboxListener == null || !sequenceSet.matches("\\d+:(\\d+|\\*)")) {
                break;
            }
            String replyString = imap.getReplyString();
            if (!startsWith(replyString, PATTEMPFAIL)) {
                throw new IOException("FETCH " + sequenceSet + " " + itemNames + " failed with " + replyString);
            }
            System.err.println("Temporary error detected, will retry in " + retryWaitSecs + " seconds");
            sequenceSet = mboxListener.lastSeq + 1 + ":*";
            try {
                Thread.sleep(retryWaitSecs * 1000);
            } catch (final InterruptedException e) {
                // ignored
            }
        }
    }
    private static void handleFetchError(IOException ioe, MboxListener mboxListener) {
        String count = mboxListener == null ? "0" : String.valueOf(mboxListener.total);
        System.err.println("Could not fetch messages. Total processed messages: " + count);
        ioe.printStackTrace();
    }
    private static void finalizeProcess(MboxListener mboxListener, IMAPClient imap) {
        if (mboxListener != null) {
            try {
                mboxListener.close();
            } catch (final IOException e) {
                System.err.println("Failed to close the mbox listener: " + e.getMessage());
            }
        }
        if (imap != null) {
            try {
                imap.logout();
                imap.disconnect();
            } catch (final IOException e) {
                System.err.println("Failed to logout and disconnect: " + e.getMessage());
            }
        }
    }
    private static String matches(final String input, final Pattern pat, final int index) {
        final Matcher m = pat.matcher(input);
        if (m.lookingAt()) {
            return m.group(index);
        }
        return null;
    }
    private static boolean startsWith(final String input, final Pattern pat) {
        final Matcher m = pat.matcher(input);
        return m.lookingAt();
    }
}