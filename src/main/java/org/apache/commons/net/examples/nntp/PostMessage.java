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

package org.apache.commons.net.examples.nntp;

import java.io.BufferedReader;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Paths;

import org.apache.commons.net.examples.PrintCommandListeners;
import org.apache.commons.net.io.Util;
import org.apache.commons.net.nntp.NNTPClient;
import org.apache.commons.net.nntp.NNTPReply;
import org.apache.commons.net.nntp.SimpleNNTPHeader;

/**
 * This is an example program using the NNTP package to post an article to the specified newsgroup(s). It prompts you for header information and a file name to
 * post.
 */

public final class PostMessage {
    static String ERR = "Error I/O exception: ";
    public static void main(final String[] args) {
        if (args.length < 1) {
            System.err.println("Usage: post newsserver");
            System.exit(1);
        }

        final String server = args[0];
        final BufferedReader stdin = new BufferedReader(new InputStreamReader(System.in, Charset.defaultCharset()));
        final SimpleNNTPHeader header = createHeader(stdin);
        String fileName = getFileName(stdin);

        try (Reader fileReader = Files.newBufferedReader(Paths.get(fileName), Charset.defaultCharset())) {
            postArticle(server, header, fileReader);
        } catch (final FileNotFoundException e) {
            System.err.println("File not found. " + e.getMessage());
            System.exit(1);
        } catch (final IOException e) {
            System.err.println(ERR + e.getMessage());
            System.exit(1);
        }
    }

    private static SimpleNNTPHeader createHeader(BufferedReader stdin) {
        try {
            System.out.print("From: ");
            String from = stdin.readLine();

            System.out.print("Subject: ");
            String subject = stdin.readLine();

            SimpleNNTPHeader header = new SimpleNNTPHeader(from, subject);

            addNewsgroups(stdin, header);

            System.out.print("Organization: ");
            String organization = stdin.readLine();
            if (organization != null && !organization.isEmpty()) {
                header.addHeaderField("Organization", organization);
            }

            System.out.print("References: ");
            String references = stdin.readLine();
            if (references != null && !references.isEmpty()) {
                header.addHeaderField("References", references);
            }

            header.addHeaderField("X-Newsreader", "NetComponents");
            return header;
        } catch (IOException e) {
            System.err.println(ERR + e.getMessage());
            System.exit(1);
        }
        return null; // This won't be reached if exceptions are handled.
    }

    private static void addNewsgroups(BufferedReader stdin, SimpleNNTPHeader header) throws IOException {
        System.out.print("Newsgroup: ");
        String newsgroup = stdin.readLine();
        header.addNewsgroup(newsgroup);

        while (true) {
            System.out.print("Additional Newsgroup <Hit enter to end>: ");
            newsgroup = stdin.readLine();
            if (newsgroup == null || newsgroup.trim().isEmpty()) {
                break;
            }
            header.addNewsgroup(newsgroup.trim());
        }
    }

    private static String getFileName(BufferedReader stdin) {
        System.out.print("Filename: ");
        System.out.flush();
        try {
            return stdin.readLine();
        } catch (IOException e) {
            System.err.println(ERR + e.getMessage());
            System.exit(1);
            return null; // This won't be reached if exceptions are handled.
        }
    }

    private static void postArticle(String server, SimpleNNTPHeader header, Reader fileReader) {
        NNTPClient client = new NNTPClient();
        client.addProtocolCommandListener(PrintCommandListeners.sysOutPrintCommandListener());

        try {
            client.connect(server);

            if (!NNTPReply.isPositiveCompletion(client.getReplyCode())) {
                client.disconnect();
                System.err.println("NNTP server refused connection.");
                System.exit(1);
            }

            if (client.isAllowedToPost()) {
                try (Writer writer = client.postArticle()) {
                    if (writer != null) {
                        writer.write(header.toString());
                        Util.copyReader(fileReader, writer);
                        client.completePendingCommand();
                    }
                }
            }

            client.logout();
            client.disconnect();
        } catch (IOException e) {
            e.printStackTrace();
            System.exit(1);
        }
    }
}