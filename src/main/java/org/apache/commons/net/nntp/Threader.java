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

        // A NntpThreadContainer exists for this id already. This should be a forward
        // reference, but may
        // be a duplicate id, in which case we will need to generate a bogus placeholder
        // id
        if (container != null) {
            switch (Boolean.compare(container.threadable != null, false)) {
                case 1: // container.threadable is not null (duplicate ids)
                    bogusIdCount++;
                    id = "<Bogus-id:" + bogusIdCount + ">";
                    container = null;
                    break;

                case 0: // container.threadable is null (forward reference)
                    // Fill in the threadable field of the container with this message
                    container.threadable = threadable;
                    break;

                default:
                    // Do nothing for the case where the condition is false
                    break;
            }
        }

        // No container exists for that message Id. Create one and insert it into the
        // hash table.
        if (container == null) {
            container = new NntpThreadContainer();
            container.threadable = threadable;
            idTable.put(id, container);
        }

        // Iterate through all the references and create ThreadContainers for any
        // references that
        // don't have them.
        NntpThreadContainer parentRef = null;
        {
            final String[] references = threadable.messageThreadReferences();
            for (final String refString : references) {
                NntpThreadContainer ref = idTable.get(refString);

                // if this id doesn't have a container, create one
                if (ref == null) {
                    ref = new NntpThreadContainer();
                    idTable.put(refString, ref);
                }

                // Link references together in the order they appear in the References: header,
                // IF they don't have a parent already &&
                // IF it will not cause a circular reference
                if (parentRef != null && ref.parent == null && parentRef != ref && !ref.findChild(parentRef)) {
                    // Link ref into the parent's child list
                    ref.parent = parentRef;
                    ref.next = parentRef.child;
                    parentRef.child = ref;
                }
                parentRef = ref;
            }
        }

        // parentRef is now set to the container of the last element in the references
        // field. make that
        // be the parent of this container, unless doing so causes a circular reference
        if (parentRef != null && (parentRef == container || container.findChild(parentRef))) {
            parentRef = null;
        }

        // if it has a parent already, it's because we saw this message in a References:
        // field, and presumed
        // a parent based on the other entries in that field. Now that we have the
        // actual message, we can
        // throw away the old parent and use this new one
        if (container.parent != null) {
            NntpThreadContainer rest, prev;

            for (prev = null, rest = container.parent.child; rest != null; prev = rest, rest = rest.next) {
                if (rest == container) {
                    break;
                }
            }

            if (rest == null) {
                throw new IllegalStateException("Didnt find " + container + " in parent " + container.parent);
            }

            // Unlink this container from the parent's child list
            if (prev == null) {
                container.parent.child = container.next;
            } else {
                prev.next = container.next;
            }

            container.next = null;
            container.parent = null;
        }

        // If we have a parent, link container into the parent's child list
        if (parentRef != null) {
            container.parent = parentRef;
            container.next = parentRef.child;
            parentRef.child = container;
        }
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
        int count = 0;

        // Contare i nodi figli
        for (NntpThreadContainer c = root.child; c != null; c = c.next) {
            count++;
        }

        HashMap<String, NntpThreadContainer> subjectTable = new HashMap<>((int) (count * 1.2), (float) 0.9);
        count = 0;

        // Popolazione della subjectTable
        for (NntpThreadContainer c = root.child; c != null; c = c.next) {
            Threadable threadable = c.threadable;

            // No threadable? If so, it is a dummy node in the root set.
            if (threadable == null) {
                threadable = c.child.threadable;
            }

            final String subj = threadable.simplifiedSubject();

            if (subj == null || subj.isEmpty()) {
                continue;
            }

            final NntpThreadContainer old = subjectTable.get(subj);

            // Logica di aggiunta al subjectTable
            boolean addToTable = false;

            switch (checkConditionsToAdd(old, c)) {
                case 1: // old == null
                    addToTable = true;
                    break;
                case 2: // c.threadable == null && old.threadable != null
                    addToTable = true;
                    break;
                case 3: // old.threadable != null && old.threadable.subjectIsReply() && c.threadable !=
                        // null && !c.threadable.subjectIsReply()
                    addToTable = true;
                    break;
            }

            if (addToTable) {
                subjectTable.put(subj, c);
                count++;
            }
        }

        if (count == 0) {
            return;
        }

        // Iterazione sulla root set
        NntpThreadContainer prev, c, rest;
        for (prev = null, c = root.child, rest = c.next; c != null; prev = c, c = rest, rest = rest == null ? null
                : rest.next) {
            Threadable threadable = c.threadable;

            if (threadable == null) {
                threadable = c.child.threadable;
            }

            final String subj = threadable.simplifiedSubject();

            if (subj == null || subj.isEmpty()) {
                continue;
            }

            final NntpThreadContainer old = subjectTable.get(subj);

            if (old == c) {
                continue;
            }

            // Gestione della rimozione del messaggio
            if (prev == null) {
                root.child = c.next;
            } else {
                prev.next = c.next;
            }
            c.next = null;

            handleMerging(old, c);

            // Manteniamo lo stesso prev
            c = prev;
        }

        subjectTable.clear();
        subjectTable = null;
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
        NntpThreadContainer container, prev, next;
        for (prev = null, container = parent.child, next = container != null ? container.next
                : null; container != null; prev = container, container = next, next = container.next) {

            // Determina il tipo di stato del contenitore
            String state = "VALID";

            if (container.threadable == null && container.child == null) {
                state = "EMPTY_NO_CHILDREN"; // Vuoto e senza figli
            } else if (container.threadable == null && (container.parent != null || container.child.next == null)) {
                state = "EMPTY_WITH_KIDS"; // Vuoto con figli
            } else if (container.child != null) {
                state = "REAL_MESSAGE_WITH_KIDS"; // Messaggio reale con figli
            }

            switch (state) {
                case "EMPTY_NO_CHILDREN":
                    // Is it empty and without any children? If so, delete it
                    if (prev == null) {
                        parent.child = container.next;
                    } else {
                        prev.next = container.next;
                    }
                    // Set container to prev so that prev keeps its same value the next time through
                    // the loop
                    container = prev;
                    break;

                case "EMPTY_WITH_KIDS":
                    // We have an invalid/expired message with kids. Promote the kids to this level.
                    NntpThreadContainer tail;
                    final NntpThreadContainer kids = container.child;

                    // Remove this container and replace with 'kids'.
                    if (prev == null) {
                        parent.child = kids;
                    } else {
                        prev.next = kids;
                    }

                    // Make each child's parent be this level's parent -> i.e. promote the children.
                    // Make the last child's next point to this container's next
                    // i.e. splice kids into the list in place of container
                    for (tail = kids; tail.next != null; tail = tail.next) {
                        tail.parent = container.parent;
                    }

                    tail.parent = container.parent;
                    tail.next = container.next;

                    // next currently points to the item after the inserted items in the chain -
                    // reset that, so we process the newly
                    // promoted items next time round
                    next = kids;

                    // Set container to prev so that prev keeps its same value the next time through
                    // the loop
                    container = prev;
                    break;

                case "REAL_MESSAGE_WITH_KIDS":
                    // A real message , with kids
                    // Iterate over the children
                    pruneEmptyContainers(container);
                    break;
            }
        }
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