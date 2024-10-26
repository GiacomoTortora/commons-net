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

package org.apache.commons.net.ntp;

import java.net.DatagramPacket;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.List;

/**
 * Wrapper class to network time packet messages (NTP, etc.) that computes
 * related timing info and stats.
 */
public class TimeInfo {

    private final NtpV3Packet message;
    private List<String> comments;
    private Long delayMillis;
    private Long offsetMillis;

    /**
     * time at which time message packet was received by local machine
     */
    private final long returnTimeMillis;

    /**
     * flag indicating that the TimeInfo details was processed and delay/offset were
     * computed
     */
    private boolean detailsComputed;

    /**
     * Create TimeInfo object with raw packet message and destination time received.
     *
     * @param message          NTP message packet
     * @param returnTimeMillis destination receive time
     * @throws IllegalArgumentException if message is null
     */
    public TimeInfo(final NtpV3Packet message, final long returnTimeMillis) {
        this(message, returnTimeMillis, null, true);
    }

    /**
     * Create TimeInfo object with raw packet message and destination time received.
     * Auto-computes details if computeDetails flag set otherwise this is delayed
     * until computeDetails() is called. Delayed computation is for fast
     * initialization when sub-millisecond timing is needed.
     *
     * @param msgPacket        NTP message packet
     * @param returnTimeMillis destination receive time
     * @param doComputeDetails flag to pre-compute delay/offset values
     * @throws IllegalArgumentException if message is null
     */
    public TimeInfo(final NtpV3Packet msgPacket, final long returnTimeMillis, final boolean doComputeDetails) {
        this(msgPacket, returnTimeMillis, null, doComputeDetails);
    }

    /**
     * Create TimeInfo object with raw packet message and destination time received.
     *
     * @param message          NTP message packet
     * @param returnTimeMillis destination receive time
     * @param comments         List of errors/warnings identified during processing
     * @throws IllegalArgumentException if message is null
     */
    public TimeInfo(final NtpV3Packet message, final long returnTimeMillis, final List<String> comments) {
        this(message, returnTimeMillis, comments, true);
    }

    /**
     * Create TimeInfo object with raw packet message and destination time received.
     * Auto-computes details if computeDetails flag set otherwise this is delayed
     * until computeDetails() is called. Delayed computation is for fast
     * initialization when sub-millisecond timing is needed.
     *
     * @param message          NTP message packet
     * @param returnTimeMillis destination receive time
     * @param comments         list of comments used to store errors/warnings with
     *                         message
     * @param doComputeDetails flag to pre-compute delay/offset values
     * @throws IllegalArgumentException if message is null
     */
    public TimeInfo(final NtpV3Packet message, final long returnTimeMillis, final List<String> comments,
            final boolean doComputeDetails) {
        if (message == null) {
            throw new IllegalArgumentException("message cannot be null");
        }
        this.returnTimeMillis = returnTimeMillis;
        this.message = message;
        this.comments = comments;
        if (doComputeDetails) {
            computeDetails();
        }
    }

    /**
     * Add comment (error/warning) to list of comments associated with processing of
     * NTP parameters. If comment list not create then one will be created.
     *
     * @param comment the comment
     */
    public void addComment(final String comment) {
        if (comments == null) {
            comments = new ArrayList<>();
        }
        comments.add(comment);
    }

    /**
     * Compute and validate details of the NTP message packet. Computed fields
     * include the offset and delay.
     */
    public void computeDetails() {
        if (detailsComputed) {
            return; // details already computed - do nothing
        }
        detailsComputed = true;
        initializeCommentsIfNeeded();

        final TimeStamp origNtpTime = message.getOriginateTimeStamp();
        final long origTimeMillis = origNtpTime.getTime();
        final TimeStamp rcvNtpTime = message.getReceiveTimeStamp();
        final long rcvTimeMillis = rcvNtpTime.getTime();
        final TimeStamp xmitNtpTime = message.getTransmitTimeStamp();
        final long xmitTimeMillis = xmitNtpTime.getTime();

        if (isOriginateTimeZero(origNtpTime)) {
            handleZeroOriginateTime(xmitNtpTime, xmitTimeMillis);
        } else {
            handleNonZeroOriginateTime(origTimeMillis, rcvNtpTime, rcvTimeMillis, xmitNtpTime, xmitTimeMillis);
        }
    }

    private void initializeCommentsIfNeeded() {
        if (comments == null) {
            comments = new ArrayList<>();
        }
    }

    private boolean isOriginateTimeZero(TimeStamp origNtpTime) {
        return origNtpTime.ntpValue() == 0;
    }

    private void handleZeroOriginateTime(TimeStamp xmitNtpTime, long xmitTimeMillis) {
        if (xmitNtpTime.ntpValue() != 0) {
            offsetMillis = Long.valueOf(xmitTimeMillis - returnTimeMillis);
            comments.add("Error: zero orig time -- cannot compute delay");
        } else {
            comments.add("Error: zero orig time -- cannot compute delay/offset");
        }
    }

    private void handleNonZeroOriginateTime(long origTimeMillis, TimeStamp rcvNtpTime, long rcvTimeMillis,
            TimeStamp xmitNtpTime, long xmitTimeMillis) {
        switch (checkNtpValues(rcvNtpTime.ntpValue(), xmitNtpTime.ntpValue())) {
            case 1:
                handleZeroReceiveOrTransmitTime(origTimeMillis, rcvNtpTime, rcvTimeMillis, xmitNtpTime, xmitTimeMillis);
                break;
            case 2:
                handleNormalProcessing(origTimeMillis, rcvTimeMillis, xmitTimeMillis);
                break;
            default:
                break;
        }
    }

    private void handleZeroReceiveOrTransmitTime(long origTimeMillis, TimeStamp rcvNtpTime, long rcvTimeMillis,
            TimeStamp xmitNtpTime, long xmitTimeMillis) {
        comments.add("Warning: zero rcvNtpTime or xmitNtpTime");
        if (origTimeMillis > returnTimeMillis) {
            comments.add("Error: OrigTime > DestRcvTime");
        } else {
            delayMillis = Long.valueOf(returnTimeMillis - origTimeMillis);
        }
        handleOffsetWhenNtpValuesAreZero(rcvNtpTime, rcvTimeMillis, origTimeMillis, xmitNtpTime, xmitTimeMillis);
    }

    private void handleNormalProcessing(long origTimeMillis, long rcvTimeMillis, long xmitTimeMillis) {
        long delayValueMillis = returnTimeMillis - origTimeMillis;
        if (xmitTimeMillis < rcvTimeMillis) {
            comments.add("Error: xmitTime < rcvTime");
        } else {
            final long deltaMillis = xmitTimeMillis - rcvTimeMillis;
            delayValueMillis = adjustDelayValueMillis(delayValueMillis, deltaMillis);
        }
        delayMillis = Long.valueOf(delayValueMillis);

        if (origTimeMillis > returnTimeMillis) {
            comments.add("Error: OrigTime > DestRcvTime");
        }
        offsetMillis = Long.valueOf((rcvTimeMillis - origTimeMillis + xmitTimeMillis - returnTimeMillis) / 2);
    }

    private int checkNtpValues(long rcvNtpValue, long xmitNtpValue) {
        if (rcvNtpValue == 0 || xmitNtpValue == 0) {
            return 1; // Indicates zero rcv or xmit time case
        }
        return 2; // Indicates normal processing case
    }

    private void handleOffsetWhenNtpValuesAreZero(TimeStamp rcvNtpTime, long rcvTimeMillis, long origTimeMillis,
            TimeStamp xmitNtpTime, long xmitTimeMillis) {
        if (rcvNtpTime.ntpValue() != 0) {
            // xmitTime is 0 just use rcv time
            offsetMillis = Long.valueOf(rcvTimeMillis - origTimeMillis);
        } else if (xmitNtpTime.ntpValue() != 0) {
            // rcvTime is 0 just use xmitTime time
            offsetMillis = Long.valueOf(xmitTimeMillis - returnTimeMillis);
        }
    }

    private long adjustDelayValueMillis(long delayValueMillis, long deltaMillis) {
        // in normal cases the processing delta is less than the total roundtrip network
        // travel time.
        if (deltaMillis <= delayValueMillis) {
            delayValueMillis -= deltaMillis; // delay = (t4 - t1) - (t3 - t2)
        } else if (deltaMillis - delayValueMillis == 1) {
            // delayValue == 0 -> local clock saw no tick change but destination clock did
            if (delayValueMillis != 0) {
                comments.add("Info: processing time > total network time by 1 ms -> assume zero delay");
                delayValueMillis = 0;
            }
        } else {
            comments.add("Warning: processing time > total network time");
        }
        return delayValueMillis;
    }

    /**
     * Compares this object against the specified object. The result is {@code true}
     * if and only if the argument is not {@code null} and is a
     * {@code TimeStamp} object that contains the same values as this object.
     *
     * @param obj the object to compare with.
     * @return {@code true} if the objects are the same; {@code false} otherwise.
     * @since 3.4
     */
    @Override
    public boolean equals(final Object obj) {
        if (this == obj) {
            return true;
        }
        if (obj == null || getClass() != obj.getClass()) {
            return false;
        }
        final TimeInfo other = (TimeInfo) obj;
        return returnTimeMillis == other.returnTimeMillis && message.equals(other.message);
    }

    /**
     * Gets host address from message datagram if available
     *
     * @return host address of available otherwise null
     * @since 3.4
     */
    public InetAddress getAddress() {
        final DatagramPacket pkt = message.getDatagramPacket();
        return pkt == null ? null : pkt.getAddress();
    }

    /**
     * Return list of comments (if any) during processing of NTP packet.
     *
     * @return List or null if not yet computed
     */
    public List<String> getComments() {
        return comments;
    }

    /**
     * Gets round-trip network delay. If null then could not compute the delay.
     *
     * @return Long or null if delay not available.
     */
    public Long getDelay() {
        return delayMillis;
    }

    /**
     * Returns NTP message packet.
     *
     * @return NTP message packet.
     */
    public NtpV3Packet getMessage() {
        return message;
    }

    /**
     * Gets clock offset needed to adjust local clock to match remote clock. If null
     * then could not compute the offset.
     *
     * @return Long or null if offset not available.
     */
    public Long getOffset() {
        return offsetMillis;
    }

    /**
     * Returns time at which time message packet was received by local machine.
     *
     * @return packet return time.
     */
    public long getReturnTime() {
        return returnTimeMillis;
    }

    /**
     * Computes a hash code for this object. The result is the exclusive OR of the
     * return time and the message hash code.
     *
     * @return a hash code value for this object.
     * @since 3.4
     */
    @Override
    public int hashCode() {
        final int prime = 31;
        final int result = (int) returnTimeMillis;
        return prime * result + message.hashCode();
    }
}