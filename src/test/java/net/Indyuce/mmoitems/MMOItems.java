package net.Indyuce.mmoitems;

/** Stands in for the MMOItems entry point that ItemResolver reaches through reflection. */
public final class MMOItems {

    public static MMOItems plugin;

    private final Object types;
    private final Object items;

    public MMOItems(Object types, Object items) {
        this.types = types;
        this.items = items;
    }

    public Object getTypes() {
        return types;
    }

    public Object getItems() {
        return items;
    }
}
