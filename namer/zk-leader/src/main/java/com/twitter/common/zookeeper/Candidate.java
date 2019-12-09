// =================================================================================================
// Copyright 2011 Twitter, Inc.
// -------------------------------------------------------------------------------------------------
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this work except in compliance with the License.
// You may obtain a copy of the License in the LICENSE file, or at:
//
//  http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
// =================================================================================================

package com.twitter.common.zookeeper;

import com.google.common.base.Optional;
import com.google.common.base.Supplier;

import com.twitter.finagle.common.zookeeper.Group.JoinException;
import com.twitter.finagle.common.zookeeper.Group.WatchException;
import com.twitter.finagle.common.zookeeper.ZooKeeperClient.ZooKeeperConnectionException;

import org.apache.zookeeper.KeeperException;


/**
 * Interface definition for becoming or querying for a ZooKeeper-based group leader.
 */
public interface Candidate {

    /**
     * Returns the current group leader by querying ZooKeeper synchronously.
     *
     * @return the current group leader's identifying data or {@link Optional#absent()} if there is
     *     no leader
     * @throws ZooKeeperConnectionException if there was a problem connecting to ZooKeeper
     * @throws KeeperException if there was a problem reading the leader information
     * @throws InterruptedException if this thread is interrupted getting the leader
     */
    public Optional<byte[]> getLeaderData()
            throws ZooKeeperConnectionException, KeeperException, InterruptedException;

    /**
     * Encapsulates a leader that can be elected and subsequently defeated.
     */
    interface Leader {

        /**
         * Called when this leader has been elected.
         *
         * @param abdicate a command that can be used to abdicate leadership and force a new election
         */
        void onElected(ExceptionalCommand<JoinException> abdicate);

        /**
         * Called when the leader has been ousted.  Can occur either if the leader abdicates or if an
         * external event causes the leader to lose its leadership role (session expiration).
         */
        void onDefeated();
    }

    /**
     * Offers this candidate in leadership elections for as long as the current jvm process is alive.
     * Upon election, the {@code onElected} callback will be executed and a command that can be used
     * to abdicate leadership will be passed in.  If the elected leader jvm process dies or the
     * elected leader successfully abdicates then a new leader will be elected.  Leaders that
     * successfully abdicate are removed from the group and will not be eligible for leadership
     * election unless {@link #offerLeadership(Leader)} is called again.
     *
     * @param leader the leader to notify of election and defeat events
     * @throws JoinException if there was a problem joining the group
     * @throws WatchException if there is a problem generating the 1st group membership list
     * @throws InterruptedException if interrupted waiting to join the group and determine initial
     *     election results
     * @return a supplier that can be queried to find out if this leader is currently elected
     */
    public Supplier<Boolean> offerLeadership(Leader leader)
            throws JoinException, WatchException, InterruptedException;
}
