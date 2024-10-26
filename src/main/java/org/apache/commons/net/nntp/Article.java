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

package org.apache.commons.net.nntp;

import java.io.PrintStream;
import java.util.ArrayList;
import java.util.Collections;

import org.apache.commons.net.util.NetConstants;

/**
 * Basic state needed for message retrieval and threading. With thanks to Jamie
 * Zawinski (jwz@jwz.org)
 */
public class Article implements Threadable<Article> {

    /**
     * Recursive method that traverses a pre-threaded graph (or tree) of connected
     * Article objects and prints them out.
     *
     * @param article the root of the article 'tree'
     * @since 3.4
     */
    public static void printThread(final Article article) {
        printThread(article, 0, System.out);
    }

    /**
     * Recursive method that traverses a pre-threaded graph (or tree) of connected
     * Article objects and prints them out.
     *
     * @param article the root of the article 'tree'
     * @param depth   the current tree depth
     */
    public static void printThread(final Article article, final int depth) {
        printThread(article, depth, System.out);
    }

    /**
     * Recursive method that traverses a pre-threaded graph (or tree) of connected
     * Article objects and prints them out.
     *
     * @param article the root of the article 'tree'
     * @param depth   the current tree depth
     * @param ps      the PrintStream to use
     * @since 3.4
     */
    public static void printThread(final Article article, final int depth, final PrintStream ps) {
        for (int i = 0; i < depth; ++i) {
            ps.print("==>");
        }
        ps.println(article.getSubject() + "\t" + article.getFrom() + "\t" + article.getArticleId());
        if (article.kid != null) {
            printThread(article.kid, depth + 1);
        }
        if (article.next != null) {
            printThread(article.next, depth);
        }
    }

    /**
     * Recursive method that traverses a pre-threaded graph (or tree) of connected
     * Article objects and prints them out.
     *
     * @param article the root of the article 'tree'
     * @param ps      the PrintStream to use
     * @since 3.4
     */
    public static void printThread(final Article article, final PrintStream ps) {
        printThread(article, 0, ps);
    }

    private long articleNumber;
    private String subject;
    private String date;
    private String articleId;

    private String simplifiedSubject;

    private String from;
    private ArrayList<String> references;

    private boolean isReply;

    /**
     * Will be private in 4.0.
     *
     * @deprecated Use {@link #setChild(Article)} and {@link #getChild()}.
     */
    @Deprecated
    public Article kid;

    /**
     * Will be private in 4.0.
     *
     * @deprecated Use {@link #setNext(Article)} and {@link #getNext()}.
     */
    @Deprecated
    public Article next;

    public Article() {
        articleNumber = -1; // isDummy
    }

    @Deprecated
    public void addHeaderField(final String name, final String val) {
        // empty
    }

    /**
     * Adds a message-id to the list of messages that this message references (i.e.
     * replies to)
     *
     * @param msgId the message id to add
     */
    public void addReference(final String msgId) {
        if (msgId == null || msgId.isEmpty()) {
            return;
        }
        if (references == null) {
            references = new ArrayList<>();
        }
        isReply = true;
        Collections.addAll(references, msgId.split(" "));
    }

    private void flushSubjectCache() {
        simplifiedSubject = null;
    }

    /**
     * Gets the article ID.
     *
     * @return the article ID.
     */
    public String getArticleId() {
        return articleId;
    }

    /**
     * Gets the article number.
     *
     * @return the article number.
     */
    @Deprecated
    public int getArticleNumber() {
        return (int) articleNumber;
    }

    /**
     * Gets the article number.
     *
     * @return the article number.
     */
    public long getArticleNumberLong() {
        return articleNumber;
    }

    /**
     * Gets the child article.
     *
     * @return the child article.
     * @since 3.12.0
     */
    public Article getChild() {
        return kid;
    }

    /**
     * Gets the article date header.
     *
     * @return the article date header.
     */
    public String getDate() {
        return date;
    }

    /**
     * Gets the article from header.
     *
     * @return the article from header.
     */
    public String getFrom() {
        return from;
    }

    /**
     * Gets the next article.
     *
     * @return the next article.
     * @since 3.12.0
     */
    public Article getNext() {
        return next;
    }

    /**
     * Returns the MessageId references as an array of Strings
     *
     * @return an array of message-ids
     */
    public String[] getReferences() {
        if (references == null) {
            return NetConstants.EMPTY_STRING_ARRAY;
        }
        return references.toArray(NetConstants.EMPTY_STRING_ARRAY);
    }

    /**
     * Gets the article subject.
     *
     * @return the article subject.
     */
    public String getSubject() {
        return subject;
    }

    @Override
    public boolean isDummy() {
        return articleNumber == -1;
    }

    @Override
    public Article makeDummy() {
        return new Article();
    }

    @Override
    public String messageThreadId() {
        return articleId;
    }

    @Override
    public String[] messageThreadReferences() {
        return getReferences();
    }

    public void setArticleId(final String string) {
        articleId = string;
    }

    /**
     * Sets the article number.
     *
     * @param articleNumber the article number.
     */
    @Deprecated
    public void setArticleNumber(final int articleNumber) {
        this.articleNumber = articleNumber;
    }

    /**
     * Sets the article number.
     *
     * @param articleNumber the article number.
     */
    public void setArticleNumber(final long articleNumber) {
        this.articleNumber = articleNumber;
    }

    @Override
    public void setChild(final Article child) {
        this.kid = child;
        flushSubjectCache();
    }

    /**
     * Sets the article date header.
     *
     * @param date the article date header.
     */
    public void setDate(final String date) {
        this.date = date;
    }

    /**
     * Sets the article from header.
     *
     * @param from the article from header.
     */
    public void setFrom(final String from) {
        this.from = from;
    }

    @Override
    public void setNext(final Article next) {
        this.next = next;
        flushSubjectCache();
    }

    /**
     * Sets the article subject.
     *
     * @param subject the article subject.
     */
    public void setSubject(final String subject) {
        this.subject = subject;
    }

    @Override
    public String simplifiedSubject() {
        if (simplifiedSubject == null) {
            simplifySubject();
        }
        return simplifiedSubject;
    }

    /**
     * Attempts to parse the subject line for some typical reply signatures, and
     * strip them out
     */
    private void simplifySubject() {
        int start = 0;
        final String subject = getSubject();
        final int len = subject.length();
        boolean done = false;

        while (!done) {
            done = true;

            // Salta spazi bianchi iniziali
            start = skipLeadingWhitespace(subject, start, len);

            // Semplifica "Re: " e variazioni
            start = simplifyReplyPrefix(subject, start, len);

            // Gestisce il caso "(no subject)"
            handleNoSubject();

            // Imposta il soggetto semplificato rimuovendo gli spazi bianchi finali
            simplifiedSubject = extractSimplifiedSubject(subject, start, len);
        }
    }

    private int skipLeadingWhitespace(String subject, int start, int len) {
        while (start < len && subject.charAt(start) == ' ') {
            start++;
        }
        return start;
    }

    private int simplifyReplyPrefix(String subject, int start, int len) {
        if (start < len - 2 && isReplyPrefix(subject, start)) {
            switch (subject.charAt(start + 2)) {
                case ':':
                    start += 3; // Salta "Re:"
                    break;
                case '[':
                case '(':
                    int i = start + 3;
                    while (i < len && Character.isDigit(subject.charAt(i))) {
                        i++;
                    }
                    if (isValidReplySuffix(subject, i, len)) {
                        start = i + 2;
                    }
                    break;
                default:
                    break;
            }
        }
        return start;
    }

    private boolean isReplyPrefix(String subject, int start) {
        return (subject.charAt(start) == 'r' || subject.charAt(start) == 'R') &&
                (subject.charAt(start + 1) == 'e' || subject.charAt(start + 1) == 'E');
    }

    private boolean isValidReplySuffix(String subject, int i, int len) {
        return i < len - 1 && (subject.charAt(i) == ']' || subject.charAt(i) == ')')
                && subject.charAt(i + 1) == ':';
    }

    private void handleNoSubject() {
        if ("(no subject)".equals(simplifiedSubject)) {
            simplifiedSubject = "";
        }
    }

    private String extractSimplifiedSubject(String subject, int start, int len) {
        int end = trimTrailingWhitespace(subject, len, start);
        return (start == 0 && end == len) ? subject : subject.substring(start, end);
    }

    private int trimTrailingWhitespace(String subject, int len, int start) {
        int end = len;
        while (end > start && subject.charAt(end - 1) < ' ') {
            end--;
        }
        return end;
    }

    @Override
    public boolean subjectIsReply() {
        return isReply;
    }

    @Override
    public String toString() { // Useful for Eclipse debugging
        return articleNumber + " " + articleId + " " + subject;
    }
}