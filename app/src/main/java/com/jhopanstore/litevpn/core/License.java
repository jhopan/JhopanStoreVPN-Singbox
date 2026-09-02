package com.jhopanstore.litevpn.core;

/** Immutable offline license payload embedded in a .jvs file. */
public final class License {
    public final String vless;
    public final String name;
    public final String message;
    public final String connectedMessage;
    public final String hwid;
    public final boolean lock;
    public final long expiry;

    public License(String vless, String name, String message, String connectedMessage, String hwid, boolean lock, long expiry) {
        this.vless = vless;
        this.name = name;
        this.message = message;
        this.connectedMessage = connectedMessage;
        this.hwid = hwid;
        this.lock = lock;
        this.expiry = expiry;
    }
}
