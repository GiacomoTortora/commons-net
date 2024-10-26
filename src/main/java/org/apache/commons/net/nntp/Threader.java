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

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * This is an implementation of a message threading algorithm, as originally
 * devised by Zamie Zawinski.
 * See <a href=
 * "http://www.jwz.org/doc/threading.html">http://www.jwz.org/doc/threading.html</a>
 * for details.
 * For his Java implementation, see
 * <a href=
 * "https://lxr.mozilla.org/mozilla/source/grendel/sources/grendel/view/Threader.java">
 * https://lxr.mozilla.org/mozilla/source/grendel/sources/grendel/view/Threader.java</a>
 */
public class Threader {

    /**
     *
     * @param threadable
     * @param idTable
     */
    private void buildContainer(final Threadable threadable, final HashMap<String, NntpThreadContainer> idTable) {
        String id = threadable.messageThreadId();
        NntpThreadContainer container = idTable.get(id);
        int bogusIdCount = 0;

        container = processContainer(container, threadable, idTable, id, bogusIdCount);

        NntpThreadContainer parentRef = processReferences(threadable, idTable);

        resolveParentRelationship(container, parentRef);
    }

    private NntpThreadContainer processContainer(NntpThreadContainer container, Threadable threadable,
            HashMap<String, NntpThreadContainer> idTable, String id, int bogusIdCount) {
        if (container != null) {
            switch (Boolean.compare(container.threadable != null, false)) {
                case 1: // Duplicate ids, create a new bogus id
                    bogusIdCount++;
                    id = "<Bogus-id:" + bogusIdCount + ">";
                    container = null;
                    break;
                case 0: // Forward reference, update container
                    container.threadable = threadable;
                    break;
                default:
                    // Do nothing
                    break;
            }
        }

        if (container == null) {
            container = new NntpThreadContainer();
            container.threadable = threadable;
            idTable.put(id, container);
        }

        return container;
    }

    private NntpThreadContainer processReferences(Threadable threadable, HashMap<String, NntpThreadContainer> idTable) {
        NntpThreadContainer parentRef = null;
        final String[] references = threadable.messageThreadReferences();

        for (final String refString : references) {
            NntpThreadContainer ref = idTable.computeIfAbsent(refString, k -> new NntpThreadContainer());

            if (parentRef != null && ref.parent == null && parentRef != ref && !ref.findChild(parentRef)) {
                linkParentChild(parentRef, ref);
            }
            parentRef = ref;
        }
        return parentRef;
    }

    private void linkParentChild(NntpThreadContainer parent, NntpThreadContainer child) {
        child.parent = parent;
        child.next = parent.child;
        parent.child = child;
    }

    private void resolveParentRelationship(NntpThreadContainer container, NntpThreadContainer parentRef) {
        if (parentRef != null && (parentRef == container || container.findChild(parentRef))) {
            parentRef = null;
        }

        if (container.parent != null) {
            unlinkContainerFromParent(container);
        }

        if (parentRef != null) {
            linkParentChild(parentRef, container);
        }
    }

    private void unlinkContainerFromParent(NntpThreadContainer container) {
        NntpThreadContainer rest, prev = null;

        for (rest = container.parent.child; rest != null; prev = rest, rest = rest.next) {
            if (rest == container) {
                break;
            }
        }

        if (rest == null) {
            throw new IllegalStateException("Didn't find " + container + " in parent " + container.parent);
        }

        if (prev == null) {
            container.parent.child = container.next;
        } else {
            prev.next = container.next;
        }

        container.next = null;
        container.parent = null;
    }

    /**
     * Find the root set of all existing ThreadContainers
     *
     * @param idTable
     * @return root the NntpThreadContainer representing the root node
     */
    private NntpThreadContainer findRootSet(final HashMap<String, NntpThreadContainer> idTable) {
        final NntpThreadContainer root = new NntpThreadContainer();
        for (final Map.Entry<String, NntpThreadContainer> entry : idTable.entrySet()) {
            final NntpThreadContainer c = entry.getValue();
            if (c.parent == null) {
                if (c.next != null) {
                    throw new IllegalStateException("c.next is " + c.next.toString());
                }
                c.next = root.child;
                root.child = c;
            }
        }
        return root;
    }

    /**
     * If any two members of the root set have the same subject, merge them. This is
     * to attempt to accomodate messages without References: headers.
     *
     * @param root
     */
    private void gatherSubjects(final NntpThreadContainer root) {
        int count = countChildren(root);

        HashMap<String, NntpThreadContainer> subjectTable = initializeSubjectTable(count);

        // Popolazione della subjectTable
        count = populateSubjectTable(root, subjectTable);

        if (count == 0) {
            return;
        }

        // Iterazione sulla root set e rimozione dei messaggi duplicati
        removeDuplicateMessages(root, subjectTable);

        subjectTable.clear();
    }

    // Metodo per contare i figli
    private int countChildren(NntpThreadContainer root) {
        int count = 0;
        for (NntpThreadContainer c = root.child; c != null; c = c.next) {
            count++;
        }
        return count;
    }

    // Metodo per inizializzare la subjectTable
    private HashMap<String, NntpThreadContainer> initializeSubjectTable(int count) {
        return new HashMap<>((int) (count * 1.2), 0.9f);
    }

    // Metodo per popolare la subjectTable
    private int populateSubjectTable(NntpThreadContainer root, HashMap<String, NntpThreadContainer> subjectTable) {
        int count = 0;
        for (NntpThreadContainer c = root.child; c != null; c = c.next) {
            Threadable threadable = getThreadable(c);

            final String subj = threadable.simplifiedSubject();
            if (isSubjectInvalid(subj)) {
                continue;
            }

            if (shouldAddToTable(subjectTable.get(subj), c)) {
                subjectTable.put(subj, c);
                count++;
            }
        }
        return count;
    }

    // Verifica se il soggetto è valido
    private boolean isSubjectInvalid(String subj) {
        return subj == null || subj.isEmpty();
    }

    // Ottiene l'oggetto Threadable dal nodo
    private Threadable getThreadable(NntpThreadContainer container) {
        return container.threadable == null ? container.child.threadable : container.threadable;
    }

    // Verifica se il nodo deve essere aggiunto alla subjectTable
    private boolean shouldAddToTable(NntpThreadContainer old, NntpThreadContainer c) {
        switch (checkConditionsToAdd(old, c)) {
            case 1:
            case 2:
            case 3:
                return true;
            default:
                return false;
        }
    }

    // Metodo per rimuovere i messaggi duplicati dalla root
    private void removeDuplicateMessages(NntpThreadContainer root, HashMap<String, NntpThreadContainer> subjectTable) {
        NntpThreadContainer prev = null;
        for (NntpThreadContainer c = root.child,
                rest = c.next; c != null; prev = c, c = rest, rest = (rest == null ? null : rest.next)) {
            Threadable threadable = getThreadable(c);
            final String subj = threadable.simplifiedSubject();

            if (isSubjectInvalid(subj)) {
                continue;
            }

            final NntpThreadContainer old = subjectTable.get(subj);

            if (old == c) {
                continue;
            }

            removeContainer(root, prev, c);
            handleMerging(old, c);
            c = prev; // Manteniamo lo stesso prev
        }
    }

    // Rimozione di un contenitore dalla root set
    private void removeContainer(NntpThreadContainer root, NntpThreadContainer prev, NntpThreadContainer c) {
        if (prev == null) {
            root.child = c.next;
        } else {
            prev.next = c.next;
        }
        c.next = null;
    }

    // Metodo per controllare le condizioni di aggiunta alla subjectTable
    private int checkConditionsToAdd(NntpThreadContainer old, NntpThreadContainer c) {
        if (old == null) {
            return 1; // old == null
        } else if (c.threadable == null && old.threadable != null) {
            return 2; // c.threadable == null && old.threadable != null
        } else if (old.threadable != null && old.threadable.subjectIsReply() && c.threadable != null
                && !c.threadable.subjectIsReply()) {
            return 3; // old è una risposta e c non lo è
        }
        return 0; // Nessuna condizione soddisfatta
    }

    // Metodo per gestire il merging
    private void handleMerging(NntpThreadContainer old, NntpThreadContainer c) {
        if (old.threadable == null && c.threadable == null) {
            // Entrambi sono dummies - uniscili
            NntpThreadContainer tail;
            for (tail = old.child; tail != null && tail.next != null; tail = tail.next) {
                // do nothing
            }

            if (tail != null) { // proteggere contro NPE possibile
                tail.next = c.child;
            }

            for (tail = c.child; tail != null; tail = tail.next) {
                tail.parent = old;
            }

            c.child = null;
        } else if (old.threadable == null
                || (c.threadable != null && c.threadable.subjectIsReply() && !old.threadable.subjectIsReply())) {
            // c ha "Re:" e old non lo ha ==> rendi questo messaggio un figlio di old
            c.parent = old;
            c.next = old.child;
            old.child = c;
        } else {
            // Altrimenti, fai in modo che i vecchi e i nuovi messaggi siano figli di un
            // nuovo contenitore dummy.
            final NntpThreadContainer newc = new NntpThreadContainer();
            newc.threadable = old.threadable;
            newc.child = old.child;

            for (NntpThreadContainer tail = newc.child; tail != null; tail = tail.next) {
                tail.parent = newc;
            }

            old.threadable = null;
            old.child = null;

            c.parent = old;
            newc.parent = old;

            // Old diventa un dummy - dare 2 kids, c e newc
            old.child = c;
            c.next = newc;
        }
    }

    /**
     * Delete any empty or dummy ThreadContainers
     *
     * @param parent
     */
    private void pruneEmptyContainers(final NntpThreadContainer parent) {
        NntpThreadContainer prev = null;
        NntpThreadContainer container = parent.child;
        NntpThreadContainer next = (container != null) ? container.next : null;

        while (container != null) {
            String state = determineState(container);

            switch (state) {
                case "EMPTY_NO_CHILDREN":
                    removeEmptyContainer(parent, prev, container);
                    container = prev;
                    break;

                case "EMPTY_WITH_KIDS":
                    promoteChildren(parent, prev, container);
                    next = container.child;
                    container = prev;
                    break;

                case "REAL_MESSAGE_WITH_KIDS":
                    pruneEmptyContainers(container);
                    break;

                default:
                    break;
            }

            prev = container;
            container = next;
            next = (container != null) ? container.next : null;
        }
    }

    private String determineState(NntpThreadContainer container) {
        if (container.threadable == null && container.child == null) {
            return "EMPTY_NO_CHILDREN";
        } else if (container.threadable == null && (container.parent != null || container.child.next == null)) {
            return "EMPTY_WITH_KIDS";
        } else if (container.child != null) {
            return "REAL_MESSAGE_WITH_KIDS";
        }
        return "VALID";
    }

    private void removeEmptyContainer(NntpThreadContainer parent, NntpThreadContainer prev,
            NntpThreadContainer container) {
        if (prev == null) {
            parent.child = container.next;
        } else {
            prev.next = container.next;
        }
    }

    private void promoteChildren(NntpThreadContainer parent, NntpThreadContainer prev, NntpThreadContainer container) {
        NntpThreadContainer kids = container.child;

        if (prev == null) {
            parent.child = kids;
        } else {
            prev.next = kids;
        }

        NntpThreadContainer tail = updateParentAndFindTail(kids, container);
        tail.next = container.next;
    }

    private NntpThreadContainer updateParentAndFindTail(NntpThreadContainer kids, NntpThreadContainer container) {
        NntpThreadContainer tail = kids;
        while (tail.next != null) {
            tail.parent = container.parent;
            tail = tail.next;
        }
        tail.parent = container.parent;
        return tail;
    }

    /**
     * The client passes in a list of Iterable objects, and the Threader constructs
     * a connected 'graph' of messages
     *
     * @param messages iterable of messages to thread, must not be empty
     * @return null if messages == null or root.child == null or messages list is
     *         empty
     * @since 3.0
     */
    public Threadable thread(final Iterable<? extends Threadable> messages) {
        if (messages == null) {
            return null;
        }

        HashMap<String, NntpThreadContainer> idTable = new HashMap<>();

        // walk through each Threadable element
        for (final Threadable t : messages) {
            if (!t.isDummy()) {
                buildContainer(t, idTable);
            }
        }

        if (idTable.isEmpty()) {
            return null;
        }

        final NntpThreadContainer root = findRootSet(idTable);
        idTable.clear();
        idTable = null;

        pruneEmptyContainers(root);

        root.reverseChildren();
        gatherSubjects(root);

        if (root.next != null) {
            throw new IllegalStateException("root node has a next:" + root);
        }

        for (NntpThreadContainer r = root.child; r != null; r = r.next) {
            if (r.threadable == null) {
                r.threadable = r.child.threadable.makeDummy();
            }
        }

        final Threadable result = root.child == null ? null : root.child.threadable;
        root.flush();

        return result;
    }

    /**
     * The client passes in a list of Threadable objects, and the Threader
     * constructs a connected 'graph' of messages
     *
     * @param messages list of messages to thread, must not be empty
     * @return null if messages == null or root.child == null or messages list is
     *         empty
     * @since 2.2
     */
    public Threadable thread(final List<? extends Threadable> messages) {
        return thread((Iterable<? extends Threadable>) messages);
    }

    // DEPRECATED METHODS - for API compatibility only - DO NOT USE

    /**
     * The client passes in an array of Threadable objects, and the Threader
     * constructs a connected 'graph' of messages
     *
     * @param messages array of messages to thread, must not be empty
     * @return null if messages == null or root.child == null or messages array is
     *         empty
     * @deprecated (2.2) prefer {@link #thread(List)}
     */
    @Deprecated
    public Threadable thread(final Threadable[] messages) {
        if (messages == null) {
            return null;
        }
        return thread(Arrays.asList(messages));
    }
}