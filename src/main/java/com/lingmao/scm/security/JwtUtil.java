package com.lingmao.scm.security;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;

@Component
public class JwtUtil {
    /**
     * @Component：和 @Service 一样，告诉 Spring"这是一个要交给你管理的对象"。它不是业务 Service，只是一个工具，所以用更通用的 @Component
     * 为什么这次自己写构造器，不用 @RequiredArgsConstructor：两个参数要用 @Value 从配置里取值，@Value 得写在构造器参数上。Lombok 生成的构造器上没法加这个注解，所以手写。效果和 Lombok 一样，都是构造器注入，字段也是 final
     * @Value("${lingmao.jwt.ttl}") Duration ttl：配置里写的是 2h，Spring 会自动把它转成 Duration（时长）类型。写 30m、7d 也行
     */
    private final SecretKey key;
    private final Duration ttl;

    public JwtUtil(@Value("${lingmao.jwt.secret}") String secret,
                   @Value("${lingmao.jwt.ttl}") Duration ttl) {
        this.key = Keys.hmacShaKeyFor(Decoders.BASE64.decode(secret));
        this.ttl = ttl;
    }

    /** 给某个用户签发 token */
    public String createToken(Long userId) {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(String.valueOf(userId))
                .id(UUID.randomUUID().toString())
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(ttl)))
                .signWith(key)
                .compact();
    }
}