package io.github.mochiuaena.triage.sdk;

import java.net.SocketTimeoutException;
import java.net.http.HttpTimeoutException;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.concurrent.TimeoutException;

final class TriageHttpTimeouts {
    private TriageHttpTimeouts() { }

    static boolean isTimeout(Throwable error) {
        var seen = Collections.newSetFromMap(new IdentityHashMap<Throwable, Boolean>());
        for (Throwable cause = error; cause != null && seen.add(cause); cause = cause.getCause()) {
            String type = cause.getClass().getName();
            if (cause instanceof SocketTimeoutException || cause instanceof HttpTimeoutException || cause instanceof TimeoutException
                || type.equals("io.netty.handler.timeout.ReadTimeoutException")
                || type.equals("io.netty.channel.ConnectTimeoutException")
                || type.equals("io.netty.handler.timeout.WriteTimeoutException")) return true;
        }
        return false;
    }
}
