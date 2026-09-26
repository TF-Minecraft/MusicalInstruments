package com.nexomc.nexo.api;

import java.util.function.Function;

/** Stands in for the Nexo item lookup that ItemResolver reaches through reflection. */
public final class NexoItems {

    public static Function<String, Object> lookup = id -> null;

    private NexoItems() {}

    public static Object itemFromId(String id) {
        return lookup.apply(id);
    }
}
