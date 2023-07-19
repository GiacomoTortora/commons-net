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
package org.apache.commons.net.tftp;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;

import org.apache.commons.net.tftp.TFTPServer.ServerMode;
import org.junit.jupiter.api.Test;

/**
 * Basic tests to ensure that the TFTP Server is honoring its read/write mode, and preventing files from being read or written from outside of the assigned
 * roots.
 */
public class TFTPServerPathTest {

    private static final int SERVER_PORT = 6901;
    String filePrefix = "tftp-";
    File serverDirectory = new File(System.getProperty("java.io.tmpdir"));

    @Test
    public void testReadOnly() throws IOException {
        // Start a read-only server
        try (TFTPServer tftpS = new TFTPServer(serverDirectory, serverDirectory, SERVER_PORT, ServerMode.GET_ONLY, null, null)) {

            // Create our TFTP instance to handle the file transfer.
            final TFTPClient tftp = new TFTPClient();
            tftp.open();
            tftp.setSoTimeout(2000);

            // make a file to work with.
            final File file = new File(serverDirectory, filePrefix + "source.txt");
            file.createNewFile();

            // Read the file from the tftp server.
            final File out = new File(serverDirectory, filePrefix + "out");

            // cleanup old failed runs
            out.delete();
            assertFalse(out.exists(), "Couldn't clear output location");

            try (final FileOutputStream output = new FileOutputStream(out)) {
                tftp.receiveFile(file.getName(), TFTP.BINARY_MODE, output, "localhost", SERVER_PORT);
            }

            assertTrue(out.exists(), "file not created");

            out.delete();

            try (final FileInputStream fis = new FileInputStream(file)) {
                tftp.sendFile(out.getName(), TFTP.BINARY_MODE, fis, "localhost", SERVER_PORT);
                fail("Server allowed write");
            } catch (final IOException e) {
                // expected path
            }
            file.delete();
            tftp.close();
        }
    }

    @Test
    public void testWriteOnly() throws IOException {
        // Start a write-only server
        try (TFTPServer tftpS = new TFTPServer(serverDirectory, serverDirectory, SERVER_PORT, ServerMode.PUT_ONLY, null, null)) {

            // Create our TFTP instance to handle the file transfer.
            final TFTPClient tftp = new TFTPClient();
            tftp.open();
            tftp.setSoTimeout(2000);

            // make a file to work with.
            final File file = new File(serverDirectory, filePrefix + "source.txt");
            file.createNewFile();

            final File out = new File(serverDirectory, filePrefix + "out");

            // cleanup old failed runs
            out.delete();
            assertFalse(out.exists(), "Couldn't clear output location");

            try (final FileOutputStream output = new FileOutputStream(out)) {
                tftp.receiveFile(file.getName(), TFTP.BINARY_MODE, output, "localhost", SERVER_PORT);
                fail("Server allowed read");
            } catch (final IOException e) {
                // expected path
            }
            out.delete();

            try (final FileInputStream fis = new FileInputStream(file)) {
                tftp.sendFile(out.getName(), TFTP.BINARY_MODE, fis, "localhost", SERVER_PORT);
            }

            assertTrue(out.exists(), "file not created");

            // cleanup
            file.delete();
            out.delete();
            tftp.close();
        }
    }

    @Test
    public void testWriteOutsideHome() throws IOException {
        // Start a server
        try (TFTPServer tftpS = new TFTPServer(serverDirectory, serverDirectory, SERVER_PORT, ServerMode.GET_AND_PUT, null, null)) {

            // Create our TFTP instance to handle the file transfer.
            final TFTPClient tftp = new TFTPClient();
            tftp.open();

            final File file = new File(serverDirectory, filePrefix + "source.txt");
            file.createNewFile();

            assertFalse(new File(serverDirectory, "../foo").exists(), "test construction error");

            try (final FileInputStream fis = new FileInputStream(file)) {
                tftp.sendFile("../foo", TFTP.BINARY_MODE, fis, "localhost", SERVER_PORT);
                fail("Server allowed write!");
            } catch (final IOException e) {
                // expected path
            }

            assertFalse(new File(serverDirectory, "../foo").exists(), "file created when it should not have been");

            // cleanup
            file.delete();
            tftp.close();
        }
    }

}
