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

package org.apache.commons.net.ftp.parser;

import java.text.ParsePosition;
import java.text.SimpleDateFormat;
import java.time.Instant;
import java.util.Calendar;
import java.util.Date;
import java.util.GregorianCalendar;
import java.util.HashMap;
import java.util.Locale;
import java.util.TimeZone;

import org.apache.commons.net.ftp.FTPFile;
import org.apache.commons.net.ftp.FTPFileEntryParserImpl;

/**
 * Parses {@code MSLT} and {@code MLSD} replies. See
 * <a href="https://datatracker.ietf.org/doc/html/rfc3659">RFC 3659</a>.
 * <p>
 * The format is as follows:
 * </p>
 *
 * <pre>
 * entry            = [ facts ] SP pathname
 * facts            = 1*( fact ";" )
 * fact             = factname "=" value
 * factname         = "Size" / "Modify" / "Create" /
 *                    "Type" / "Unique" / "Perm" /
 *                    "Lang" / "Media-Type" / "CharSet" /
 * os-depend-fact / local-fact
 * os-depend-fact   = {IANA assigned OS name} "." token
 * local-fact       = "X." token
 * value            = *SCHAR
 *
 * Sample os-depend-fact:
 * UNIX.group=0;UNIX.mode=0755;UNIX.owner=0;
 * </pre>
 * <p>
 * A single control response entry (MLST) is returned with a leading space;
 * multiple (data) entries are returned without any leading spaces. The parser
 * requires
 * that the leading space from the MLST entry is removed. MLSD entries can begin
 * with a single space if there are no facts.
 * </p>
 *
 * @since 3.0
 */
public class MLSxEntryParser extends FTPFileEntryParserImpl {
    // This class is immutable, so a single instance can be shared.
    private static final MLSxEntryParser INSTANCE = new MLSxEntryParser();

    private static final HashMap<String, Integer> TYPE_TO_INT = new HashMap<>();
    static {
        TYPE_TO_INT.put("file", Integer.valueOf(FTPFile.FILE_TYPE));
        TYPE_TO_INT.put("cdir", Integer.valueOf(FTPFile.DIRECTORY_TYPE)); // listed directory
        TYPE_TO_INT.put("pdir", Integer.valueOf(FTPFile.DIRECTORY_TYPE)); // a parent dir
        TYPE_TO_INT.put("dir", Integer.valueOf(FTPFile.DIRECTORY_TYPE)); // dir or sub-dir
    }

    private static final int[] UNIX_GROUPS = { // Groups in order of mode digits
            FTPFile.USER_ACCESS, FTPFile.GROUP_ACCESS, FTPFile.WORLD_ACCESS, };

    private static final int[][] UNIX_PERMS = { // perm bits, broken down by octal int value
            /* 0 */ {}, /* 1 */ { FTPFile.EXECUTE_PERMISSION }, /* 2 */ { FTPFile.WRITE_PERMISSION },
            /* 3 */ { FTPFile.EXECUTE_PERMISSION, FTPFile.WRITE_PERMISSION }, /* 4 */ { FTPFile.READ_PERMISSION },
            /* 5 */ { FTPFile.READ_PERMISSION, FTPFile.EXECUTE_PERMISSION },
            /* 6 */ { FTPFile.READ_PERMISSION, FTPFile.WRITE_PERMISSION },
            /* 7 */ { FTPFile.READ_PERMISSION, FTPFile.WRITE_PERMISSION, FTPFile.EXECUTE_PERMISSION }, };

    public static MLSxEntryParser getInstance() {
        return INSTANCE;
    }

    public static FTPFile parseEntry(final String entry) {
        return INSTANCE.parseFTPEntry(entry);
    }

    /**
     * Parse a GMT time stamp of the form yyyyMMDDHHMMSS[.sss]
     *
     * @param timestamp the date-time to parse
     * @return a Calendar entry, may be {@code null}
     * @since 3.4
     */
    public static Calendar parseGMTdateTime(final String timestamp) {
        final SimpleDateFormat dateFormat;
        final boolean hasMillis;
        if (timestamp.contains(".")) {
            dateFormat = new SimpleDateFormat("yyyyMMddHHmmss.SSS");
            hasMillis = true;
        } else {
            dateFormat = new SimpleDateFormat("yyyyMMddHHmmss");
            hasMillis = false;
        }
        final TimeZone gmtTimeZone = TimeZone.getTimeZone("GMT");
        // both time zones need to be set for the parse to work OK
        dateFormat.setTimeZone(gmtTimeZone);
        final GregorianCalendar gCalendar = new GregorianCalendar(gmtTimeZone);
        final ParsePosition pos = new ParsePosition(0);
        dateFormat.setLenient(false); // We want to parse the whole string
        final Date parsed = dateFormat.parse(timestamp, pos);
        if (pos.getIndex() != timestamp.length()) {
            return null; // did not fully parse the input
        }
        gCalendar.setTime(parsed);
        if (!hasMillis) {
            gCalendar.clear(Calendar.MILLISECOND); // flag up missing ms units
        }
        return gCalendar;
    }

    /**
     * Parse a GMT time stamp of the form yyyyMMDDHHMMSS[.sss]
     *
     * @param timestamp the date-time to parse
     * @return a Calendar entry, may be {@code null}
     * @since 3.9.0
     */
    public static Instant parseGmtInstant(final String timestamp) {
        return parseGMTdateTime(timestamp).toInstant();
    }

    /**
     * Creates the parser for MSLT and MSLD listing entries This class is immutable,
     * so one can use {@link #getInstance()} instead.
     *
     * @deprecated Use {@link #getInstance()}.
     */
    @Deprecated
    public MLSxEntryParser() {
        // empty
    }

    // perm-fact = "Perm" "=" *pvals
    // pvals = "a" / "c" / "d" / "e" / "f" /
    // "l" / "m" / "p" / "r" / "w"
    private void doUnixPerms(final FTPFile file, final String valueLowerCase) {
        for (final char c : valueLowerCase.toCharArray()) {
            // TODO these are mostly just guesses at present
            switch (c) {
                case 'a': // (file) may APPEnd
                    file.setPermission(FTPFile.USER_ACCESS, FTPFile.WRITE_PERMISSION, true);
                    break;
                case 'c': // (dir) files may be created in the dir
                    file.setPermission(FTPFile.USER_ACCESS, FTPFile.WRITE_PERMISSION, true);
                    break;
                case 'd': // deletable
                    file.setPermission(FTPFile.USER_ACCESS, FTPFile.WRITE_PERMISSION, true);
                    break;
                case 'e': // (dir) can change to this dir
                    file.setPermission(FTPFile.USER_ACCESS, FTPFile.READ_PERMISSION, true);
                    break;
                case 'f': // (file) renamable
                    // ?? file.setPermission(FTPFile.USER_ACCESS, FTPFile.WRITE_PERMISSION, true);
                    break;
                case 'l': // (dir) can be listed
                    file.setPermission(FTPFile.USER_ACCESS, FTPFile.EXECUTE_PERMISSION, true);
                    break;
                case 'm': // (dir) can create directory here
                    file.setPermission(FTPFile.USER_ACCESS, FTPFile.WRITE_PERMISSION, true);
                    break;
                case 'p': // (dir) entries may be deleted
                    file.setPermission(FTPFile.USER_ACCESS, FTPFile.WRITE_PERMISSION, true);
                    break;
                case 'r': // (files) file may be RETRieved
                    file.setPermission(FTPFile.USER_ACCESS, FTPFile.READ_PERMISSION, true);
                    break;
                case 'w': // (files) file may be STORed
                    file.setPermission(FTPFile.USER_ACCESS, FTPFile.WRITE_PERMISSION, true);
                    break;
                default:
                    break;
                // ignore unexpected flag for now.
            } // switch
        } // each char
    }

    @Override
    public FTPFile parseFTPEntry(final String entry) {
        // Controlla se l'entry inizia con uno spazio
        if (entry.startsWith(" ")) {
            return handleLeadingSpaceEntry(entry);
        }

        // Splitta l'entry in fatti e nome del file
        String[] parts = entry.split(" ", 2);
        if (parts.length != 2 || parts[1].isEmpty()) {
            return null; // Invalid - no file name
        }

        // Crea un nuovo FTPFile
        FTPFile file = createFTPFile(entry, parts[1]);
        String factList = parts[0];

        // Verifica se il factList termina con un punto e virgola
        if (!factList.endsWith(";")) {
            return null; // Deve terminare con un punto e virgola
        }

        // Processa i fatti
        return processFacts(factList, file) ? file : null; // Ritorna null se ci sono errori nel parsing
    }

    // Gestisce le voci che iniziano con uno spazio
    private FTPFile handleLeadingSpaceEntry(String entry) {
        if (entry.length() > 1) {
            FTPFile file = new FTPFile();
            file.setRawListing(entry);
            file.setName(entry.substring(1));
            return file;
        }
        return null; // Invalid - no pathname
    }

    // Crea un FTPFile a partire dall'entry
    private FTPFile createFTPFile(String entry, String name) {
        FTPFile file = new FTPFile();
        file.setRawListing(entry);
        file.setName(name);
        return file;
    }

    // Processa i fatti presenti nell'entry
    private boolean processFacts(String factList, FTPFile file) {
        String[] facts = factList.split(";");
        boolean hasUnixMode = factList.toLowerCase(Locale.ENGLISH).contains("unix.mode=");

        for (String fact : facts) {
            String[] factParts = fact.split("=", -1);
            if (factParts.length != 2) {
                return false; // Invalid - there was no "=" sign
            }

            String factName = factParts[0].toLowerCase(Locale.ENGLISH);
            String factValue = factParts[1];

            if (factValue.isEmpty()) {
                continue; // nothing to process here
            }

            // Processa il fatto specifico
            if (!processFact(factName, factValue, file, hasUnixMode)) {
                return false; // Ritorna false in caso di errore
            }
        }
        return true; // Tutti i fatti elaborati con successo
    }

    // Processa un singolo fatto
    private boolean processFact(String factName, String factValue, FTPFile file, boolean hasUnixMode) {
        switch (factName) {
            case "size":
                return handleSizeFact(factValue, file);
            case "modify":
                return handleModifyFact(factValue, file);
            case "type":
                return handleTypeFact(factValue, file);
            default:
                return handleUnixOrPermFact(factName, factValue, file, hasUnixMode);
        }
    }

    // Gestisce il fatto "size"
    private boolean handleSizeFact(String factValue, FTPFile file) {
        try {
            file.setSize(Long.parseLong(factValue));
            return true; // Successo
        } catch (NumberFormatException e) {
            return false; // Errore nel formato
        }
    }

    // Gestisce il fatto "modify"
    private boolean handleModifyFact(String factValue, FTPFile file) {
        Calendar parsed = parseGMTdateTime(factValue);
        if (parsed == null) {
            return false; // Errore nella data di modifica
        }
        file.setTimestamp(parsed);
        return true; // Successo
    }

    // Gestisce il fatto "type"
    private boolean handleTypeFact(String factValue, FTPFile file) {
        Integer intType = TYPE_TO_INT.get(factValue.toLowerCase(Locale.ENGLISH));
        file.setType(intType != null ? intType : FTPFile.UNKNOWN_TYPE);
        return true; // Successo
    }

    // Gestisce i fatti Unix o "perm"
    private boolean handleUnixOrPermFact(String factName, String factValue, FTPFile file, boolean hasUnixMode) {
        if (factName.startsWith("unix.")) {
            return handleUnixFact(factName, factValue, file);
        } else if (!hasUnixMode && "perm".equals(factName)) {
            doUnixPerms(file, factValue.toLowerCase(Locale.ENGLISH));
        }
        return true; // Non un caso di errore
    }

    // Gestisce i fatti Unix
    private boolean handleUnixFact(String factName, String factValue, FTPFile file) {
        String unixFact = factName.substring("unix.".length()).toLowerCase(Locale.ENGLISH);
        switch (unixFact) {
            case "group":
                file.setGroup(factValue);
                return true;
            case "owner":
                file.setUser(factValue);
                return true;
            case "mode":
                handleUnixMode(factValue, file);
                return true;
            default:
                return true; // Caso valido non gestito
        }
    }

    // Gestisce i permessi Unix
    private void handleUnixMode(String factValue, FTPFile file) {
        int off = factValue.length() - 3; // Solo i 3 ultimi caratteri
        for (int i = 0; i < 3; i++) {
            int ch = factValue.charAt(off + i) - '0';
            if (ch >= 0 && ch <= 7) { // Verifica che sia ottale valido
                for (int p : UNIX_PERMS[ch]) {
                    file.setPermission(UNIX_GROUPS[i], p, true);
                }
            }
        }
    }
}