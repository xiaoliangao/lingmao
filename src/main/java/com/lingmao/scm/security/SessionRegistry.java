package com.lingmao.scm.security;

import java.time.Instant;

/** 服务端的撤销边界，和 JWT 怎么传无关。 */
public interface SessionRegistry {
    Long getUserId(String jti);
    void saveVersion(String jti, long userId, long version, Instant issuedAt);
    void remove(String jti);
}
