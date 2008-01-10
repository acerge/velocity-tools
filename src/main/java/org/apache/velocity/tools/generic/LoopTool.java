package org.apache.velocity.tools.generic;

/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Stack;
import org.apache.velocity.tools.ClassUtils;
import org.apache.velocity.tools.Scope;
import org.apache.velocity.tools.config.DefaultKey;
import org.apache.velocity.tools.config.ValidScope;

/**
 * <p>
 * A convenience tool to use with #foreach loops. It wraps a list
 * with a custom iterator to let the designer specify a condition
 * to stop looping over the items early.
 * </p>
 * <p>
 * This tool is heavily inspired the now-deprecated IteratorTool,
 * which provided similar functionality but was somewhat more difficult
 * to understand and use.  Rather than try to migrate that implementation
 * via deprecation and new methods, it was simplest to just create an
 * entirely new tool with a simplified API, support for nested loops
 * (which can optionally be given names), skipping ahead in loops,
 * getting the iteration count of loops, and identifying if a loop is
 * on its first or last iteration.
 * </p>
 * <p>
 * Most users, of course, will probably never need anything beyond the 
 * simple {@link #watch(Object)}, {@link #stop}, {@link #isFirst},
 * {@link #isLast}, {@link #getCount}, or maybe {@link #skip(int)}
 * methods, if even that much.  However, it is with complicated nested
 * #foreach loops and varying "break" conditions that this tool can
 * probably do the most to simplify your templates.
 * </p>
 * <p>
 * Example of use:
 * <pre>
 *  Template
 *  ---
 *  #set ($list = [1, 2, 3, 4, 5, 6])
 *  #foreach( $item in $loop.watch($list) )
 *  $item ##
 *  #if( $item >= 1 )$loop.skip(1)#end
 *  #if( $item >= 5 )$loop.stop()#end
 *  #end
 *
 *  Output
 *  ------
 *  1 3 5
 *
 * Example tools.xml config (if you want to use this with VelocityView):
 * &lt;tools&gt;
 *   &lt;toolbox scope="request"&gt;
 *     &lt;tool class="org.apache.velocity.tools.generic.LoopTool"/&gt;
 *   &lt;/toolbox&gt;
 * &lt;/tools&gt;
 * </pre>
 * </p>
 *
 * @author Nathan Bubna
 * @version $Id: LoopTool.java 590893 2007-11-01 04:40:21Z nbubna $
 */
@DefaultKey("loop")
@ValidScope(Scope.REQUEST)
public class LoopTool
{
    private Stack<ManagedIterator> iterators = new Stack<ManagedIterator>();

    /**
     * <p>Tells the LoopTool to watch the specified Array, Collection, Map,
     * Iterator, Iterable, Enumeration or POJO with an iterator() method
     * while the template iterates over the values within it.
     * </p>
     * <p>Under the covers, this is returning an iterable wrapper that
     * is also pushed onto this tool's stack.  That allows this tool to
     * know which iterator to give later commands (i.e. stop() or skip()).
     * </p>
     * @param obj an object that Velocity's #foreach directive can iterate over
     * @return a {@link ManagedIterator} that this tool instance will track
     */
    public ManagedIterator watch(Object obj)
    {
        Iterator iterator = getIterator(obj);
        if (iterator == null)
        {
            return null;
        }

        ManagedIterator managed = new ManagedIterator(iterator, this);
        iterators.push(managed);
        return managed;
    }

    /**
     * This is just like {@link #watch(Object)} except that it also takes
     * a name which is given to the {@link ManagedIterator} that is returned.
     * This allows the user to send stop or skip commands to that specific
     * iterator even when there are nested iterators within it that are being
     * watched.  If the given name is {@code null}, then this will return 
     * {@code null} even if the object can be watched. Provided names cannot
     * be {@code null}.
     * @see #watch(Object)
     */
    public ManagedIterator watch(Object obj, String name)
    {
        // don't mess around with null names
        if (name == null)
        {
            return null;
        }
        Iterator iterator = getIterator(obj);
        if (iterator == null)
        {
            return null;
        }

        ManagedIterator managed = new ManagedIterator(name, iterator, this);
        iterators.push(managed);
        return managed;
    }

    /**
     * This tells the current loop to stop after the current iteration.
     * This is different from "break" common to most programming languages,
     * in that it does not immediately cease activity in the current iteration.
     * Instead, it merely tells the #foreach loop that this is the last time
     * around.
     */
    public void stop()
    {
        // if we have an iterator on the stack
        if (!iterators.empty())
        {
            // stop the top one, so #foreach doesn't loop again
            iterators.peek().stop();
        }
    }

    /**
     * This is just like {@link #stop()} except that the stop command is issued
     * <strong>only</strong> to the loop/iterator with the specified name.
     * If no such loop is found with that name, then no stop command is issued.
     * @see #stop()
     */
    public void stop(String name)
    {
        // just stop the matching one
        for (ManagedIterator iterator : iterators)
        {
            if (iterator.getName().equals(name))
            {
                iterator.stop();
                break;
            }
        }
    }

    /**
     * This is just like {@link #stop(String)} except that the stop command is issued
     * both to the loop/iterator with the specified name and all loops nested within
     * it.  If no such loop is found with that name, then no stop commands are
     * issued.
     * @see #stop()
     * @see #stop(String)
     */
    public void stopTo(String name)
    {
        if (!iterators.empty())
        {
            // create a backup stack to put things back as they were
            Stack<ManagedIterator> backup = new Stack<ManagedIterator>();
            // look for the iterator with the specified name
            boolean found = false;
            while (!found && !iterators.empty())
            {
                ManagedIterator iterator = iterators.pop();
                if (iterator.getName().equals(name))
                {
                    found = true;
                    iterator.stop();
                }
                else
                {
                    // keep a backup of the ones that don't match
                    backup.push(iterator);
                }
            }

            while (!backup.empty())
            {
                // push the nested iterators back
                ManagedIterator iterator = backup.pop();
                iterators.push(iterator);
                if (found)
                {
                    iterator.stop();
                }
            }
        }
    }

    /**
     * This is just like {@link #stop()} except that the stop command is issued
     * <strong>all</strong> the loops being watched by this tool.
     * @see #stop()
     */
    public void stopAll()
    {
        // just stop them all
        for (ManagedIterator iterator : iterators)
        {
            iterator.stop();
        }
    }


    /**
     * Skips ahead the specified number of iterations (if possible).
     * Since this is manual skipping (unlike the automatic skipping
     * provided by the likes of {@link ManagedIterator#exclude(Object)}, any elements
     * skipped are still considered in the results returned by {@link #getCount()}
     * and {@link #isFirst()}.
     */
    public void skip(int number)
    {
        // if we have an iterator on the stack
        if (!iterators.empty())
        {
            // tell the top one to skip the specified number
            skip(number, iterators.peek());
        }
    }

    /**
     * This tells the specified loop to skip ahead the specified number of
     * iterations.
     * @see #skip(int)
     */
    public void skip(int number, String name)
    {
        // just tell the matching one to skip
        ManagedIterator iterator = findIterator(name);
        if (iterator != null)
        {
            skip(number, iterator);
        }
    }

    // does the actual skipping by manually advancing the ManagedIterator
    private void skip(int number, ManagedIterator iterator)
    {
        for (int i=0; i < number; i++)
        {
            if (iterator.hasNext())
            {
                iterator.next();
            }
            else
            {
                break;
            }
        }
    }

    /**
     * Returns {@code true} if the current loop is on its first iteration.
     */
    public Boolean isFirst()
    {
        if (iterators.empty())
        {
            return null;
        }
        return iterators.peek().isFirst();
    }

    /**
     * Returns {@code true} if the loop with the specified name
     * is on its first iteration.
     */
    public Boolean isFirst(String name)
    {
        // just tell the matching one to skip
        ManagedIterator iterator = findIterator(name);
        if (iterator != null)
        {
            return iterator.isFirst();
        }
        return null;
    }

    /**
     * Returns the result of {@link #isFirst}. Exists to allow $loop.first syntax.
     */
    public Boolean getFirst()
    {
        return isFirst();
    }

    /**
     * Returns {@code true} if the current loop is on its last iteration.
     */
    public Boolean isLast()
    {
        if (iterators.empty())
        {
            return null;
        }
        return iterators.peek().isLast();
    }

    /**
     * Returns {@code true} if the loop with the specified name
     * is on its last iteration.
     */
    public Boolean isLast(String name)
    {
        // just tell the matching one to skip
        ManagedIterator iterator = findIterator(name);
        if (iterator != null)
        {
            return iterator.isLast();
        }
        return null;
    }

    /**
     * Returns the result of {@link #isLast}. Exists to allow $loop.last syntax.
     */
    public Boolean getLast()
    {
        return isLast();
    }

    /**
     * Returns the number of items the current loop has handled. So, if this
     * is the first iteration, then the count will be 1.  If you {@link #skip}
     * ahead in this loop, those skipped iterations will still be included in
     * the count.
     */
    public Integer getCount()
    {
        if (iterators.empty())
        {
            return null;
        }
        return iterators.peek().getCount();
    }

    /**
     * Returns the number of items the specified loop has handled. So, if this
     * is the first iteration, then the count will be 1.  If you {@link #skip}
     * ahead in this loop, those skipped iterations will still be included in
     * the count.
     */
    public Integer getCount(String name)
    {
        // just tell the matching one to skip
        ManagedIterator iterator = findIterator(name);
        if (iterator != null)
        {
            return iterator.getCount();
        }
        return null;
    }

    /**
     * Returns the number of loops currently on the stack to tell
     * how deeply nested the current loop is.
     */
    public int getDepth()
    {
        return iterators.size();
    }


    /**
     * Finds the {@link ManagedIterator} with the specified name
     * if it is in this instance's iterator stack.
     */
    protected ManagedIterator findIterator(String name)
    {
        // look for the one with the specified name
        for (ManagedIterator iterator : iterators)
        {
            if (iterator.getName().equals(name))
            {
                return iterator;
            }
        }
        return null;
    }

    /**
     * Don't let templates call this, but allow subclasses
     * and ManagedIterator to have access.
     */
    protected void pop()
    {
        iterators.pop();
    }


    /**
     * Wraps access to {@link ClassUtils#getIterator} is a 
     * nice little try/catch block to prevent exceptions from
     * escaping into the template.  In the case of such problems,
     * this will return {@code null}.
     */
    protected static Iterator getIterator(Object obj)
    {
        if (obj == null)
        {
            return null;
        }
        try
        {
            return ClassUtils.getIterator(obj);
        }
        catch (Exception e)
        {
            //TODO: pick up a log so we can log this
        }
        return null;
    }


    /**
     * Iterator implementation that wraps a standard {@link Iterator}
     * and allows it to be prematurely stopped, skipped ahead, and 
     * associated with a name for advanced nested loop control.
     * This also allows a arbitrary {@link ActionCondition}s to be added
     * in order to have it automatically skip over or stop before
     * certain elements in the iterator.
     */
    public static class ManagedIterator implements Iterator
    {
        private String name;
        private Iterator iterator;
        private LoopTool owner;
        private boolean stopped = false;
        private Boolean first = null;
        private int count = 0;
        private Object next;
        private List<ActionCondition> conditions;

        public ManagedIterator(Iterator iterator, LoopTool owner)
        {
            this("loop"+owner.getDepth(), iterator, owner);
        }

        public ManagedIterator(String name, Iterator iterator, LoopTool owner)
        {
            if (name == null)
            {
                throw new NullPointerException("name cannot be null");
            }
            this.name = name;
            this.iterator = iterator;
            this.owner = owner;
        }

        /**
         * Returns the name of this instance.
         */
        public String getName()
        {
            return this.name;
        }

        /**
         * Returns true if either 0 or 1 elements have been returned
         * by {@link #next()}.
         */
        public boolean isFirst()
        {
            if (first == null || first.booleanValue())
            {
                return true;
            }
            return false;
        }

        /**
         * Returns true if the last element returned by {@link #next()}
         * is the last element available in the iterator being managed
         * which satisfies any/all {@link ActionCondition}s set for this
         * instance. Otherwise, returns false.
         */
        public boolean isLast()
        {
            return !hasNext(false);
        }

        /**
         * Returns true if there are more elements in the iterator
         * being managed by this instance which satisfy all the
         * {@link ActionCondition}s set for this instance.  Returns
         * false if there are no more valid elements available.
         */
        public boolean hasNext()
        {
            return hasNext(true);
        }

        // version that lets isLast check w/o popping this from the stack
        private boolean hasNext(boolean popWhenDone)
        {
            // we don't if we've stopped
            if (stopped)
            {
                return false;
            }
            // we're not stopped, so do we have a next cached?
            if (this.next != null)
            {
                return true;
            }
            // try to get a next that satisfies the conditions
            // if there isn't one, return false; if there is, return true
            return cacheNext(popWhenDone);
        }

        // Tries to get a next that satisfies the conditions.
        // Returns true if there is a next to get.
        private boolean cacheNext(boolean popWhenDone)
        {
            // ok, let's see if we can get a next
            if (!iterator.hasNext())
            {
                if (popWhenDone)
                {
                    // this iterator is done, pop it from the owner's stack
                    owner.pop();
                    // and make sure we don't pop twice
                    stop();
                }
                return false;
            }

            // ok, the iterator has more, but do they work for us?
            this.next = iterator.next();
            if (conditions != null)
            {
                for (ActionCondition condition : conditions)
                {
                    if (condition.matches(this.next))
                    {
                        switch (condition.action)
                        {
                            case EXCLUDE:
                                // recurse on to the next one
                                return cacheNext(popWhenDone);
                            case STOP:
                                stop();
                                return false;
                            default:
                                throw new IllegalStateException("ActionConditions should never have a null Action");
                        }
                    }
                }
            }

            // ok, looks like we have a next that met all the conditions
            return true;
        }

        /**
         * Returns the number of elements returned by {@link #next()} so far.
         */
        public int getCount()
        {
            return count;
        }

        /**
         * Returns the next element that meets the set {@link ActionCondition}s
         * (if any) in the iterator being managed. If there are none left, then
         * this will throw a {@link NoSuchElementException}.
         */
        public Object next()
        {
            // if no next is cached...
            if (this.next == null)
            {
                // try to cache one
                if (!cacheNext(true))
                {
                    // naughty! calling next() without knowing if there is one!
                    throw new NoSuchElementException("There are no more valid elements in this iterator");
                }
            }

            // if we haven't returned any elements, first = true
            if (first == null)
            {
                first = Boolean.TRUE;
            }
            // or if we've only returned one, first = false
            else if (first.booleanValue())
            {
                first = Boolean.FALSE;
            }
            // update the number of iterations made
            count++;

            // get the cached next value
            Object value = this.next;
            // clear the cache
            this.next = null;
            // return the no-longer-cached value
            return value;
        }

        /**
         * This operation is unsupported.
         */
        public void remove()
        {
            // at this point, i don't see any use for this, so...
            throw new UnsupportedOperationException("remove is not currently supported");
        }

        /**
         * Stops this iterator from doing any further iteration.
         */
        public void stop()
        {
            this.stopped = true;
            this.next = null;
        }

        /**
         * Directs this instance to completely exclude
         * any elements equal to the specified Object.
         * @return This same {@link ManagedIterator} instance
         */
        public ManagedIterator exclude(Object compare)
        {
            return condition(new ActionCondition(Action.EXCLUDE, new Equals(compare)));
        }


        /**
         * Directs this instance to stop iterating immediately prior to
         * any element equal to the specified Object.
         * @return This same {@link ManagedIterator} instance
         */
        public ManagedIterator stop(Object compare)
        {
            return condition(new ActionCondition(Action.STOP, new Equals(compare)));
        }

        /**
         * Adds a new {@link ActionCondition} for this instance to check
         * against the elements in the iterator being managed.
         * @return This same {@link ManagedIterator} instance
         */
        public ManagedIterator condition(ActionCondition condition)
        {
            if (condition == null)
            {
                return null;
            }
            if (conditions == null)
            {
                conditions = new ArrayList<ActionCondition>();
            }
            conditions.add(condition);
            return this;
        }

        @Override
        public String toString()
        {
            return ManagedIterator.class.getSimpleName()+':'+getName();
        }
    }

    /**
     * Represents an automatic action taken by a {@link ManagedIterator}
     * when a {@link Condition} is satisfied by the subsequent element.
     */
    public static enum Action
    {
        EXCLUDE, STOP;
    }

    /**
     * Composition class which associates an {@link Action} and {@link Condition}
     * for a {@link ManagedIterator}.
     */
    public static class ActionCondition
    {
        protected Condition condition;
        protected Action action;

        public ActionCondition(Action action, Condition condition)
        {
            if (condition == null || action == null)
            {
                throw new IllegalArgumentException("Condition and Action must both not be null");
            }
            this.condition = condition;
            this.action = action;
        }

        /**
         * Returns true if the specified value meets the set {@link Condition}
         */
        public boolean matches(Object value)
        {
            return condition.test(value);
        }
    }

    /**
     * Represents a function into which a {@link ManagedIterator} can
     * pass it's next element to see if an {@link Action} should be taken.
     */
    public static interface Condition
    {
        public boolean test(Object value);
    }

    /**
     * Base condition class for conditions (assumption here is that
     * conditions are all comparative.  Not much else makes sense to me
     * for this context at this point.
     */
    public static abstract class Comparison implements Condition
    {
        protected Object compare;

        public Comparison(Object compare)
        {
            if (compare == null)
            {
                throw new IllegalArgumentException("Condition must have something to compare to");
            }
            this.compare = compare;
        }
    }

    /**
     * Simple condition that checks elements in the iterator
     * for equality to a specified Object.
     */
    public static class Equals extends Comparison
    {
        public Equals(Object compare)
        {
            super(compare);
        }

        public boolean test(Object value)
        {
            if (value == null)
            {
                return false;
            }
            if (compare.equals(value))
            {
                return true;
            }
            if (value.getClass().equals(compare.getClass()))
            {
                // no point in going on to string comparison
                // if the classes are the same
                return false;
            }
            // compare them as strings as a last resort
            return String.valueOf(value).equals(String.valueOf(compare));
        }
    }

}
