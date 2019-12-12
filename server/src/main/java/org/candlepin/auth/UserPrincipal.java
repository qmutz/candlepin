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

import org.candlepin.auth.permissions.AsyncJobStatusPermission;
import org.candlepin.auth.permissions.Permission;
import org.candlepin.auth.permissions.UserUserPermission;
import org.candlepin.model.Owner;

import java.util.Collection;
import java.util.LinkedList;
import java.util.List;

/**
 *
 */
public class UserPrincipal extends Principal {

    private String username;
    private boolean admin;

    /**
     * Create a user principal
     *
     * @param username
     */
    public UserPrincipal(String username, Collection<Permission> permissions, boolean admin) {
        this.username = username;
        this.admin = admin;

        if (permissions != null) {
            this.permissions.addAll(permissions);
        }

        // User principals should have an implicit permission to view their own data.
        addPermission(new UserUserPermission(username));

        // Allow users to check the status of their own jobs.
        addPermission(new AsyncJobStatusPermission(getData(), this.getOwnerIds()));
    }

    public String getUsername() {
        return username;
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

        final UserPrincipal other = (UserPrincipal) obj;
        if ((this.username == null) ?
            (other.username != null) : !this.username.equals(other.username)) {
            return false;
        }

        return true;
    }

    // Note: automatically generated by Netbeans
    @Override
    public int hashCode() {
        int hash = 5;
        hash = 47 * hash + (this.username != null ? this.username.hashCode() : 0);
        return hash;
    }

    @Override
    public String getType() {
        return "user";
    }

    @Override
    public String getName() {
        return username;
    }

    @Override
    public boolean hasFullAccess() {
        return this.admin;
    }

    public List<String> getOwnerIds() {
        List<String> ownerIds = new LinkedList<>();

        for (Owner owner : getOwners()) {
            ownerIds.add(owner.getId());
        }

        return ownerIds;
    }

    public List<String> getOwnerKeys() {
        List<String> ownerKeys = new LinkedList<>();

        for (Owner owner : getOwners()) {
            ownerKeys.add(owner.getKey());
        }

        return ownerKeys;
    }

    /**
     * @return list of owners this principal has some level of access to.
     */
    public List<Owner> getOwners() {
        List<Owner> owners = new LinkedList<>();

        for (Permission permission : permissions) {
            Owner o = permission.getOwner();
            if (o != null) {
                owners.add(o);
            }
        }

        return owners;
    }

    @Override
    public boolean canAccess(Object target, SubResource subResource, Access access) {
        return this.hasFullAccess() || super.canAccess(target, subResource, access);
    }

}
