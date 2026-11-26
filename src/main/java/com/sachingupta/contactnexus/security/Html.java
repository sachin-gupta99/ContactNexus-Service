package com.sachingupta.contactnexus.security;

/** Minimal escaping for user-supplied names placed into email HTML. */
final class Html {

    private Html() {
    }

    static String escape(String raw) {
        if (raw == null) return "";
        return raw.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }
}
