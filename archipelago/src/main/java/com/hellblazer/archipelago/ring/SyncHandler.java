/*
 * Copyright (c) 2023. Hal Hildebrand, All Rights Reserved.
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 */

package com.hellblazer.archipelago.ring;

import java.util.Optional;

/**
 * @author hal.hildebrand
 **/
@FunctionalInterface
public interface SyncHandler<M, T, Comm> {
    void handle(Optional<T> result, SyncRingCommunications.Destination<M, Comm> destination);
}
