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

import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.SocketException;
import java.net.UnknownHostException;

import org.apache.commons.net.io.FromNetASCIIOutputStream;
import org.apache.commons.net.io.ToNetASCIIInputStream;

/**
 * The TFTPClient class encapsulates all the aspects of the TFTP protocol
 * necessary to receive and send files through TFTP. It is derived from the
 * {@link org.apache.commons.net.tftp.TFTP} because it is more convenient than
 * using aggregation, and as a result exposes the same set of methods to allow
 * you
 * to deal with the TFTP protocol directly. However, almost every user should
 * only be concerend with the the
 * {@link org.apache.commons.net.DatagramSocketClient#open open() },
 * {@link org.apache.commons.net.DatagramSocketClient#close close() },
 * {@link #sendFile
 * sendFile() }, and {@link #receiveFile receiveFile() } methods. Additionally,
 * the {@link #setMaxTimeouts setMaxTimeouts() } and
 * {@link org.apache.commons.net.DatagramSocketClient#setDefaultTimeout
 * setDefaultTimeout() } methods may be of importance for performance tuning.
 * <p>
 * Details regarding the TFTP protocol and the format of TFTP packets can be
 * found in RFC 783. But the point of these classes is to keep you from having
 * to
 * worry about the internals.
 *
 *
 * @see TFTP
 * @see TFTPPacket
 * @see TFTPPacketException
 */

public class TFTPClient extends TFTP {
    /**
     * The default number of times a {@code receive} attempt is allowed to timeout
     * before ending attempts to retry the {@code receive} and failing.
     * The default is 5 timeouts.
     */
    public static final int DEFAULT_MAX_TIMEOUTS = 5;

    /** The maximum number of timeouts allowed before failing. */
    private int maxTimeouts;

    /** The number of bytes received in the ongoing download. */
    private long totalBytesReceived;

    /** The number of bytes sent in the ongoing upload. */
    private long totalBytesSent;

    /**
     * Creates a TFTPClient instance with a default timeout of DEFAULT_TIMEOUT,
     * maximum timeouts value of DEFAULT_MAX_TIMEOUTS, a null socket, and buffered
     * operations disabled.
     */
    public TFTPClient() {
        maxTimeouts = DEFAULT_MAX_TIMEOUTS;
    }

    /**
     * Returns the maximum number of times a {@code receive} attempt is allowed to
     * timeout before ending attempts to retry the {@code receive} and failing.
     *
     * @return The maximum number of timeouts allowed.
     */
    public int getMaxTimeouts() {
        return maxTimeouts;
    }

    /**
     * @return The number of bytes received in the ongoing download
     */
    public long getTotalBytesReceived() {
        return totalBytesReceived;
    }

    /**
     * @return The number of bytes sent in the ongoing download
     */
    public long getTotalBytesSent() {
        return totalBytesSent;
    }

    /**
     * Same as calling receiveFile(fileName, mode, output, host, TFTP.DEFAULT_PORT).
     *
     * @param fileName The name of the file to receive.
     * @param mode     The TFTP mode of the transfer (one of the MODE constants).
     * @param output   The OutputStream to which the file should be written.
     * @param host     The remote host serving the file.
     * @return number of bytes read
     * @throws IOException If an I/O error occurs. The nature of the error will be
     *                     reported in the message.
     */
    public int receiveFile(final String fileName, final int mode, final OutputStream output, final InetAddress host)
            throws IOException {
        return receiveFile(fileName, mode, output, host, DEFAULT_PORT);
    }

    /**
     * Requests a named file from a remote host, writes the file to an OutputStream,
     * closes the connection, and returns the number of bytes read. A local UDP
     * socket must first be created by
     * {@link org.apache.commons.net.DatagramSocketClient#open open()} before
     * invoking this method. This method will not close
     * the OutputStream containing the file; you must close it after the method
     * invocation.
     *
     * @param fileName The name of the file to receive.
     * @param mode     The TFTP mode of the transfer (one of the MODE constants).
     * @param output   The OutputStream to which the file should be written.
     * @param host     The remote host serving the file.
     * @param port     The port number of the remote TFTP server.
     * @return number of bytes read
     * @throws IOException If an I/O error occurs. The nature of the error will be
     *                     reported in the message.
     */
    public int receiveFile(final String fileName, final int mode, OutputStream output, InetAddress host, final int port)
            throws IOException {
        int bytesRead = 0;
        int lastBlock = 0;
        int block = 1;
        int dataLength = 0;

        totalBytesReceived = 0;

        // Imposta la modalità ASCII se necessario
        if (mode == ASCII_MODE) {
            output = new FromNetASCIIOutputStream(output);
        }

        TFTPPacket sent = new TFTPReadRequestPacket(host, port, fileName, mode);
        TFTPAckPacket ack = new TFTPAckPacket(host, port, 0);

        beginBufferedOps();

        boolean justStarted = true;
        try {
            do {
                // Invia la richiesta di lettura
                bufferedSend(sent);

                boolean wantReply = true;
                int timeouts = 0;

                while (wantReply) {
                    try {
                        TFTPPacket received = bufferedReceive();
                        int recdPort = received.getPort();
                        InetAddress recdAddress = received.getAddress();

                        if (justStarted) {
                            justStarted = false;
                            if (recdPort == port) {
                                sendErrorPacket(recdAddress, recdPort, "INCORRECT SOURCE PORT");
                                throw new IOException("Incorrect source port (" + recdPort + ") in request reply.");
                            }
                            ack.setPort(recdPort);
                            if (!host.equals(recdAddress)) {
                                host = recdAddress;
                                ack.setAddress(host);
                                sent.setAddress(host);
                            }
                        }

                        if (host.equals(recdAddress) && recdPort == ack.getPort()) {
                            // Gestisce i vari tipi di pacchetti
                            switch (received.getType()) {
                                case TFTPPacket.ERROR:
                                    TFTPErrorPacket error = (TFTPErrorPacket) received;
                                    throw new IOException(
                                            "Error code " + error.getError() + " received: " + error.getMessage());
                                case TFTPPacket.DATA:
                                    TFTPDataPacket data = (TFTPDataPacket) received;
                                    dataLength = data.getDataLength();
                                    lastBlock = data.getBlockNumber();

                                    if (lastBlock == block) { // Se il numero del blocco è corretto
                                        writeData(output, data, dataLength);
                                        block = (block + 1) % 65536; // Incrementa e avvolge il numero del blocco
                                        wantReply = false; // Uscita dal ciclo, dato che è stato ricevuto il blocco
                                    } else { // Numero di blocco non previsto
                                        discardPackets();
                                        if (lastBlock == (block == 0 ? 65535 : block - 1)) {
                                            wantReply = false; // Resendi l'ack dell'ultimo blocco
                                        }
                                    }
                                    break;
                                default:
                                    throw new IOException(
                                            "Received unexpected packet type (" + received.getType() + ")");
                            }
                        } else {
                            // Indirizzo o porta non corretti
                            sendErrorPacket(recdAddress, recdPort, "Unexpected host or port.");
                        }
                    } catch (SocketException | InterruptedIOException e) {
                        if (++timeouts >= maxTimeouts) {
                            throw new IOException("Connection timed out.");
                        }
                    } catch (TFTPPacketException e) {
                        throw new IOException("Bad packet: " + e.getMessage());
                    }
                }

                ack.setBlockNumber(lastBlock);
                sent = ack;
                bytesRead += dataLength;
                totalBytesReceived += dataLength;
            } while (dataLength == TFTPPacket.SEGMENT_SIZE); // Continua finché non ricevi l'EOF

            bufferedSend(sent); // Invia l'ack finale

        } finally {
            endBufferedOps();
        }
        return bytesRead;
    }

    private void writeData(OutputStream output, TFTPDataPacket data, int dataLength) throws IOException {
        try {
            output.write(data.getData(), data.getDataOffset(), dataLength);
        } catch (IOException e) {
            throw new IOException("Error writing data to output stream: " + e.getMessage(), e);
        }
    }

    private void sendErrorPacket(InetAddress recdAddress, int recdPort, String errorMessage) throws IOException {
        TFTPErrorPacket error = new TFTPErrorPacket(recdAddress, recdPort, TFTPErrorPacket.UNKNOWN_TID, errorMessage);
        bufferedSend(error);
    }

    /**
     * Same as calling receiveFile(fileName, mode, output, hostname,
     * TFTP.DEFAULT_PORT).
     *
     * @param fileName The name of the file to receive.
     * @param mode     The TFTP mode of the transfer (one of the MODE constants).
     * @param output   The OutputStream to which the file should be written.
     * @param hostname The name of the remote host serving the file.
     * @return number of bytes read
     * @throws IOException          If an I/O error occurs. The nature of the error
     *                              will be reported in the message.
     * @throws UnknownHostException If the hostname cannot be resolved.
     */
    public int receiveFile(final String fileName, final int mode, final OutputStream output, final String hostname)
            throws UnknownHostException, IOException {
        return receiveFile(fileName, mode, output, InetAddress.getByName(hostname), DEFAULT_PORT);
    }

    /**
     * Requests a named file from a remote host, writes the file to an OutputStream,
     * closes the connection, and returns the number of bytes read. A local UDP
     * socket must first be created by
     * {@link org.apache.commons.net.DatagramSocketClient#open open()} before
     * invoking this method. This method will not close
     * the OutputStream containing the file; you must close it after the method
     * invocation.
     *
     * @param fileName The name of the file to receive.
     * @param mode     The TFTP mode of the transfer (one of the MODE constants).
     * @param output   The OutputStream to which the file should be written.
     * @param hostname The name of the remote host serving the file.
     * @param port     The port number of the remote TFTP server.
     * @return number of bytes read
     * @throws IOException          If an I/O error occurs. The nature of the error
     *                              will be reported in the message.
     * @throws UnknownHostException If the hostname cannot be resolved.
     */
    public int receiveFile(final String fileName, final int mode, final OutputStream output, final String hostname,
            final int port)
            throws UnknownHostException, IOException {
        return receiveFile(fileName, mode, output, InetAddress.getByName(hostname), port);
    }

    /**
     * Same as calling sendFile(fileName, mode, input, host, TFTP.DEFAULT_PORT).
     *
     * @param fileName The name the remote server should use when creating the file
     *                 on its file system.
     * @param mode     The TFTP mode of the transfer (one of the MODE constants).
     * @param input    the input stream containing the data to be sent
     * @param host     The name of the remote host receiving the file.
     * @throws IOException          If an I/O error occurs. The nature of the error
     *                              will be reported in the message.
     * @throws UnknownHostException If the hostname cannot be resolved.
     */
    public void sendFile(final String fileName, final int mode, final InputStream input, final InetAddress host)
            throws IOException {
        sendFile(fileName, mode, input, host, DEFAULT_PORT);
    }

    /**
     * Requests to send a file to a remote host, reads the file from an InputStream,
     * sends the file to the remote host, and closes the connection. A local UDP
     * socket must first be created by
     * {@link org.apache.commons.net.DatagramSocketClient#open open()} before
     * invoking this method. This method will not close
     * the InputStream containing the file; you must close it after the method
     * invocation.
     *
     * @param fileName The name the remote server should use when creating the file
     *                 on its file system.
     * @param mode     The TFTP mode of the transfer (one of the MODE constants).
     * @param input    the input stream containing the data to be sent
     * @param host     The remote host receiving the file.
     * @param port     The port number of the remote TFTP server.
     * @throws IOException If an I/O error occurs. The nature of the error will be
     *                     reported in the message.
     */
    public void sendFile(final String fileName, final int mode, InputStream input, InetAddress host, final int port)
            throws IOException {
        int block = 0;
        int hostPort = 0;
        boolean justStarted = true;
        boolean lastAckWait = false;

        totalBytesSent = 0L;

        if (mode == ASCII_MODE) {
            input = new ToNetASCIIInputStream(input); // Conversione in modalità ASCII
        }

        TFTPPacket sent = new TFTPWriteRequestPacket(host, port, fileName, mode);
        final TFTPDataPacket data = new TFTPDataPacket(host, port, 0, sendBuffer, 4, 0);

        beginBufferedOps();

        try {
            do {
                bufferedSend(sent); // Invia richiesta di scrittura
                boolean wantReply = true;
                int timeouts = 0;

                // Ricevi risposte fino a ottenere l'ACK atteso
                while (wantReply) {
                    try {
                        final TFTPPacket received = bufferedReceive();
                        final InetAddress recdAddress = received.getAddress();
                        final int recdPort = received.getPort();

                        // Gestisce l'avvio della comunicazione
                        if (justStarted) {
                            justStarted = false;
                            if (recdPort == port) {
                                sendErrorPacket(recdAddress, recdPort, "INCORRECT SOURCE PORT");
                                throw new IOException("Incorrect source port (" + recdPort + ") in request reply.");
                            }
                            hostPort = recdPort;
                            data.setPort(hostPort);
                            if (!host.equals(recdAddress)) {
                                host = recdAddress;
                                data.setAddress(host);
                                sent.setAddress(host);
                            }
                        }

                        // Gestione pacchetti con controllo di porta e indirizzo
                        if (host.equals(recdAddress) && recdPort == hostPort) {
                            switch (received.getType()) {
                                case TFTPPacket.ACKNOWLEDGEMENT:
                                    TFTPAckPacket ack = (TFTPAckPacket) received;
                                    if (ack.getBlockNumber() == block) {
                                        block = (block + 1) % 65536; // Incrementa e gestisce il wrapping del blocco
                                        wantReply = false; // ACK ricevuto correttamente
                                    } else {
                                        discardPackets(); // Discard pacchetti duplicati o fuori sequenza
                                    }
                                    break;

                                case TFTPPacket.ERROR:
                                    TFTPErrorPacket error = (TFTPErrorPacket) received;
                                    throw new IOException(
                                            "Error code " + error.getError() + " received: " + error.getMessage());

                                default:
                                    throw new IOException("Received unexpected packet type.");
                            }
                        } else {
                            sendErrorPacket(recdAddress, recdPort, "Unexpected host or port.");
                        }
                    } catch (final SocketException | InterruptedIOException e) {
                        if (++timeouts >= maxTimeouts) {
                            throw new IOException("Connection timed out.");
                        }
                    } catch (final TFTPPacketException e) {
                        throw new IOException("Bad packet: " + e.getMessage());
                    }
                }

                if (lastAckWait) {
                    break; // Ultimo pacchetto inviato e ACK ricevuto
                }

                // Prepara il pacchetto dati per inviarlo
                int dataLength = TFTPPacket.SEGMENT_SIZE;
                int offset = 4;
                int totalThisPacket = 0;
                int bytesRead;

                while (dataLength > 0 && (bytesRead = input.read(sendBuffer, offset, dataLength)) > 0) {
                    offset += bytesRead;
                    dataLength -= bytesRead;
                    totalThisPacket += bytesRead;
                }

                if (totalThisPacket < TFTPPacket.SEGMENT_SIZE) {
                    lastAckWait = true; // Flag per ultimo pacchetto
                }

                data.setBlockNumber(block);
                data.setData(sendBuffer, 4, totalThisPacket);
                sent = data; // Imposta il pacchetto come quello corrente
                totalBytesSent += totalThisPacket;

            } while (true); // Ripete fino al completamento della trasmissione

        } finally {
            endBufferedOps();
        }
    }

    /**
     * Same as calling sendFile(fileName, mode, input, hostname, TFTP.DEFAULT_PORT).
     *
     * @param fileName The name the remote server should use when creating the file
     *                 on its file system.
     * @param mode     The TFTP mode of the transfer (one of the MODE constants).
     * @param input    the input stream containing the data to be sent
     * @param hostname The name of the remote host receiving the file.
     * @throws IOException          If an I/O error occurs. The nature of the error
     *                              will be reported in the message.
     * @throws UnknownHostException If the hostname cannot be resolved.
     */
    public void sendFile(final String fileName, final int mode, final InputStream input, final String hostname)
            throws UnknownHostException, IOException {
        sendFile(fileName, mode, input, InetAddress.getByName(hostname), DEFAULT_PORT);
    }

    /**
     * Requests to send a file to a remote host, reads the file from an InputStream,
     * sends the file to the remote host, and closes the connection. A local UDP
     * socket must first be created by
     * {@link org.apache.commons.net.DatagramSocketClient#open open()} before
     * invoking this method. This method will not close
     * the InputStream containing the file; you must close it after the method
     * invocation.
     *
     * @param fileName The name the remote server should use when creating the file
     *                 on its file system.
     * @param mode     The TFTP mode of the transfer (one of the MODE constants).
     * @param input    the input stream containing the data to be sent
     * @param hostname The name of the remote host receiving the file.
     * @param port     The port number of the remote TFTP server.
     * @throws IOException          If an I/O error occurs. The nature of the error
     *                              will be reported in the message.
     * @throws UnknownHostException If the hostname cannot be resolved.
     */
    public void sendFile(final String fileName, final int mode, final InputStream input, final String hostname,
            final int port)
            throws UnknownHostException, IOException {
        sendFile(fileName, mode, input, InetAddress.getByName(hostname), port);
    }

    /**
     * Sets the maximum number of times a {@code receive} attempt is allowed to
     * timeout during a receiveFile() or sendFile() operation before ending
     * attempts to retry the {@code receive} and failing. The default is
     * DEFAULT_MAX_TIMEOUTS.
     *
     * @param numTimeouts The maximum number of timeouts to allow. Values less than
     *                    1 should not be used, but if they are, they are treated as
     *                    1.
     */
    public void setMaxTimeouts(final int numTimeouts) {
        maxTimeouts = Math.max(numTimeouts, 1);
    }
}
