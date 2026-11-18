package com.lingmao.scm.security;

import java.time.Instant;

/** Server-side revocation boundary, independent of the JWT transport. */
public interface SessionRegistry {
    Long getUserId(String jti);
    void saveVersion(String jti, long userId, long version, Instant issuedAt);
    void remove(String jti);
}
