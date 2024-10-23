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

package org.apache.commons.net.examples.ntp;

import java.io.IOException;
import java.net.InetAddress;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.text.DecimalFormat;
import java.text.NumberFormat;
import java.time.Duration;
import java.util.Objects;

import org.apache.commons.net.ntp.NTPUDPClient;
import org.apache.commons.net.ntp.NtpUtils;
import org.apache.commons.net.ntp.NtpV3Packet;
import org.apache.commons.net.ntp.TimeInfo;
import org.apache.commons.net.ntp.TimeStamp;

/**
 * This is an example program demonstrating how to use the NTPUDPClient class. This program sends a Datagram client request packet to a Network time Protocol
 * (NTP) service port on a specified server, retrieves the time, and prints it to standard output along with the fields from the NTP message header (e.g.
 * stratum level, reference id, poll interval, root delay, mode, ...) See <A HREF="ftp://ftp.rfc-editor.org/in-notes/rfc868.txt"> the spec </A> for details.
 * <p>
 * Usage: NTPClient {@code <hostname-or-address-list>}
 * </p>
 * <p>
 * Example: NTPClient clock.psu.edu
 * </p>
 */
public final class NTPClient {

    private static final String OWN_CLOCK_IP_ADDRESS = "127.127.1.0"; // NOPMD
    private static final NumberFormat numberFormat = new DecimalFormat("0.00");

    public static void main(final String[] args) {
        if (args.length == 0) {
            System.err.println("Usage: NTPClient <hostname-or-address-list>");
            System.exit(1);
        }

        try (NTPUDPClient client = new NTPUDPClient()) {
            // We want to timeout if a response takes longer than 10 seconds
            client.setDefaultTimeout(Duration.ofSeconds(10));
            try {
                client.open();
                for (final String arg : args) {
                    System.out.println();
                    try {
                        final InetAddress hostAddr = InetAddress.getByName(arg);
                        System.out.println("> " + hostAddr.getHostName() + "/" + hostAddr.getHostAddress());
                        final TimeInfo info = client.getTime(hostAddr);
                        processResponse(info);
                    } catch (final IOException ioe) {
                        ioe.printStackTrace();
                    }
                }
            } catch (final SocketException e) {
                e.printStackTrace();
            }
        }
    }

    /**
     * Process {@code TimeInfo} object and print its details.
     *
     * @param info {@code TimeInfo} object.
     */
    public static void processResponse(final TimeInfo info) {
        final NtpV3Packet message = info.getMessage();
        processStratum(message);
        processMessageDetails(message);
        processReferenceDetails(message, info);
        processTimestamps(message, info);
        computeAndDisplayDetails(info);
    }

    private static void processStratum(final NtpV3Packet message) {
        final int stratum = message.getStratum();
        final String refType;
        if (stratum <= 0) {
            refType = "(Unspecified or Unavailable)";
        } else if (stratum == 1) {
            refType = "(Primary Reference; e.g., GPS)"; // GPS, radio clock, etc.
        } else {
            refType = "(Secondary Reference; e.g. via NTP or SNTP)";
        }
        System.out.println(" Stratum: " + stratum + " " + refType);
    }

    private static void processMessageDetails(final NtpV3Packet message) {
        final int version = message.getVersion();
        final int li = message.getLeapIndicator();
        System.out.println(" leap=" + li + ", version=" + version + ", precision=" + message.getPrecision());
        System.out.println(" mode: " + message.getModeName() + " (" + message.getMode() + ")");
        
        final int poll = message.getPoll();
        System.out.println(" poll: " + (poll <= 0 ? 1 : (int) Math.pow(2, poll)) + " seconds" + " (2 ** " + poll + ")");
        final double disp = message.getRootDispersionInMillisDouble();
        System.out.println(" rootdelay=" + numberFormat.format(message.getRootDelayInMillisDouble()) + ", rootdispersion(ms): " + numberFormat.format(disp));
    }

    private static void processReferenceDetails(final NtpV3Packet message, final TimeInfo info) {
        final int refId = message.getReferenceId();
        String refAddr = NtpUtils.getHostAddress(refId);
        String refName = getReferenceName(message, refId, refAddr);
        
        if (refName != null && refName.length() > 1) {
            refAddr += " (" + refName + ")";
        }
        System.out.println(" Reference Identifier:\t" + refAddr);
    }

    private static String getReferenceName(final NtpV3Packet message, final int refId, final String refAddr) {
        String refName = null;
        if (refId != 0) {
            if (refAddr.equals(OWN_CLOCK_IP_ADDRESS)) {
                refName = "LOCAL"; // This is the ref address for the Local Clock
            } else if (message.getStratum() >= 2 && !refAddr.startsWith("127.127")) {
                refName = resolveHostName(refAddr, message);
            } else if (message.getVersion() >= 3 && (message.getStratum() == 0 || message.getStratum() == 1)) {
                refName = NtpUtils.getReferenceClock(message);
            }
        }
        return refName;
    }

    private static String resolveHostName(final String refAddr, final NtpV3Packet message) {
        String refName = null;
        try {
            final InetAddress addr = InetAddress.getByName(refAddr);
            final String name = addr.getHostName();
            if (name != null && !name.equals(refAddr)) {
                refName = name;
            }
        } catch (final UnknownHostException e) {
            refName = NtpUtils.getReferenceClock(message);
        }
        return refName;
    }

    private static void processTimestamps(final NtpV3Packet message, final TimeInfo info) {
        final TimeStamp refNtpTime = message.getReferenceTimeStamp();
        System.out.println(" Reference Timestamp:\t" + refNtpTime + "  " + refNtpTime.toDateString());

        final TimeStamp origNtpTime = message.getOriginateTimeStamp();
        System.out.println(" Originate Timestamp:\t" + origNtpTime + "  " + origNtpTime.toDateString());

        final long destTimeMillis = info.getReturnTime();
        final TimeStamp rcvNtpTime = message.getReceiveTimeStamp();
        System.out.println(" Receive Timestamp:\t" + rcvNtpTime + "  " + rcvNtpTime.toDateString());

        final TimeStamp xmitNtpTime = message.getTransmitTimeStamp();
        System.out.println(" Transmit Timestamp:\t" + xmitNtpTime + "  " + xmitNtpTime.toDateString());

        final TimeStamp destNtpTime = TimeStamp.getNtpTime(destTimeMillis);
        System.out.println(" Destination Timestamp:\t" + destNtpTime + "  " + destNtpTime.toDateString());
    }

    private static void computeAndDisplayDetails(final TimeInfo info) {
        info.computeDetails(); // compute offset/delay if not already done
        final Long offsetMillis = info.getOffset();
        final Long delayMillis = info.getDelay();
        final String delay = Objects.toString(delayMillis, "N/A");
        final String offset = Objects.toString(offsetMillis, "N/A");

        System.out.println(" Roundtrip delay(ms)=" + delay + ", clock offset(ms)=" + offset); // offset in ms
    }
}