package dev.lone.itemsadder.api;

import java.util.function.Function;

/** Stands in for the ItemsAdder item lookup that ItemResolver reaches through reflection. */
public final class CustomStack {

    public static Function<String, Object> lookup = id -> null;

    private CustomStack() {}

    public static Object getInstance(String id) {
        return lookup.apply(id);
    }
}
