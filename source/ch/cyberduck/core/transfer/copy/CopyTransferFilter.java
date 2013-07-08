package ch.cyberduck.core.transfer.copy;

/*
 * Copyright (c) 2002-2013 David Kocher. All rights reserved.
 * http://cyberduck.ch/
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 2 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * Bug fixes, suggestions and comments should be sent to feedback@cyberduck.ch
 */

import ch.cyberduck.core.Path;
import ch.cyberduck.core.Permission;
import ch.cyberduck.core.Preferences;
import ch.cyberduck.core.Session;
import ch.cyberduck.core.exception.BackgroundException;
import ch.cyberduck.core.features.Timestamp;
import ch.cyberduck.core.features.UnixPermission;
import ch.cyberduck.core.transfer.TransferOptions;
import ch.cyberduck.core.transfer.TransferPathFilter;
import ch.cyberduck.core.transfer.TransferStatus;

import org.apache.log4j.Logger;

import java.util.Map;

/**
 * @version $Id$
 */
public class CopyTransferFilter implements TransferPathFilter {
    private static final Logger log = Logger.getLogger(CopyTransferFilter.class);

    private Session<?> destination;

    private final Map<Path, Path> files;

    public CopyTransferFilter(final Session destination, final Map<Path, Path> files) {
        this.destination = destination;
        this.files = files;
    }

    @Override
    public boolean accept(final Session session, final Path source) throws BackgroundException {
        if(source.attributes().isDirectory()) {
            final Path destination = files.get(source);
            // Do not attempt to create a directory that already exists
            if(session.exists(destination)) {
                return false;
            }
        }
        return true;
    }

    @Override
    public TransferStatus prepare(final Session session, final Path source) throws BackgroundException {
        final TransferStatus status = new TransferStatus();
        if(source.attributes().isFile()) {
            status.setLength(source.attributes().getSize());
        }
        return status;
    }

    @Override
    public void complete(final Session<?> session, final Path source, final TransferOptions options, final TransferStatus status) throws BackgroundException {
        if(status.isComplete()) {
            final UnixPermission unix = destination.getFeature(UnixPermission.class, null);
            if(unix != null) {
                if(Preferences.instance().getBoolean("queue.upload.changePermissions")) {
                    Permission permission = source.attributes().getPermission();
                    if(!Permission.EMPTY.equals(permission)) {
                        try {
                            unix.setUnixPermission(files.get(source), permission);
                        }
                        catch(BackgroundException e) {
                            // Ignore
                            log.warn(e.getMessage());
                        }
                    }
                }
            }
            final Timestamp timestamp = destination.getFeature(Timestamp.class, null);
            if(timestamp != null) {
                if(Preferences.instance().getBoolean("queue.upload.preserveDate")) {
                    try {
                        timestamp.setTimestamp(files.get(source), source.attributes().getCreationDate(),
                                source.attributes().getModificationDate(),
                                source.attributes().getAccessedDate());
                    }
                    catch(BackgroundException e) {
                        // Ignore
                        log.warn(e.getMessage());
                    }
                }
            }
        }
    }
}