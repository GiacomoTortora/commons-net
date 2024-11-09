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

package org.apache.commons.net.telnet;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;

final class TelnetInputStream extends BufferedInputStream implements Runnable {
    /** End of file has been reached */
    private static final int EOF = -1;

    /** Read would block */
    private static final int WOULD_BLOCK = -2;

    // TODO should these be private enums?
    static final int STATE_DATA = 0, STATE_IAC = 1, STATE_WILL = 2, STATE_WONT = 3, STATE_DO = 4, STATE_DONT = 5,
            STATE_SB = 6, STATE_SE = 7, STATE_CR = 8,
            STATE_IAC_SB = 9;

    private boolean hasReachedEOF; // @GuardedBy("queue")
    private volatile boolean isClosed;
    private boolean readIsWaiting;
    private int receiveState, queueHead, queueTail, bytesAvailable;
    private final int[] queue;
    private final TelnetClient client;
    private final Thread thread;
    private IOException ioException;

    /* TERMINAL-TYPE option (start) */
    private final int suboption[];
    private int suboptionCount;
    /* TERMINAL-TYPE option (end) */

    private volatile boolean threaded;

    TelnetInputStream(final InputStream input, final TelnetClient client) {
        this(input, client, true);
    }

    TelnetInputStream(final InputStream input, final TelnetClient client, final boolean readerThread) {
        super(input);
        this.client = client;
        this.receiveState = STATE_DATA;
        this.isClosed = true;
        this.hasReachedEOF = false;
        // Make it 2049, because when full, one slot will go unused, and we
        // want a 2048 byte buffer just to have a round number (base 2 that is)
        this.queue = new int[2049];
        this.queueHead = 0;
        this.queueTail = 0;
        this.suboption = new int[client.maxSubnegotiationLength];
        this.bytesAvailable = 0;
        this.ioException = null;
        this.readIsWaiting = false;
        this.threaded = false;
        if (readerThread) {
            this.thread = new Thread(this);
        } else {
            this.thread = null;
        }
    }

    @Override
    public int available() throws IOException {
        // Critical section because run() may change bytesAvailable
        synchronized (queue) {
            if (threaded) { // Must not call super.available when running threaded: NET-466
                return bytesAvailable;
            }
            return bytesAvailable + super.available();
        }
    }

    // Cannot be synchronized. Will cause deadlock if run() is blocked
    // in read because BufferedInputStream read() is synchronized.
    @Override
    public void close() throws IOException {
        // Completely disregard the fact thread may still be running.
        // We can't afford to block on this close by waiting for
        // thread to terminate because few if any JVM's will actually
        // interrupt a system read() from the interrupt() method.
        super.close();

        synchronized (queue) {
            hasReachedEOF = true;
            isClosed = true;

            if (thread != null && thread.isAlive()) {
                thread.interrupt();
            }

            queue.notifyAll();
        }

    }

    /** Returns false. Mark is not supported. */
    @Override
    public boolean markSupported() {
        return false;
    }

    // synchronized(client) critical sections are to protect against
    // TelnetOutputStream writing through the Telnet client at same time
    // as a processDo/Will/etc. command invoked from TelnetInputStream
    // tries to write. Returns true if buffer was previously empty.
    private boolean processChar(final int ch) throws InterruptedException {
        // Critical section because we're altering bytesAvailable,
        // queueTail, and the contents of _queue.
        final boolean bufferWasEmpty;
        synchronized (queue) {
            bufferWasEmpty = bytesAvailable == 0;
            while (bytesAvailable >= queue.length - 1) {
                // The queue is full. We need to wait before adding any more data to it.
                // Hopefully the stream owner
                // will consume some data soon!
                if (!threaded) {
                    // We've been asked to add another character to the queue, but it is already
                    // full and there's
                    // no other thread to drain it. This should not have happened!
                    throw new IllegalStateException("Queue is full! Cannot process another character.");
                }
                queue.notify();
                try {
                    queue.wait();
                } catch (final InterruptedException e) {
                    throw e;
                }
            }

            // Need to do this in case we're not full, but block on a read
            if (readIsWaiting && threaded) {
                queue.notify();
            }

            queue[queueTail] = ch;
            ++bytesAvailable;

            if (++queueTail >= queue.length) {
                queueTail = 0;
            }
        }
        return bufferWasEmpty;
    }

    @Override
    public int read() throws IOException {
        synchronized (queue) {
            // Verifica se è presente un'eccezione di I/O
            if (ioException != null) {
                final IOException e = ioException;
                ioException = null;
                throw e;
            }

            switch (bytesAvailable) {
                case 0:
                    return handleQueueEmpty();

                default:
                    return handleQueueNotEmpty();
            }
        }
    }

    private int handleQueueEmpty() throws IOException {
        if (hasReachedEOF) {
            return EOF;
        }

        if (threaded) {
            return handleThreadedQueue();
        } else {
            return handleNonThreadedQueue();
        }
    }

    private int handleThreadedQueue() throws IOException {
        synchronized (queue) {
            queue.notify();
            try {
                readIsWaiting = true;
                queue.wait();
                readIsWaiting = false;
            } catch (InterruptedException e) {
                // Ripristina lo stato di interruzione del thread
                Thread.currentThread().interrupt();
                // Lancia una nuova eccezione per segnalare un'interruzione fatale durante la
                // lettura
                throw new InterruptedIOException("Fatal thread interruption during read.");
            }
        }
        return read();
    }

    private int handleNonThreadedQueue() throws IOException {
        readIsWaiting = true;
        int ch;
        boolean mayBlock = true;

        do {
            ch = tryReading(mayBlock);
            if (ch < 0 && ch != WOULD_BLOCK) {
                return ch; // EOF
            }

            if (ch != WOULD_BLOCK) {
                try {
                    processChar(ch); // Aggiungiamo il try-catch per gestire InterruptedException
                } catch (InterruptedException e) {
                    if (isClosed) {
                        return EOF;
                    }
                    // Se InterruptedException viene lanciata, può essere trattata come
                    // n'eccezione
                    // o riprendere la lettura.
                    Thread.currentThread().interrupt(); // Impostiamo il flag di interruzione
                    return EOF; // Restituiamo EOF o possiamo trattarlo diversamente
                }
            }

            mayBlock = false; // subsequent reads should not block

        } while (super.available() > 0 && bytesAvailable < queue.length - 1);

        readIsWaiting = false;
        return read();
    }

    private int tryReading(boolean mayBlock) throws IOException {
        try {
            return read(mayBlock);
        } catch (InterruptedIOException e) {
            synchronized (queue) {
                ioException = e;
                queue.notifyAll();
                try {
                    queue.wait(100);
                } catch (InterruptedException interrupted) {
                    // Ripristina lo stato di interruzione del thread
                    Thread.currentThread().interrupt();
                }
            }
            return EOF;
        }
    }

    private int handleQueueNotEmpty() {
        final int ch = queue[queueHead];

        if (++queueHead >= queue.length) {
            queueHead = 0;
        }

        --bytesAvailable;

        if (bytesAvailable == 0 && threaded) {
            synchronized (queue) {
                queue.notify();
            }
        }

        return ch;
    }

    // synchronized(client) critical sections are to protect against
    // TelnetOutputStream writing through the Telnet client at same time
    // as a processDo/Will/etc. command invoked from TelnetInputStream
    // tries to write.
    /**
     * Gets the next byte of data. IAC commands are processed internally and do not
     * return data.
     *
     * @param mayBlock true if method is allowed to block
     * @return the next byte of data, or -1 (EOF) if end of stread reached, or -2
     *         (WOULD_BLOCK) if mayBlock is false and there is no data available
     */
    private int read(final boolean mayBlock) throws IOException {
        int ch = 0;
        boolean continueLoop = true;

        while (continueLoop) {
            if (!mayBlock && super.available() == 0) {
                return WOULD_BLOCK;
            }

            if ((ch = super.read()) < 0) {
                return EOF;
            }

            ch &= 0xff;

            synchronized (client) {
                client.processAYTResponse();
            }

            client.spyRead(ch);

            switch (receiveState) {
                case STATE_CR:
                    handleStateCR(ch);
                    break;

                case STATE_DATA:
                    continueLoop = handleStateData(ch);
                    break;

                case STATE_IAC:
                    continueLoop = handleStateIAC(ch);
                    break;

                case STATE_WILL:
                    handleStateWill(ch);
                    break;

                case STATE_WONT:
                    handleStateWont(ch);
                    break;

                case STATE_DO:
                    handleStateDo(ch);
                    break;

                case STATE_DONT:
                    handleStateDont(ch);
                    break;

                case STATE_SB:
                    handleStateSB(ch);
                    break;

                case STATE_IAC_SB:
                    handleStateIACSb(ch);
                    break;

                default:
                    continueLoop = false;
                    break;
            }
        }

        return ch;
    }

    // Funzioni helper per semplificare la logica dello switch principale

    private void handleStateCR(int ch) {
        if (ch == '\0') {
            return;
        }
        receiveState = STATE_DATA;
    }

    private boolean handleStateData(int ch) {
        switch (ch) {
            case TelnetCommand.IAC:
                receiveState = STATE_IAC;
                return true;
            case '\r':
                synchronized (client) {
                    receiveState = client.requestedDont(TelnetOption.BINARY) ? STATE_CR : STATE_DATA;
                }
                return true;
            default:
                receiveState = STATE_DATA;
                return false;
        }
    }

    private boolean handleStateIAC(int ch) {
        switch (ch) {
            case TelnetCommand.WILL:
                receiveState = STATE_WILL;
                return true;
            case TelnetCommand.WONT:
                receiveState = STATE_WONT;
                return true;
            case TelnetCommand.DO:
                receiveState = STATE_DO;
                return true;
            case TelnetCommand.DONT:
                receiveState = STATE_DONT;
                return true;
            case TelnetCommand.SB:
                suboptionCount = 0;
                receiveState = STATE_SB;
                return true;
            case TelnetCommand.IAC:
                receiveState = STATE_DATA;
                return false;
            case TelnetCommand.SE:
                receiveState = STATE_DATA;
                return true;
            default:
                receiveState = STATE_DATA;
                client.processCommand(ch);
                return true;
        }
    }

    private void handleStateWill(int ch) throws IOException {
        synchronized (client) {
            client.processWill(ch);
            client.flushOutputStream();
        }
        receiveState = STATE_DATA;
    }

    private void handleStateWont(int ch) throws IOException {
        synchronized (client) {
            client.processWont(ch);
            client.flushOutputStream();
        }
        receiveState = STATE_DATA;
    }

    private void handleStateDo(int ch) throws IOException {
        synchronized (client) {
            client.processDo(ch);
            client.flushOutputStream();
        }
        receiveState = STATE_DATA;
    }

    private void handleStateDont(int ch) throws IOException {
        synchronized (client) {
            client.processDont(ch);
            client.flushOutputStream();
        }
        receiveState = STATE_DATA;
    }

    private void handleStateSB(int ch) {
        if (ch == TelnetCommand.IAC) {
            receiveState = STATE_IAC_SB;
        } else if (suboptionCount < suboption.length) {
            suboption[suboptionCount++] = ch;
        }
    }

    private void handleStateIACSb(int ch) throws IOException {
        if (ch == TelnetCommand.SE) {
            synchronized (client) {
                client.processSuboption(suboption, suboptionCount);
                client.flushOutputStream();
            }
            receiveState = STATE_DATA;
        } else if (ch == TelnetCommand.IAC && suboptionCount < suboption.length) {
            suboption[suboptionCount++] = ch;
        } else {
            receiveState = STATE_SB;
        }
    }

    /**
     * Reads the next number of bytes from the stream into an array and returns the
     * number of bytes read. Returns -1 if the end of the stream has been reached.
     *
     * @param buffer The byte array in which to store the data.
     * @return The number of bytes read. Returns -1 if the end of the message has
     *         been reached.
     * @throws IOException If an error occurs in reading the underlying stream.
     */
    @Override
    public int read(final byte buffer[]) throws IOException {
        return read(buffer, 0, buffer.length);
    }

    /**
     * Reads the next number of bytes from the stream into an array and returns the
     * number of bytes read. Returns -1 if the end of the message has been reached.
     * The characters are stored in the array starting from the given offset and up
     * to the length specified.
     *
     * @param buffer The byte array in which to store the data.
     * @param offset The offset into the array at which to start storing data.
     * @param length The number of bytes to read.
     * @return The number of bytes read. Returns -1 if the end of the stream has
     *         been reached.
     * @throws IOException If an error occurs while reading the underlying stream.
     */
    @Override
    public int read(final byte buffer[], int offset, int length) throws IOException {
        int ch;
        final int off;

        if (length < 1) {
            return 0;
        }

        // Critical section because run() may change bytesAvailable
        synchronized (queue) {
            if (length > bytesAvailable) {
                length = bytesAvailable;
            }
        }

        if ((ch = read()) == EOF) {
            return EOF;
        }

        off = offset;

        do {
            buffer[offset++] = (byte) ch;
        } while (--length > 0 && (ch = read()) != EOF);

        // client._spyRead(buffer, off, offset - off);
        return offset - off;
    }

    @Override
    public void run() {
        try {
            processInputLoop();
        } catch (final IOException ioe) {
            handleIOException(ioe);
        } finally {
            cleanupAndNotify();
            threaded = false; // Clean up thread state
        }
    }

    private void processInputLoop() throws IOException {
        int ch;

        _outerLoop: while (!isClosed) {
            try {
                ch = readCharacter();
                if (ch < 0) {
                    break;
                }
            } catch (final InterruptedIOException e) {
                handleInterruptedIOException(e);
                continue; // Continue to the next iteration after handling the exception
            } catch (final RuntimeException re) {
                handleRuntimeException();
                break _outerLoop; // Exit the outer loop on RuntimeException
            }

            processCharacter(ch);
        }
    }

    private int readCharacter() throws IOException {
        return read(true);
    }

    private void processCharacter(int ch) {
        boolean notify = false;
        try {
            notify = processChar(ch);
        } catch (final InterruptedException e) {
            if (isClosed) {
                throw new RuntimeException("Stream closed during processing", e);
            }
        }

        // Notify input listener if buffer was previously empty
        if (notify) {
            client.notifyInputListener();
        }
    }

    private void handleInterruptedIOException(InterruptedIOException e) {
        synchronized (queue) {
            ioException = e;
            queue.notifyAll();

            while (ioException != null) { // Condizione di attesa
                try {
                    queue.wait(100); // Attendi che la condizione cambi
                } catch (final InterruptedException interrupted) {
                    if (isClosed) {
                        throw new RuntimeException("Stream closed during wait", interrupted); // Gestisce il caso di
                                                                                              // stream chiuso
                    }
                    // Reimposta lo stato di interruzione del thread
                    Thread.currentThread().interrupt();
                }
            }
        }
    }

    private void handleRuntimeException() throws IOException {
        super.close();
    }

    private void handleIOException(IOException ioe) {
        synchronized (queue) {
            ioException = ioe; // Store IO exception
        }
        client.notifyInputListener(); // Notify input listener on IO exception
    }

    private void cleanupAndNotify() {
        synchronized (queue) {
            isClosed = true; // Possibly redundant
            hasReachedEOF = true;
            queue.notifyAll(); // Notify any waiting threads
        }
    }

    void start() {
        if (thread == null) {
            return;
        }

        int priority;
        isClosed = false;
        // TODO remove this
        // Need to set a higher priority in case JVM does not use pre-emptive
        // threads. This should prevent scheduler induced deadlock (rather than
        // deadlock caused by a bug in this code).
        priority = Thread.currentThread().getPriority() + 1;
        if (priority > Thread.MAX_PRIORITY) {
            priority = Thread.MAX_PRIORITY;
        }
        thread.setPriority(priority);
        thread.setDaemon(true);
        thread.start();
        threaded = true; // tell _processChar that we are running threaded
    }
}