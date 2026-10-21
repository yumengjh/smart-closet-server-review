package com.smartcloset.security;

/** Reserved storage namespace; never a login identity or a creatable account. */
public final class PresetOwner {
    public static final String USERNAME = "__preset__";
    private PresetOwner() {}
    public static boolean reserved(String username) { return USERNAME.equalsIgnoreCase(username); }
}
