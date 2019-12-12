/**
 * Copyright (c) 2009 - 2012 Red Hat, Inc.
 *
 * This software is licensed to you under the GNU General Public License,
 * version 2 (GPLv2). There is NO WARRANTY for this software, express or
 * implied, including the implied warranties of MERCHANTABILITY or FITNESS
 * FOR A PARTICULAR PURPOSE. You should have received a copy of GPLv2
 * along with this software; if not, see
 * http://www.gnu.org/licenses/old-licenses/gpl-2.0.txt.
 *
 * Red Hat trademarks are not licensed under GPLv2. No permission is
 * granted to use or replicate Red Hat trademarks that are incorporated
 * in this software or its documentation.
 */
package org.candlepin.auth;

import org.candlepin.auth.permissions.AttachPermission;
import org.candlepin.auth.permissions.AsyncJobStatusPermission;
import org.candlepin.auth.permissions.ConsumerEntitlementPermission;
import org.candlepin.auth.permissions.ConsumerOrgHypervisorPermission;
import org.candlepin.auth.permissions.ConsumerPermission;
import org.candlepin.auth.permissions.ConsumerServiceLevelsPermission;
import org.candlepin.auth.permissions.OwnerPoolsPermission;
import org.candlepin.model.Consumer;
import org.candlepin.model.Owner;

import java.util.Collections;

/**
 *
 */
public class ConsumerPrincipal extends Principal {
    private Consumer consumer;

    public ConsumerPrincipal(Consumer consumer, Owner owner) {
        this.consumer = consumer;

        addPermission(new ConsumerPermission(consumer, owner));

        // Allow consumers to attach entitlements:
        addPermission(new AttachPermission(owner));

        // Allow consumers to view and manage their entitlements:
        addPermission(new ConsumerEntitlementPermission(consumer, owner));

        // Allow consumers to list their owner's pools and subscriptions:
        addPermission(new OwnerPoolsPermission(owner));

        // Allow consumers to view their owner's service levels:
        addPermission(new ConsumerServiceLevelsPermission(consumer, owner));

        // Allow consumers to run virt-who hypervisor update
        addPermission(new ConsumerOrgHypervisorPermission(owner));

        // Allow consumers to check the status of their own jobs.
        addPermission(new AsyncJobStatusPermission(getData(), Collections.singleton(owner.getId())));
    }

    public Consumer getConsumer() {
        return consumer;
    }

    // Note: automatically generated by Netbeans
    @Override
    public boolean equals(Object obj) {
        if (obj == null) {
            return false;
        }
        if (getClass() != obj.getClass()) {
            return false;
        }
        final ConsumerPrincipal other = (ConsumerPrincipal) obj;
        if (this.consumer != other.consumer &&
            (this.consumer == null || !this.consumer.equals(other.consumer))) {
            return false;
        }
        return true;
    }

    // Note: automatically generated by Netbeans
    @Override
    public int hashCode() {
        int hash = 3;
        hash = 83 * hash + (this.consumer != null ? this.consumer.hashCode() : 0);
        return hash;
    }

    @Override
    public String getName() {
        return consumer.getUuid();
    }

    @Override
    public String getType() {
        return "consumer";
    }

    @Override
    public boolean hasFullAccess() {
        return false;
    }
}
